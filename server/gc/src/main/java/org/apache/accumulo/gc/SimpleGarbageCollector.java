/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.gc;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.IsolatedScanner;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.impl.Tables;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.PartialKey;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.gc.thrift.GCMonitorService.Iface;
import org.apache.accumulo.core.gc.thrift.GCMonitorService.Processor;
import org.apache.accumulo.core.gc.thrift.GCStatus;
import org.apache.accumulo.core.gc.thrift.GcCycleStats;
import org.apache.accumulo.core.master.state.tables.TableState;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.metadata.RootTable;
import org.apache.accumulo.core.metadata.schema.MetadataSchema;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.DataFileColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.ScanFileColumnFamily;
import org.apache.accumulo.core.replication.ReplicationSchema.StatusSection;
import org.apache.accumulo.core.replication.proto.Replication.Status;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.Credentials;
import org.apache.accumulo.core.security.SecurityUtil;
import org.apache.accumulo.core.security.thrift.TCredentials;
import org.apache.accumulo.core.util.NamingThreadFactory;
import org.apache.accumulo.core.util.Pair;
import org.apache.accumulo.core.util.ServerServices;
import org.apache.accumulo.core.util.ServerServices.Service;
import org.apache.accumulo.core.util.SslConnectionParams;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.accumulo.core.zookeeper.ZooUtil;
import org.apache.accumulo.fate.zookeeper.ZooLock.LockLossReason;
import org.apache.accumulo.fate.zookeeper.ZooLock.LockWatcher;
import org.apache.accumulo.gc.replication.CloseWriteAheadLogReferences;
import org.apache.accumulo.server.Accumulo;
import org.apache.accumulo.server.ServerConstants;
import org.apache.accumulo.server.ServerOpts;
import org.apache.accumulo.server.client.HdfsZooInstance;
import org.apache.accumulo.server.conf.ServerConfiguration;
import org.apache.accumulo.server.fs.VolumeManager;
import org.apache.accumulo.server.fs.VolumeManager.FileType;
import org.apache.accumulo.server.fs.VolumeManagerImpl;
import org.apache.accumulo.server.fs.VolumeUtil;
import org.apache.accumulo.server.replication.ReplicationTable;
import org.apache.accumulo.server.security.SystemCredentials;
import org.apache.accumulo.server.tables.TableManager;
import org.apache.accumulo.server.util.Halt;
import org.apache.accumulo.server.util.RpcWrapper;
import org.apache.accumulo.server.util.TServerUtils;
import org.apache.accumulo.server.util.TabletIterator;
import org.apache.accumulo.server.zookeeper.ZooLock;
import org.apache.accumulo.trace.instrument.CountSampler;
import org.apache.accumulo.trace.instrument.Sampler;
import org.apache.accumulo.trace.instrument.Span;
import org.apache.accumulo.trace.instrument.Trace;
import org.apache.accumulo.trace.thrift.TInfo;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;

import com.beust.jcommander.Parameter;
import com.google.common.base.Function;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.net.HostAndPort;
import com.google.protobuf.InvalidProtocolBufferException;

public class SimpleGarbageCollector implements Iface {
  private static final Text EMPTY_TEXT = new Text();

  /**
   * Options for the garbage collector.
   */
  static class Opts extends ServerOpts {
    @Parameter(names = {"-v", "--verbose"}, description = "extra information will get printed to stdout also")
    boolean verbose = false;
    @Parameter(names = {"-s", "--safemode"}, description = "safe mode will not delete files")
    boolean safeMode = false;
  }

  /**
   * A fraction representing how much of the JVM's available memory should be
   * used for gathering candidates.
   */
  static final float CANDIDATE_MEMORY_PERCENTAGE = 0.75f;

  private static final Logger log = Logger.getLogger(SimpleGarbageCollector.class);

  private Credentials credentials;
  private long gcStartDelay;
  private VolumeManager fs;
  private boolean useTrash = true;
  private Opts opts = new Opts();
  private ZooLock lock;

  private GCStatus status = new GCStatus(new GcCycleStats(), new GcCycleStats(), new GcCycleStats(), new GcCycleStats());

  private int numDeleteThreads;

  private Instance instance;

  public static void main(String[] args) throws UnknownHostException, IOException {
    SecurityUtil.serverLogin(ServerConfiguration.getSiteConfiguration());
    Instance instance = HdfsZooInstance.getInstance();
    ServerConfiguration serverConf = new ServerConfiguration(instance);
    final VolumeManager fs = VolumeManagerImpl.get();
    Accumulo.init(fs, serverConf, "gc");
    Opts opts = new Opts();
    opts.parseArgs("gc", args);
    SimpleGarbageCollector gc = new SimpleGarbageCollector(opts);

    gc.init(fs, instance, SystemCredentials.get(), serverConf.getConfiguration().getBoolean(Property.GC_TRASH_IGNORE));
    Accumulo.enableTracing(opts.getAddress(), "gc");
    gc.run();
  }

  /**
   * Creates a new garbage collector.
   *
   * @param opts options
   */
  public SimpleGarbageCollector(Opts opts) {
    this.opts = opts;
  }

  /**
   * Gets the credentials used by this GC.
   *
   * @return credentials
   */
  Credentials getCredentials() {
    return credentials;
  }
  /**
   * Gets the delay before the first collection.
   *
   * @return start delay, in milliseconds
   */
  long getStartDelay() {
    return gcStartDelay;
  }
  /**
   * Gets the volume manager used by this GC.
   *
   * @return volume manager
   */
  VolumeManager getVolumeManager() {
    return fs;
  }
  /**
   * Checks if the volume manager should move files to the trash rather than
   * delete them.
   *
   * @return true if trash is used
   */
  boolean isUsingTrash() {
    return useTrash;
  }
  /**
   * Gets the options for this garbage collector.
   */
  Opts getOpts() {
    return opts;
  }
  /**
   * Gets the number of threads used for deleting files.
   *
   * @return number of delete threads
   */
  int getNumDeleteThreads() {
    return numDeleteThreads;
  }
  /**
   * Gets the instance used by this GC.
   *
   * @return instance
   */
  Instance getInstance() {
    return instance;
  }

  /**
   * Initializes this garbage collector with the current system configuration.
   *
   * @param fs volume manager
   * @param instance instance
   * @param credentials credentials
   * @param noTrash true to not move files to trash instead of deleting
   */
  public void init(VolumeManager fs, Instance instance, Credentials credentials, boolean noTrash) {
    init(fs, instance, credentials, noTrash, ServerConfiguration.getSystemConfiguration(instance));
  }

  /**
   * Initializes this garbage collector.
   *
   * @param fs volume manager
   * @param instance instance
   * @param credentials credentials
   * @param noTrash true to not move files to trash instead of deleting
   * @param systemConfig system configuration
   */
  public void init(VolumeManager fs, Instance instance, Credentials credentials, boolean noTrash, AccumuloConfiguration systemConfig) {
    this.fs = fs;
    this.credentials = credentials;
    this.instance = instance;

    gcStartDelay = systemConfig.getTimeInMillis(Property.GC_CYCLE_START);
    long gcDelay = systemConfig.getTimeInMillis(Property.GC_CYCLE_DELAY);
    numDeleteThreads = systemConfig.getCount(Property.GC_DELETE_THREADS);
    log.info("start delay: " + gcStartDelay + " milliseconds");
    log.info("time delay: " + gcDelay + " milliseconds");
    log.info("safemode: " + opts.safeMode);
    log.info("verbose: " + opts.verbose);
    log.info("memory threshold: " + CANDIDATE_MEMORY_PERCENTAGE + " of " + Runtime.getRuntime().maxMemory() + " bytes");
    log.info("delete threads: " + numDeleteThreads);
    useTrash = !noTrash;
  }

  private class GCEnv implements GarbageCollectionEnvironment {

    private String tableName;

    GCEnv(String tableName) {
      this.tableName = tableName;
    }

    @Override
    public List<String> getCandidates(String continuePoint) throws TableNotFoundException, AccumuloException, AccumuloSecurityException {
      // want to ensure GC makes progress... if the 1st N deletes are stable and we keep processing them,
      // then will never inspect deletes after N
      Range range = MetadataSchema.DeletesSection.getRange();
      if (continuePoint != null && !continuePoint.isEmpty()) {
        String continueRow = MetadataSchema.DeletesSection.getRowPrefix() + continuePoint;
        range = new Range(new Key(continueRow).followingKey(PartialKey.ROW), true, range.getEndKey(), range.isEndKeyInclusive());
      }

      Scanner scanner = instance.getConnector(credentials.getPrincipal(), credentials.getToken()).createScanner(tableName, Authorizations.EMPTY);
      scanner.setRange(range);
      List<String> result = new ArrayList<String>();
      // find candidates for deletion; chop off the prefix
      for (Entry<Key,Value> entry : scanner) {
        String cand = entry.getKey().getRow().toString().substring(MetadataSchema.DeletesSection.getRowPrefix().length());
        result.add(cand);
        if (almostOutOfMemory(Runtime.getRuntime())) {
          log.info("List of delete candidates has exceeded the memory threshold. Attempting to delete what has been gathered so far.");
          break;
        }
      }

      return result;

    }

    @Override
    public Iterator<String> getBlipIterator() throws TableNotFoundException, AccumuloException, AccumuloSecurityException {
      IsolatedScanner scanner = new IsolatedScanner(instance.getConnector(credentials.getPrincipal(), credentials.getToken()).createScanner(tableName,
          Authorizations.EMPTY));

      scanner.setRange(MetadataSchema.BlipSection.getRange());

      return Iterators.transform(scanner.iterator(), new Function<Entry<Key,Value>,String>() {
        @Override
        public String apply(Entry<Key,Value> entry) {
          return entry.getKey().getRow().toString().substring(MetadataSchema.BlipSection.getRowPrefix().length());
        }
      });
    }

    @Override
    public Iterator<Entry<Key,Value>> getReferenceIterator() throws TableNotFoundException, AccumuloException, AccumuloSecurityException {
      IsolatedScanner scanner = new IsolatedScanner(instance.getConnector(credentials.getPrincipal(), credentials.getToken()).createScanner(tableName,
          Authorizations.EMPTY));
      scanner.fetchColumnFamily(DataFileColumnFamily.NAME);
      scanner.fetchColumnFamily(ScanFileColumnFamily.NAME);
      TabletsSection.ServerColumnFamily.DIRECTORY_COLUMN.fetch(scanner);
      TabletIterator tabletIterator = new TabletIterator(scanner, MetadataSchema.TabletsSection.getRange(), false, true);

      return Iterators.concat(Iterators.transform(tabletIterator, new Function<Map<Key,Value>,Iterator<Entry<Key,Value>>>() {
        @Override
        public Iterator<Entry<Key,Value>> apply(Map<Key,Value> input) {
          return input.entrySet().iterator();
        }
      }));
    }

    @Override
    public Set<String> getTableIDs() {
      return Tables.getIdToNameMap(instance).keySet();
    }

    @Override
    public void delete(SortedMap<String,String> confirmedDeletes) throws IOException, AccumuloException, AccumuloSecurityException, TableNotFoundException {

      if (opts.safeMode) {
        if (opts.verbose)
          System.out.println("SAFEMODE: There are " + confirmedDeletes.size() + " data file candidates marked for deletion.%n"
              + "          Examine the log files to identify them.%n");
        log.info("SAFEMODE: Listing all data file candidates for deletion");
        for (String s : confirmedDeletes.values())
          log.info("SAFEMODE: " + s);
        log.info("SAFEMODE: End candidates for deletion");
        return;
      }

      Connector c = instance.getConnector(SystemCredentials.get().getPrincipal(), SystemCredentials.get().getToken());
      BatchWriter writer = c.createBatchWriter(tableName, new BatchWriterConfig());

      // when deleting a dir and all files in that dir, only need to delete the dir
      // the dir will sort right before the files... so remove the files in this case
      // to minimize namenode ops
      Iterator<Entry<String,String>> cdIter = confirmedDeletes.entrySet().iterator();

      String lastDir = null;
      while (cdIter.hasNext()) {
        Entry<String,String> entry = cdIter.next();
        String relPath = entry.getKey();
        String absPath = fs.getFullPath(FileType.TABLE, entry.getValue()).toString();

        if (isDir(relPath)) {
          lastDir = absPath;
        } else if (lastDir != null) {
          if (absPath.startsWith(lastDir)) {
            log.debug("Ignoring " + entry.getValue() + " because " + lastDir + " exist");
            try {
              putMarkerDeleteMutation(entry.getValue(), writer);
            } catch (MutationsRejectedException e) {
              throw new RuntimeException(e);
            }
            cdIter.remove();
          } else {
            lastDir = null;
          }
        }
      }

      final BatchWriter finalWriter = writer;

      ExecutorService deleteThreadPool = Executors.newFixedThreadPool(numDeleteThreads, new NamingThreadFactory("deleting"));

      final List<Pair<Path,Path>> replacements = ServerConstants.getVolumeReplacements();

      for (final String delete : confirmedDeletes.values()) {

        Runnable deleteTask = new Runnable() {
          @Override
          public void run() {
            boolean removeFlag;

            try {
              Path fullPath;
              String switchedDelete = VolumeUtil.switchVolume(delete, FileType.TABLE, replacements);
              if (switchedDelete != null) {
                // actually replacing the volumes in the metadata table would be tricky because the entries would be different rows. So it could not be
                // atomically in one mutation and extreme care would need to be taken that delete entry was not lost. Instead of doing that, just deal with
                // volume switching when something needs to be deleted. Since the rest of the code uses suffixes to compare delete entries, there is no danger
                // of deleting something that should not be deleted. Must not change value of delete variable because thats whats stored in metadata table.
                log.debug("Volume replaced " + delete + " -> " + switchedDelete);
                fullPath = fs.getFullPath(FileType.TABLE, switchedDelete);
              } else {
                fullPath = fs.getFullPath(FileType.TABLE, delete);
              }

              log.debug("Deleting " + fullPath);

              if (moveToTrash(fullPath) || fs.deleteRecursively(fullPath)) {
                // delete succeeded, still want to delete
                removeFlag = true;
                synchronized (SimpleGarbageCollector.this) {
                  ++status.current.deleted;
                }
              } else if (fs.exists(fullPath)) {
                // leave the entry in the metadata; we'll try again later
                removeFlag = false;
                synchronized (SimpleGarbageCollector.this) {
                  ++status.current.errors;
                }
                log.warn("File exists, but was not deleted for an unknown reason: " + fullPath);
              } else {
                // this failure, we still want to remove the metadata entry
                removeFlag = true;
                synchronized (SimpleGarbageCollector.this) {
                  ++status.current.errors;
                }
                String parts[] = delete.split("/");
                if (parts.length > 2) {
                  String tableId = parts[parts.length - 3];
                  String tabletDir = parts[parts.length - 2];
                  TableManager.getInstance().updateTableStateCache(tableId);
                  TableState tableState = TableManager.getInstance().getTableState(tableId);
                  if (tableState != null && tableState != TableState.DELETING) {
                    // clone directories don't always exist
                    if (!tabletDir.startsWith("c-"))
                      log.debug("File doesn't exist: " + fullPath);
                  }
                } else {
                  log.warn("Very strange path name: " + delete);
                }
              }

              // proceed to clearing out the flags for successful deletes and
              // non-existent files
              if (removeFlag && finalWriter != null) {
                putMarkerDeleteMutation(delete, finalWriter);
              }
            } catch (Exception e) {
              log.error(e, e);
            }

          }

        };

        deleteThreadPool.execute(deleteTask);
      }

      deleteThreadPool.shutdown();

      try {
        while (!deleteThreadPool.awaitTermination(1000, TimeUnit.MILLISECONDS)) {}
      } catch (InterruptedException e1) {
        log.error(e1, e1);
      }

      if (writer != null) {
        try {
          writer.close();
        } catch (MutationsRejectedException e) {
          log.error("Problem removing entries from the metadata table: ", e);
        }
      }
    }

    @Override
    public void deleteTableDirIfEmpty(String tableID) throws IOException {
      // if dir exist and is empty, then empty list is returned...
      // hadoop 1.0 will return null if the file doesn't exist
      // hadoop 2.0 will throw an exception if the file does not exist
      for (String dir : ServerConstants.getTablesDirs()) {
        FileStatus[] tabletDirs = null;
        try {
          tabletDirs = fs.listStatus(new Path(dir + "/" + tableID));
        } catch (FileNotFoundException ex) {
          // ignored
        }
        if (tabletDirs == null)
          continue;

        if (tabletDirs.length == 0) {
          Path p = new Path(dir + "/" + tableID);
          log.debug("Removing table dir " + p);
          if (!moveToTrash(p))
            fs.delete(p);
        }
      }
    }

    @Override
    public void incrementCandidatesStat(long i) {
      status.current.candidates += i;
    }

    @Override
    public void incrementInUseStat(long i) {
      status.current.inUse += i;
    }

    @Override
    public Iterator<Entry<String,Status>> getReplicationNeededIterator() throws AccumuloException, AccumuloSecurityException {
      Connector conn = instance.getConnector(credentials.getPrincipal(), credentials.getToken());
      try {
        Scanner s = ReplicationTable.getScanner(conn);
        StatusSection.limit(s);
        return Iterators.transform(s.iterator(), new Function<Entry<Key,Value>,Entry<String,Status>>() {

          @Override
          public Entry<String,Status> apply(Entry<Key,Value> input) {
            String file = input.getKey().getRow().toString();
            Status stat;
            try {
              stat = Status.parseFrom(input.getValue().get());
            } catch (InvalidProtocolBufferException e) {
              log.warn("Could not deserialize protobuf for: " + input.getKey());
              stat = null;
            }
            return Maps.immutableEntry(file, stat);
          }

        });
      } catch (TableNotFoundException e) {
        // No elements that we need to preclude
        return Iterators.emptyIterator();
      }
    }

  }

  private void run() {
    long tStart, tStop;

    // Sleep for an initial period, giving the master time to start up and
    // old data files to be unused

    try {
      getZooLock(startStatsService());
    } catch (Exception ex) {
      log.error(ex, ex);
      System.exit(1);
    }

    try {
      log.debug("Sleeping for " + gcStartDelay + " milliseconds before beginning garbage collection cycles");
      Thread.sleep(gcStartDelay);
    } catch (InterruptedException e) {
      log.warn(e, e);
      return;
    }

    Sampler sampler = new CountSampler(100);

    while (true) {
      if (sampler.next())
        Trace.on("gc");

      Span gcSpan = Trace.start("loop");
      tStart = System.currentTimeMillis();
      try {
        System.gc(); // make room

        status.current.started = System.currentTimeMillis();

        new GarbageCollectionAlgorithm().collect(new GCEnv(RootTable.NAME));
        new GarbageCollectionAlgorithm().collect(new GCEnv(MetadataTable.NAME));

        log.info("Number of data file candidates for deletion: " + status.current.candidates);
        log.info("Number of data file candidates still in use: " + status.current.inUse);
        log.info("Number of successfully deleted data files: " + status.current.deleted);
        log.info("Number of data files delete failures: " + status.current.errors);

        status.current.finished = System.currentTimeMillis();
        status.last = status.current;
        status.current = new GcCycleStats();

      } catch (Exception e) {
        log.error(e, e);
      }

      tStop = System.currentTimeMillis();
      log.info(String.format("Collect cycle took %.2f seconds", ((tStop - tStart) / 1000.0)));

      // We want to prune references to fully-replicated WALs from the replication table which are no longer referenced in the metadata table
      // before running GarbageCollectWriteAheadLogs to ensure we delete as many files as possible.
      Span replSpan = Trace.start("replicationClose");
      try {
        CloseWriteAheadLogReferences closeWals = new CloseWriteAheadLogReferences(instance, credentials);
        closeWals.run();
      } catch (Exception e) {
        log.error("Error trying to close write-ahead logs for replication table", e);
      } finally {
        replSpan.stop();
      }

      // Clean up any unused write-ahead logs
      Span waLogs = Trace.start("walogs");
      try {
        GarbageCollectWriteAheadLogs walogCollector = new GarbageCollectWriteAheadLogs(instance, fs, useTrash);
        log.info("Beginning garbage collection of write-ahead logs");
        walogCollector.collect(status);
      } catch (Exception e) {
        log.error(e, e);
      } finally {
        waLogs.stop();
      }
      gcSpan.stop();

      // we just made a lot of metadata changes: flush them out
      try {
        Connector connector = instance.getConnector(credentials.getPrincipal(), credentials.getToken());
        connector.tableOperations().compact(MetadataTable.NAME, null, null, true, true);
        connector.tableOperations().compact(RootTable.NAME, null, null, true, true);
      } catch (Exception e) {
        log.warn(e, e);
      }

      Trace.offNoFlush();
      try {
        long gcDelay = ServerConfiguration.getSystemConfiguration(instance).getTimeInMillis(Property.GC_CYCLE_DELAY);
        log.debug("Sleeping for " + gcDelay + " milliseconds");
        Thread.sleep(gcDelay);
      } catch (InterruptedException e) {
        log.warn(e, e);
        return;
      }
    }
  }

  /**
   * Moves a file to trash. If this garbage collector is not using trash, this
   * method returns false and leaves the file alone. If the file is missing,
   * this method returns false as opposed to throwing an exception.
   *
   * @param path
   * @return true if the file was moved to trash
   * @throws IOException if the volume manager encountered a problem
   */
  boolean moveToTrash(Path path) throws IOException {
    if (!useTrash)
      return false;
    try {
      return fs.moveToTrash(path);
    } catch (FileNotFoundException ex) {
      return false;
    }
  }

  private void getZooLock(HostAndPort addr) throws KeeperException, InterruptedException {
    String path = ZooUtil.getRoot(instance) + Constants.ZGC_LOCK;

    LockWatcher lockWatcher = new LockWatcher() {
      @Override
      public void lostLock(LockLossReason reason) {
        Halt.halt("GC lock in zookeeper lost (reason = " + reason + "), exiting!");
      }

      @Override
      public void unableToMonitorLockNode(final Throwable e) {
        Halt.halt(-1, new Runnable() {

          @Override
          public void run() {
            log.fatal("No longer able to monitor lock node ", e);
          }
        });

      }
    };

    while (true) {
      lock = new ZooLock(path);
      if (lock.tryLock(lockWatcher, new ServerServices(addr.toString(), Service.GC_CLIENT).toString().getBytes())) {
        break;
      }
      UtilWaitThread.sleep(1000);
    }
  }

  private HostAndPort startStatsService() throws UnknownHostException {
    Processor<Iface> processor = new Processor<Iface>(RpcWrapper.service(this));
    AccumuloConfiguration conf = ServerConfiguration.getSystemConfiguration(instance);
    int port = conf.getPort(Property.GC_PORT);
    long maxMessageSize = conf.getMemoryInBytes(Property.GENERAL_MAX_MESSAGE_SIZE);
    HostAndPort result = HostAndPort.fromParts(opts.getAddress(), port);
    log.debug("Starting garbage collector listening on " + result);
    try {
      return TServerUtils.startTServer(result, processor, this.getClass().getSimpleName(), "GC Monitor Service", 2, 1000, maxMessageSize,
          SslConnectionParams.forServer(conf), 0).address;
    } catch (Exception ex) {
      log.fatal(ex, ex);
      throw new RuntimeException(ex);
    }
  }

  /**
   * Checks if the system is almost out of memory.
   *
   * @param runtime Java runtime
   * @return true if system is almost out of memory
   * @see #CANDIDATE_MEMORY_PERCENTAGE
   */
  static boolean almostOutOfMemory(Runtime runtime) {
    return runtime.totalMemory() - runtime.freeMemory() > CANDIDATE_MEMORY_PERCENTAGE * runtime.maxMemory();
  }

  final static String METADATA_TABLE_DIR = "/" + MetadataTable.ID;

  private static void putMarkerDeleteMutation(final String delete, final BatchWriter writer) throws MutationsRejectedException {
    Mutation m = new Mutation(MetadataSchema.DeletesSection.getRowPrefix() + delete);
    m.putDelete(EMPTY_TEXT, EMPTY_TEXT);
    writer.addMutation(m);
  }

  /**
   * Checks if the given string is a directory.
   *
   * @param delete possible directory
   * @return true if string is a directory
   */
  static boolean isDir(String delete) {
    if (delete == null) { return false; }
    int slashCount = 0;
    for (int i = 0; i < delete.length(); i++)
      if (delete.charAt(i) == '/')
        slashCount++;
    return slashCount == 1;
  }

  @Override
  public GCStatus getStatus(TInfo info, TCredentials credentials) {
    return status;
  }
}

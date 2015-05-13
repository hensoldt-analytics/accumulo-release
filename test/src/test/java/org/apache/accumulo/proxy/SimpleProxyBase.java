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
package org.apache.accumulo.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.conf.DefaultConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.file.FileOperations;
import org.apache.accumulo.core.file.FileSKVWriter;
import org.apache.accumulo.core.iterators.DevNull;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;
import org.apache.accumulo.core.iterators.user.SummingCombiner;
import org.apache.accumulo.core.iterators.user.VersioningIterator;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.util.ByteBufferUtil;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.accumulo.examples.simple.constraints.NumericValueConstraint;
import org.apache.accumulo.harness.SharedMiniClusterIT;
import org.apache.accumulo.minicluster.impl.MiniAccumuloClusterImpl;
import org.apache.accumulo.proxy.thrift.AccumuloProxy.Client;
import org.apache.accumulo.proxy.thrift.AccumuloSecurityException;
import org.apache.accumulo.proxy.thrift.ActiveCompaction;
import org.apache.accumulo.proxy.thrift.ActiveScan;
import org.apache.accumulo.proxy.thrift.BatchScanOptions;
import org.apache.accumulo.proxy.thrift.Column;
import org.apache.accumulo.proxy.thrift.ColumnUpdate;
import org.apache.accumulo.proxy.thrift.CompactionReason;
import org.apache.accumulo.proxy.thrift.CompactionStrategyConfig;
import org.apache.accumulo.proxy.thrift.CompactionType;
import org.apache.accumulo.proxy.thrift.Condition;
import org.apache.accumulo.proxy.thrift.ConditionalStatus;
import org.apache.accumulo.proxy.thrift.ConditionalUpdates;
import org.apache.accumulo.proxy.thrift.ConditionalWriterOptions;
import org.apache.accumulo.proxy.thrift.DiskUsage;
import org.apache.accumulo.proxy.thrift.IteratorScope;
import org.apache.accumulo.proxy.thrift.IteratorSetting;
import org.apache.accumulo.proxy.thrift.Key;
import org.apache.accumulo.proxy.thrift.KeyValue;
import org.apache.accumulo.proxy.thrift.MutationsRejectedException;
import org.apache.accumulo.proxy.thrift.PartialKey;
import org.apache.accumulo.proxy.thrift.Range;
import org.apache.accumulo.proxy.thrift.ScanColumn;
import org.apache.accumulo.proxy.thrift.ScanOptions;
import org.apache.accumulo.proxy.thrift.ScanResult;
import org.apache.accumulo.proxy.thrift.ScanState;
import org.apache.accumulo.proxy.thrift.ScanType;
import org.apache.accumulo.proxy.thrift.SystemPermission;
import org.apache.accumulo.proxy.thrift.TableExistsException;
import org.apache.accumulo.proxy.thrift.TableNotFoundException;
import org.apache.accumulo.proxy.thrift.TablePermission;
import org.apache.accumulo.proxy.thrift.TimeType;
import org.apache.accumulo.proxy.thrift.UnknownScanner;
import org.apache.accumulo.proxy.thrift.UnknownWriter;
import org.apache.accumulo.proxy.thrift.WriterOptions;
import org.apache.accumulo.server.util.PortUtils;
import org.apache.accumulo.test.functional.SlowIterator;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.server.TServer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterators;
import com.google.common.net.HostAndPort;

/**
 * Call every method on the proxy and try to verify that it works.
 */
public abstract class SimpleProxyBase extends SharedMiniClusterIT {
  private static final Logger log = LoggerFactory.getLogger(SimpleProxyBase.class);

  @Override
  protected int defaultTimeoutSeconds() {
    return 60;
  }

  private static final long ZOOKEEPER_PROPAGATION_TIME = 10 * 1000;
  private static TServer proxyServer;
  private static int proxyPort;
  private static org.apache.accumulo.proxy.thrift.AccumuloProxy.Client client;

  private static Map<String,String> properties = new HashMap<>();
  private static ByteBuffer creds = null;

  // Implementations can set this
  static TProtocolFactory factory = null;

  private static void waitForAccumulo(Connector c) throws Exception {
    Iterators.size(c.createScanner(MetadataTable.NAME, Authorizations.EMPTY).iterator());
  }

  /**
   * Does the actual test setup, invoked by the concrete test class
   */
  public static void setUpProxy() throws Exception {
    assertNotNull("Implementations must initialize the TProtocolFactory", factory);

    Connector c = SharedMiniClusterIT.getConnector();
    Instance inst = c.getInstance();
    waitForAccumulo(c);

    Properties props = new Properties();
    props.put("instance", inst.getInstanceName());
    props.put("zookeepers", inst.getZooKeepers());
    props.put("tokenClass", PasswordToken.class.getName());
    // Avoid issues with locally installed client configuration files with custom properties
    File emptyFile = Files.createTempFile(null, null).toFile();
    emptyFile.deleteOnExit();
    props.put("clientConfigurationFile", emptyFile.toString());

    properties.put("password", SharedMiniClusterIT.getRootPassword());
    properties.put("clientConfigurationFile", emptyFile.toString());

    proxyPort = PortUtils.getRandomFreePort();
    proxyServer = Proxy.createProxyServer(HostAndPort.fromParts("localhost", proxyPort), factory, props).server;
    while (!proxyServer.isServing())
      UtilWaitThread.sleep(100);
    client = new TestProxyClient("localhost", proxyPort, factory).proxy();
    creds = client.login("root", properties);
  }

  @AfterClass
  public static void tearDownProxy() throws Exception {
    if (null != proxyServer) {
      proxyServer.stop();
    }
  }

  final IteratorSetting setting = new IteratorSetting(100, "slow", SlowIterator.class.getName(), Collections.singletonMap("sleepTime", "200"));
  String table;
  ByteBuffer badLogin;

  @Before
  public void setup() throws Exception {
    // Create 'user'
    client.createLocalUser(creds, "user", s2bb(SharedMiniClusterIT.getRootPassword()));
    // Log in as 'user'
    badLogin = client.login("user", properties);
    // Drop 'user', invalidating the credentials
    client.dropLocalUser(creds, "user");

    // Create a general table to be used
    table = getUniqueNames(1)[0];
    client.createTable(creds, table, true, TimeType.MILLIS);
  }

  @After
  public void teardown() throws Exception {
    if (null != table) {
      try {
        client.deleteTable(creds, table);
      } catch (Exception e) {
        log.warn("Failed to delete test table", e);
      }
    }
  }

  /*
   * Set a lower timeout for tests that should fail fast
   */

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void addConstraintLoginFailure() throws Exception {
    client.addConstraint(badLogin, table, NumericValueConstraint.class.getName());
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void addSplitsLoginFailure() throws Exception {
    client.addSplits(badLogin, table, Collections.singleton(s2bb("1")));
  }

  @Test(expected = TApplicationException.class, timeout = 5000)
  public void clearLocatorCacheLoginFailure() throws Exception {
    client.clearLocatorCache(badLogin, table);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void compactTableLoginFailure() throws Exception {
    client.compactTable(badLogin, table, null, null, null, true, false, null);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void cancelCompactionLoginFailure() throws Exception {
    client.cancelCompaction(badLogin, table);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void createTableLoginFailure() throws Exception {
    client.createTable(badLogin, table, false, TimeType.MILLIS);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void deleteTableLoginFailure() throws Exception {
    client.deleteTable(badLogin, table);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void deleteRowsLoginFailure() throws Exception {
    client.deleteRows(badLogin, table, null, null);
  }

  @Test(expected = TApplicationException.class, timeout = 5000)
  public void tableExistsLoginFailure() throws Exception {
    client.tableExists(badLogin, table);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void flustTableLoginFailure() throws Exception {
    client.flushTable(badLogin, table, null, null, false);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void getLocalityGroupsLoginFailure() throws Exception {
    client.getLocalityGroups(badLogin, table);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void getMaxRowLoginFailure() throws Exception {
    client.getMaxRow(badLogin, table, Collections.<ByteBuffer> emptySet(), null, false, null, false);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void getTablePropertiesLoginFailure() throws Exception {
    client.getTableProperties(badLogin, table);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void listSplitsLoginFailure() throws Exception {
    client.listSplits(badLogin, table, 10000);
  }

  @Test(expected = TApplicationException.class, timeout = 5000)
  public void listTablesLoginFailure() throws Exception {
    client.listTables(badLogin);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void listConstraintsLoginFailure() throws Exception {
    client.listConstraints(badLogin, table);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void mergeTabletsLoginFailure() throws Exception {
    client.mergeTablets(badLogin, table, null, null);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void offlineTableLoginFailure() throws Exception {
    client.offlineTable(badLogin, table, false);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void onlineTableLoginFailure() throws Exception {
    client.onlineTable(badLogin, table, false);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void removeConstraintLoginFailure() throws Exception {
    client.removeConstraint(badLogin, table, 0);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void removeTablePropertyLoginFailure() throws Exception {
    client.removeTableProperty(badLogin, table, Property.TABLE_FILE_MAX.getKey());
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void renameTableLoginFailure() throws Exception {
    client.renameTable(badLogin, table, "someTableName");
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void setLocalityGroupsLoginFailure() throws Exception {
    Map<String,Set<String>> groups = new HashMap<String,Set<String>>();
    groups.put("group1", Collections.singleton("cf1"));
    groups.put("group2", Collections.singleton("cf2"));
    client.setLocalityGroups(badLogin, table, groups);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void setTablePropertyLoginFailure() throws Exception {
    client.setTableProperty(badLogin, table, Property.TABLE_FILE_MAX.getKey(), "0");
  }

  @Test(expected = TException.class, timeout = 5000)
  public void tableIdMapLoginFailure() throws Exception {
    client.tableIdMap(badLogin);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void getSiteConfigurationLoginFailure() throws Exception {
    client.getSiteConfiguration(badLogin);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void getSystemConfigurationLoginFailure() throws Exception {
    client.getSystemConfiguration(badLogin);
  }

  @Test(expected = TException.class, timeout = 5000)
  public void getTabletServersLoginFailure() throws Exception {
    client.getTabletServers(badLogin);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void getActiveScansLoginFailure() throws Exception {
    client.getActiveScans(badLogin, "fake");
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void getActiveCompactionsLoginFailure() throws Exception {
    client.getActiveCompactions(badLogin, "fakse");
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void removePropertyLoginFailure() throws Exception {
    client.removeProperty(badLogin, "table.split.threshold");
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void setPropertyLoginFailure() throws Exception {
    client.setProperty(badLogin, "table.split.threshold", "500M");
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void testClassLoadLoginFailure() throws Exception {
    client.testClassLoad(badLogin, DevNull.class.getName(), SortedKeyValueIterator.class.getName());
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void authenticateUserLoginFailure() throws Exception {
    client.authenticateUser(badLogin, "root", s2pp(SharedMiniClusterIT.getRootPassword()));
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void changeUserAuthorizationsLoginFailure() throws Exception {
    HashSet<ByteBuffer> auths = new HashSet<ByteBuffer>(Arrays.asList(s2bb("A"), s2bb("B")));
    client.changeUserAuthorizations(badLogin, "stooge", auths);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void changePasswordLoginFailure() throws Exception {
    client.changeLocalUserPassword(badLogin, "stooge", s2bb(""));
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void createUserLoginFailure() throws Exception {
    client.createLocalUser(badLogin, "stooge", s2bb("password"));
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void dropUserLoginFailure() throws Exception {
    client.dropLocalUser(badLogin, "stooge");
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void getUserAuthorizationsLoginFailure() throws Exception {
    client.getUserAuthorizations(badLogin, "stooge");
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void grantSystemPermissionLoginFailure() throws Exception {
    client.grantSystemPermission(badLogin, "stooge", SystemPermission.CREATE_TABLE);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void grantTablePermissionLoginFailure() throws Exception {
    client.grantTablePermission(badLogin, "root", table, TablePermission.WRITE);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void hasSystemPermissionLoginFailure() throws Exception {
    client.hasSystemPermission(badLogin, "stooge", SystemPermission.CREATE_TABLE);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void hasTablePermission() throws Exception {
    client.hasTablePermission(badLogin, "root", table, TablePermission.WRITE);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void listLocalUsersLoginFailure() throws Exception {
    client.listLocalUsers(badLogin);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void revokeSystemPermissionLoginFailure() throws Exception {
    client.revokeSystemPermission(badLogin, "stooge", SystemPermission.CREATE_TABLE);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void revokeTablePermissionLoginFailure() throws Exception {
    client.revokeTablePermission(badLogin, "root", table, TablePermission.ALTER_TABLE);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void createScannerLoginFailure() throws Exception {
    client.createScanner(badLogin, table, new ScanOptions());
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void createBatchScannerLoginFailure() throws Exception {
    client.createBatchScanner(badLogin, table, new BatchScanOptions());
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void updateAndFlushLoginFailure() throws Exception {
    client.updateAndFlush(badLogin, table, new HashMap<ByteBuffer,List<ColumnUpdate>>());
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void createWriterLoginFailure() throws Exception {
    client.createWriter(badLogin, table, new WriterOptions());
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void attachIteratorLoginFailure() throws Exception {
    client.attachIterator(badLogin, "slow", setting, EnumSet.allOf(IteratorScope.class));
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void checkIteratorLoginFailure() throws Exception {
    client.checkIteratorConflicts(badLogin, table, setting, EnumSet.allOf(IteratorScope.class));
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void cloneTableLoginFailure() throws Exception {
    client.cloneTable(badLogin, table, table + "_clone", false, null, null);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void exportTableLoginFailure() throws Exception {
    client.exportTable(badLogin, table, "/tmp");
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void importTableLoginFailure() throws Exception {
    client.importTable(badLogin, "testify", "/tmp");
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void getIteratorSettingLoginFailure() throws Exception {
    client.getIteratorSetting(badLogin, table, "foo", IteratorScope.SCAN);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void listIteratorsLoginFailure() throws Exception {
    client.listIterators(badLogin, table);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void removeIteratorLoginFailure() throws Exception {
    client.removeIterator(badLogin, table, "name", EnumSet.allOf(IteratorScope.class));
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void splitRangeByTabletsLoginFailure() throws Exception {
    client.splitRangeByTablets(badLogin, table, client.getRowRange(ByteBuffer.wrap("row".getBytes())), 10);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void importDirectoryLoginFailure() throws Exception {
    MiniAccumuloClusterImpl cluster = SharedMiniClusterIT.getCluster();
    Path base = cluster.getTemporaryPath();
    Path importDir = new Path(base, "importDir");
    Path failuresDir = new Path(base, "failuresDir");
    assertTrue(cluster.getFileSystem().mkdirs(importDir));
    assertTrue(cluster.getFileSystem().mkdirs(failuresDir));
    client.importDirectory(badLogin, table, importDir.toString(), failuresDir.toString(), true);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void pingTabletServerLoginFailure() throws Exception {
    client.pingTabletServer(badLogin, "fake");
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void loginFailure() throws Exception {
    client.login("badUser", properties);
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void testTableClassLoadLoginFailure() throws Exception {
    client.testTableClassLoad(badLogin, table, VersioningIterator.class.getName(), SortedKeyValueIterator.class.getName());
  }

  @Test(expected = AccumuloSecurityException.class, timeout = 5000)
  public void createConditionalWriterLoginFailure() throws Exception {
    client.createConditionalWriter(badLogin, table, new ConditionalWriterOptions());
  }

  @Test
  public void tableNotFound() throws Exception {
    final String doesNotExist = "doesNotExists";
    try {
      client.addConstraint(creds, doesNotExist, NumericValueConstraint.class.getName());
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.addSplits(creds, doesNotExist, Collections.<ByteBuffer> emptySet());
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    final IteratorSetting setting = new IteratorSetting(100, "slow", SlowIterator.class.getName(), Collections.singletonMap("sleepTime", "200"));
    try {
      client.attachIterator(creds, doesNotExist, setting, EnumSet.allOf(IteratorScope.class));
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.cancelCompaction(creds, doesNotExist);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.checkIteratorConflicts(creds, doesNotExist, setting, EnumSet.allOf(IteratorScope.class));
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.clearLocatorCache(creds, doesNotExist);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      final String TABLE_TEST = getUniqueNames(1)[0];
      client.cloneTable(creds, doesNotExist, TABLE_TEST, false, null, null);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.compactTable(creds, doesNotExist, null, null, null, true, false, null);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.createBatchScanner(creds, doesNotExist, new BatchScanOptions());
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.createScanner(creds, doesNotExist, new ScanOptions());
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.createWriter(creds, doesNotExist, new WriterOptions());
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.deleteRows(creds, doesNotExist, null, null);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.deleteTable(creds, doesNotExist);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.exportTable(creds, doesNotExist, "/tmp");
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.flushTable(creds, doesNotExist, null, null, false);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.getIteratorSetting(creds, doesNotExist, "foo", IteratorScope.SCAN);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.getLocalityGroups(creds, doesNotExist);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.getMaxRow(creds, doesNotExist, Collections.<ByteBuffer> emptySet(), null, false, null, false);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.getTableProperties(creds, doesNotExist);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.grantTablePermission(creds, "root", doesNotExist, TablePermission.WRITE);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.hasTablePermission(creds, "root", doesNotExist, TablePermission.WRITE);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      MiniAccumuloClusterImpl cluster = SharedMiniClusterIT.getCluster();
      Path base = cluster.getTemporaryPath();
      Path importDir = new Path(base, "importDir");
      Path failuresDir = new Path(base, "failuresDir");
      assertTrue(cluster.getFileSystem().mkdirs(importDir));
      assertTrue(cluster.getFileSystem().mkdirs(failuresDir));
      client.importDirectory(creds, doesNotExist, importDir.toString(), failuresDir.toString(), true);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.listConstraints(creds, doesNotExist);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.listSplits(creds, doesNotExist, 10000);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.mergeTablets(creds, doesNotExist, null, null);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.offlineTable(creds, doesNotExist, false);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.onlineTable(creds, doesNotExist, false);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.removeConstraint(creds, doesNotExist, 0);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.removeIterator(creds, doesNotExist, "name", EnumSet.allOf(IteratorScope.class));
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.removeTableProperty(creds, doesNotExist, Property.TABLE_FILE_MAX.getKey());
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.renameTable(creds, doesNotExist, "someTableName");
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.revokeTablePermission(creds, "root", doesNotExist, TablePermission.ALTER_TABLE);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.setTableProperty(creds, doesNotExist, Property.TABLE_FILE_MAX.getKey(), "0");
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.splitRangeByTablets(creds, doesNotExist, client.getRowRange(ByteBuffer.wrap("row".getBytes())), 10);
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.updateAndFlush(creds, doesNotExist, new HashMap<ByteBuffer,List<ColumnUpdate>>());
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.getDiskUsage(creds, Collections.singleton(doesNotExist));
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.testTableClassLoad(creds, doesNotExist, VersioningIterator.class.getName(), SortedKeyValueIterator.class.getName());
      fail("exception not thrown");
    } catch (TableNotFoundException ex) {}
    try {
      client.createConditionalWriter(creds, doesNotExist, new ConditionalWriterOptions());
    } catch (TableNotFoundException ex) {}
  }

  @Test
  public void testExists() throws Exception {
    client.createTable(creds, "ett1", false, TimeType.MILLIS);
    client.createTable(creds, "ett2", false, TimeType.MILLIS);
    try {
      client.createTable(creds, "ett1", false, TimeType.MILLIS);
      fail("exception not thrown");
    } catch (TableExistsException tee) {}
    try {
      client.renameTable(creds, "ett1", "ett2");
      fail("exception not thrown");
    } catch (TableExistsException tee) {}
    try {
      client.cloneTable(creds, "ett1", "ett2", false, new HashMap<String,String>(), new HashSet<String>());
      fail("exception not thrown");
    } catch (TableExistsException tee) {}
  }

  @Test
  public void testUnknownScanner() throws Exception {
    String scanner = client.createScanner(creds, table, null);
    assertFalse(client.hasNext(scanner));
    client.closeScanner(scanner);

    try {
      client.hasNext(scanner);
      fail("exception not thrown");
    } catch (UnknownScanner us) {}

    try {
      client.closeScanner(scanner);
      fail("exception not thrown");
    } catch (UnknownScanner us) {}

    try {
      client.nextEntry("99999999");
      fail("exception not thrown");
    } catch (UnknownScanner us) {}
    try {
      client.nextK("99999999", 6);
      fail("exception not thrown");
    } catch (UnknownScanner us) {}
    try {
      client.hasNext("99999999");
      fail("exception not thrown");
    } catch (UnknownScanner us) {}
    try {
      client.hasNext(UUID.randomUUID().toString());
      fail("exception not thrown");
    } catch (UnknownScanner us) {}
  }

  @Test
  public void testUnknownWriter() throws Exception {
    String writer = client.createWriter(creds, table, null);
    client.update(writer, mutation("row0", "cf", "cq", "value"));
    client.flush(writer);
    client.update(writer, mutation("row2", "cf", "cq", "value2"));
    client.closeWriter(writer);

    // this is a oneway call, so it does not throw exceptions
    client.update(writer, mutation("row2", "cf", "cq", "value2"));

    try {
      client.flush(writer);
      fail("exception not thrown");
    } catch (UnknownWriter uw) {}
    try {
      client.flush("99999");
      fail("exception not thrown");
    } catch (UnknownWriter uw) {}
    try {
      client.flush(UUID.randomUUID().toString());
      fail("exception not thrown");
    } catch (UnknownWriter uw) {}
    try {
      client.closeWriter("99999");
      fail("exception not thrown");
    } catch (UnknownWriter uw) {}
  }

  @Test
  public void testDelete() throws Exception {
    client.updateAndFlush(creds, table, mutation("row0", "cf", "cq", "value"));

    assertScan(new String[][] {{"row0", "cf", "cq", "value"}}, table);

    ColumnUpdate upd = new ColumnUpdate(s2bb("cf"), s2bb("cq"));
    upd.setDeleteCell(false);
    Map<ByteBuffer,List<ColumnUpdate>> notDelete = Collections.singletonMap(s2bb("row0"), Collections.singletonList(upd));
    client.updateAndFlush(creds, table, notDelete);
    String scanner = client.createScanner(creds, table, null);
    ScanResult entries = client.nextK(scanner, 10);
    client.closeScanner(scanner);
    assertFalse(entries.more);
    assertEquals("Results: " + entries.results, 1, entries.results.size());

    upd = new ColumnUpdate(s2bb("cf"), s2bb("cq"));
    upd.setDeleteCell(true);
    Map<ByteBuffer,List<ColumnUpdate>> delete = Collections.singletonMap(s2bb("row0"), Collections.singletonList(upd));

    client.updateAndFlush(creds, table, delete);

    assertScan(new String[][] {}, table);
  }

  @Test
  public void testSystemProperties() throws Exception {
    Map<String,String> cfg = client.getSiteConfiguration(creds);

    // set a property in zookeeper
    client.setProperty(creds, "table.split.threshold", "500M");

    // check that we can read it
    for (int i = 0; i < 5; i++) {
      cfg = client.getSystemConfiguration(creds);
      if ("500M".equals(cfg.get("table.split.threshold")))
        break;
      UtilWaitThread.sleep(200);
    }
    assertEquals("500M", cfg.get("table.split.threshold"));

    // unset the setting, check that it's not what it was
    client.removeProperty(creds, "table.split.threshold");
    for (int i = 0; i < 5; i++) {
      cfg = client.getSystemConfiguration(creds);
      if (!"500M".equals(cfg.get("table.split.threshold")))
        break;
      UtilWaitThread.sleep(200);
    }
    assertNotEquals("500M", cfg.get("table.split.threshold"));
  }

  @Test
  public void pingTabletServers() throws Exception {
    int tservers = 0;
    for (String tserver : client.getTabletServers(creds)) {
      client.pingTabletServer(creds, tserver);
      tservers++;
    }
    assertTrue(tservers > 0);
  }

  @Test
  public void testSiteConfiguration() throws Exception {
    // get something we know is in the site config
    MiniAccumuloClusterImpl cluster = SharedMiniClusterIT.getCluster();
    Map<String,String> cfg = client.getSiteConfiguration(creds);
    assertTrue(cfg.get("instance.dfs.dir").startsWith(cluster.getConfig().getAccumuloDir().getAbsolutePath()));
  }

  @Test
  public void testClassLoad() throws Exception {
    // try to load some classes via the proxy
    assertTrue(client.testClassLoad(creds, DevNull.class.getName(), SortedKeyValueIterator.class.getName()));
    assertFalse(client.testClassLoad(creds, "foo.bar", SortedKeyValueIterator.class.getName()));
  }

  @Test
  public void attachIteratorsWithScans() throws Exception {
    if (client.tableExists(creds, "slow")) {
      client.deleteTable(creds, "slow");
    }

    // create a table that's very slow, so we can look for scans
    client.createTable(creds, "slow", true, TimeType.MILLIS);
    IteratorSetting setting = new IteratorSetting(100, "slow", SlowIterator.class.getName(), Collections.singletonMap("sleepTime", "250"));
    client.attachIterator(creds, "slow", setting, EnumSet.allOf(IteratorScope.class));

    // Should take 10 seconds to read every record
    for (int i = 0; i < 40; i++) {
      client.updateAndFlush(creds, "slow", mutation("row" + i, "cf", "cq", "value"));
    }

    // scan
    Thread t = new Thread() {
      @Override
      public void run() {
        String scanner;
        try {
          Client client2 = new TestProxyClient("localhost", proxyPort, factory).proxy();
          scanner = client2.createScanner(creds, "slow", null);
          client2.nextK(scanner, 10);
          client2.closeScanner(scanner);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    };
    t.start();

    // look for the scan many times
    List<ActiveScan> scans = new ArrayList<ActiveScan>();
    for (int i = 0; i < 100 && scans.isEmpty(); i++) {
      for (String tserver : client.getTabletServers(creds)) {
        List<ActiveScan> scansForServer = client.getActiveScans(creds, tserver);
        for (ActiveScan scan : scansForServer) {
          if ("root".equals(scan.getUser())) {
            scans.add(scan);
          }
        }

        if (!scans.isEmpty())
          break;
        UtilWaitThread.sleep(100);
      }
    }
    t.join();

    assertFalse(scans.isEmpty());
    boolean found = false;
    Map<String,String> map = null;
    for (int i = 0; i < scans.size() && !found; i++) {
      ActiveScan scan = scans.get(i);
      if ("root".equals(scan.getUser())) {
        assertTrue(ScanState.RUNNING.equals(scan.getState()) || ScanState.QUEUED.equals(scan.getState()));
        assertEquals(ScanType.SINGLE, scan.getType());
        assertEquals("slow", scan.getTable());

        map = client.tableIdMap(creds);
        assertEquals(map.get("slow"), scan.getExtent().tableId);
        assertTrue(scan.getExtent().endRow == null);
        assertTrue(scan.getExtent().prevEndRow == null);
        found = true;
      }
    }

    assertTrue("Could not find a scan against the 'slow' table", found);
  }

  @Test
  public void attachIteratorWithCompactions() throws Exception {
    if (client.tableExists(creds, "slow")) {
      client.deleteTable(creds, "slow");
    }

    // create a table that's very slow, so we can look for compactions
    client.createTable(creds, "slow", true, TimeType.MILLIS);
    IteratorSetting setting = new IteratorSetting(100, "slow", SlowIterator.class.getName(), Collections.singletonMap("sleepTime", "250"));
    client.attachIterator(creds, "slow", setting, EnumSet.allOf(IteratorScope.class));

    // Should take 10 seconds to read every record
    for (int i = 0; i < 40; i++) {
      client.updateAndFlush(creds, "slow", mutation("row" + i, "cf", "cq", "value"));
    }

    Map<String,String> map = client.tableIdMap(creds);

    // start a compaction
    Thread t = new Thread() {
      @Override
      public void run() {
        try {
          Client client2 = new TestProxyClient("localhost", proxyPort, factory).proxy();
          client2.compactTable(creds, "slow", null, null, null, true, true, null);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    };
    t.start();

    final String desiredTableId = map.get("slow");

    // Make sure we can find the slow table
    assertNotNull(desiredTableId);

    // try to catch it in the act
    List<ActiveCompaction> compactions = new ArrayList<ActiveCompaction>();
    for (int i = 0; i < 100 && compactions.isEmpty(); i++) {
      // Iterate over the tservers
      for (String tserver : client.getTabletServers(creds)) {
        // And get the compactions on each
        List<ActiveCompaction> compactionsOnServer = client.getActiveCompactions(creds, tserver);
        for (ActiveCompaction compact : compactionsOnServer) {
          // There might be other compactions occurring (e.g. on METADATA) in which
          // case we want to prune out those that aren't for our slow table
          if (desiredTableId.equals(compact.getExtent().tableId)) {
            compactions.add(compact);
          }
        }

        // If we found a compaction for the table we wanted, so we can stop looking
        if (!compactions.isEmpty())
          break;
      }
      UtilWaitThread.sleep(10);
    }
    t.join();

    // verify the compaction information
    assertFalse(compactions.isEmpty());
    for (ActiveCompaction c : compactions) {
      if (desiredTableId.equals(c.getExtent().tableId)) {
        assertTrue(c.inputFiles.isEmpty());
        assertEquals(CompactionType.MINOR, c.getType());
        assertEquals(CompactionReason.USER, c.getReason());
        assertEquals("", c.localityGroup);
        assertTrue(c.outputFile.contains("default_tablet"));

        return;
      }
    }

    fail("Expection to find running compaction for table 'slow' but did not find one");
  }

  @Test
  public void userAuthentication() throws Exception {
    // check password
    assertTrue(client.authenticateUser(creds, "root", s2pp(SharedMiniClusterIT.getRootPassword())));
    assertFalse(client.authenticateUser(creds, "root", s2pp("")));
  }

  @Test
  public void userManagement() throws Exception {
    String user = getUniqueNames(1)[0];
    // create a user
    client.createLocalUser(creds, user, s2bb("password"));
    // change auths
    Set<String> users = client.listLocalUsers(creds);
    Set<String> expectedUsers = new HashSet<String>(Arrays.asList("root", user));
    assertTrue("Did not find all expected users: " + expectedUsers, users.containsAll(expectedUsers));
    HashSet<ByteBuffer> auths = new HashSet<ByteBuffer>(Arrays.asList(s2bb("A"), s2bb("B")));
    client.changeUserAuthorizations(creds, user, auths);
    List<ByteBuffer> update = client.getUserAuthorizations(creds, user);
    assertEquals(auths, new HashSet<ByteBuffer>(update));

    // change password
    client.changeLocalUserPassword(creds, user, s2bb(""));
    assertTrue(client.authenticateUser(creds, user, s2pp("")));

    // check login with new password
    client.login(user, s2pp(""));
  }

  @Test
  public void userPermissions() throws Exception {
    String userName = getUniqueNames(1)[0];
    // create a user
    client.createLocalUser(creds, userName, s2bb("password"));

    ByteBuffer user = client.login(userName, s2pp("password"));

    // check permission failure
    try {
      client.createTable(user, "fail", true, TimeType.MILLIS);
      fail("should not create the table");
    } catch (AccumuloSecurityException ex) {
      assertFalse(client.listTables(creds).contains("fail"));
    }
    // grant permissions and test
    assertFalse(client.hasSystemPermission(creds, userName, SystemPermission.CREATE_TABLE));
    client.grantSystemPermission(creds, userName, SystemPermission.CREATE_TABLE);
    assertTrue(client.hasSystemPermission(creds, userName, SystemPermission.CREATE_TABLE));
    client.createTable(user, "success", true, TimeType.MILLIS);
    client.listTables(creds).contains("succcess");

    // revoke permissions
    client.revokeSystemPermission(creds, userName, SystemPermission.CREATE_TABLE);
    assertFalse(client.hasSystemPermission(creds, userName, SystemPermission.CREATE_TABLE));
    try {
      client.createTable(user, "fail", true, TimeType.MILLIS);
      fail("should not create the table");
    } catch (AccumuloSecurityException ex) {
      assertFalse(client.listTables(creds).contains("fail"));
    }
    // denied!
    try {
      String scanner = client.createScanner(user, table, null);
      client.nextK(scanner, 100);
      fail("stooge should not read table test");
    } catch (AccumuloSecurityException ex) {}
    // grant
    assertFalse(client.hasTablePermission(creds, userName, table, TablePermission.READ));
    client.grantTablePermission(creds, userName, table, TablePermission.READ);
    assertTrue(client.hasTablePermission(creds, userName, table, TablePermission.READ));
    String scanner = client.createScanner(user, table, null);
    client.nextK(scanner, 10);
    client.closeScanner(scanner);
    // revoke
    client.revokeTablePermission(creds, userName, table, TablePermission.READ);
    assertFalse(client.hasTablePermission(creds, userName, table, TablePermission.READ));
    try {
      scanner = client.createScanner(user, table, null);
      client.nextK(scanner, 100);
      fail("stooge should not read table test");
    } catch (AccumuloSecurityException ex) {}

    // delete user
    client.dropLocalUser(creds, userName);
    Set<String> users = client.listLocalUsers(creds);
    assertFalse("Should not see user after they are deleted", users.contains(userName));
  }

  @Test
  public void testBatchWriter() throws Exception {
    client.addConstraint(creds, table, NumericValueConstraint.class.getName());
    // zookeeper propagation time
    UtilWaitThread.sleep(ZOOKEEPER_PROPAGATION_TIME);

    WriterOptions writerOptions = new WriterOptions();
    writerOptions.setLatencyMs(10000);
    writerOptions.setMaxMemory(2);
    writerOptions.setThreads(1);
    writerOptions.setTimeoutMs(100000);

    String batchWriter = client.createWriter(creds, table, writerOptions);
    client.update(batchWriter, mutation("row1", "cf", "cq", "x"));
    client.update(batchWriter, mutation("row1", "cf", "cq", "x"));
    try {
      client.flush(batchWriter);
      fail("constraint did not fire");
    } catch (MutationsRejectedException ex) {}
    try {
      client.closeWriter(batchWriter);
      fail("constraint did not fire");
    } catch (MutationsRejectedException e) {}

    client.removeConstraint(creds, table, 2);

    assertScan(new String[][] {}, table);

    UtilWaitThread.sleep(2000);

    writerOptions = new WriterOptions();
    writerOptions.setLatencyMs(10000);
    writerOptions.setMaxMemory(3000);
    writerOptions.setThreads(1);
    writerOptions.setTimeoutMs(100000);

    batchWriter = client.createWriter(creds, table, writerOptions);

    client.update(batchWriter, mutation("row1", "cf", "cq", "x"));
    client.flush(batchWriter);
    client.closeWriter(batchWriter);

    assertScan(new String[][] {{"row1", "cf", "cq", "x"}}, table);

    client.deleteTable(creds, table);
  }

  @Test
  public void testTableConstraints() throws Exception {

    // constraints
    client.addConstraint(creds, table, NumericValueConstraint.class.getName());

    // zookeeper propagation time
    UtilWaitThread.sleep(ZOOKEEPER_PROPAGATION_TIME);

    assertEquals(2, client.listConstraints(creds, table).size());

    // Write data that satisfies the constraint
    client.updateAndFlush(creds, table, mutation("row1", "cf", "cq", "123"));

    // Expect failure on data that fails the constraint
    while (true) {
      try {
        client.updateAndFlush(creds, table, mutation("row1", "cf", "cq", "x"));
        UtilWaitThread.sleep(1000);
      } catch (MutationsRejectedException ex) {
        break;
      }
    }

    client.removeConstraint(creds, table, 2);

    UtilWaitThread.sleep(ZOOKEEPER_PROPAGATION_TIME);

    assertEquals(1, client.listConstraints(creds, table).size());

    // Make sure we can write the data after we removed the constraint
    while (true) {
      try {
        client.updateAndFlush(creds, table, mutation("row1", "cf", "cq", "x"));
        break;
      } catch (MutationsRejectedException ex) {
        UtilWaitThread.sleep(1000);
      }
    }

    assertScan(new String[][] {{"row1", "cf", "cq", "x"}}, table);
  }

  @Test
  public void tableMergesAndSplits() throws Exception {
    // add some splits
    client.addSplits(creds, table, new HashSet<ByteBuffer>(Arrays.asList(s2bb("a"), s2bb("m"), s2bb("z"))));
    List<ByteBuffer> splits = client.listSplits(creds, table, 1);
    assertEquals(Arrays.asList(s2bb("m")), splits);

    // Merge some of the splits away
    client.mergeTablets(creds, table, null, s2bb("m"));
    splits = client.listSplits(creds, table, 10);
    assertEquals(Arrays.asList(s2bb("m"), s2bb("z")), splits);

    // Merge the entire table
    client.mergeTablets(creds, table, null, null);
    splits = client.listSplits(creds, table, 10);
    List<ByteBuffer> empty = Collections.emptyList();

    // No splits after merge on whole table
    assertEquals(empty, splits);
  }

  @Test
  public void iteratorFunctionality() throws Exception {
    // iterators
    HashMap<String,String> options = new HashMap<String,String>();
    options.put("type", "STRING");
    options.put("columns", "cf");
    IteratorSetting setting = new IteratorSetting(10, table, SummingCombiner.class.getName(), options);
    client.attachIterator(creds, table, setting, EnumSet.allOf(IteratorScope.class));
    for (int i = 0; i < 10; i++) {
      client.updateAndFlush(creds, table, mutation("row1", "cf", "cq", "1"));
    }
    // 10 updates of "1" in the value w/ SummingCombiner should return value of "10"
    assertScan(new String[][] {{"row1", "cf", "cq", "10"}}, table);

    try {
      client.checkIteratorConflicts(creds, table, setting, EnumSet.allOf(IteratorScope.class));
      fail("checkIteratorConflicts did not throw an exception");
    } catch (Exception ex) {
      // Expected
    }
    client.deleteRows(creds, table, null, null);
    client.removeIterator(creds, table, "test", EnumSet.allOf(IteratorScope.class));
    String expected[][] = new String[10][];
    for (int i = 0; i < 10; i++) {
      client.updateAndFlush(creds, table, mutation("row" + i, "cf", "cq", "" + i));
      expected[i] = new String[] {"row" + i, "cf", "cq", "" + i};
      client.flushTable(creds, table, null, null, true);
    }
    assertScan(expected, table);
  }

  @Test
  public void cloneTable() throws Exception {
    String TABLE_TEST2 = getUniqueNames(2)[1];

    String expected[][] = new String[10][];
    for (int i = 0; i < 10; i++) {
      client.updateAndFlush(creds, table, mutation("row" + i, "cf", "cq", "" + i));
      expected[i] = new String[] {"row" + i, "cf", "cq", "" + i};
      client.flushTable(creds, table, null, null, true);
    }
    assertScan(expected, table);

    // clone
    client.cloneTable(creds, table, TABLE_TEST2, true, null, null);
    assertScan(expected, TABLE_TEST2);
    client.deleteTable(creds, TABLE_TEST2);
  }

  @Test
  public void clearLocatorCache() throws Exception {
    // don't know how to test this, call it just for fun
    client.clearLocatorCache(creds, table);
  }

  @Test
  public void compactTable() throws Exception {
    String expected[][] = new String[10][];
    for (int i = 0; i < 10; i++) {
      client.updateAndFlush(creds, table, mutation("row" + i, "cf", "cq", "" + i));
      expected[i] = new String[] {"row" + i, "cf", "cq", "" + i};
      client.flushTable(creds, table, null, null, true);
    }
    assertScan(expected, table);

    // compact
    client.compactTable(creds, table, null, null, null, true, true, null);
    assertEquals(1, countFiles(table));
    assertScan(expected, table);
  }

  @Test
  public void diskUsage() throws Exception {
    String TABLE_TEST2 = getUniqueNames(2)[1];

    // Write some data
    String expected[][] = new String[10][];
    for (int i = 0; i < 10; i++) {
      client.updateAndFlush(creds, table, mutation("row" + i, "cf", "cq", "" + i));
      expected[i] = new String[] {"row" + i, "cf", "cq", "" + i};
      client.flushTable(creds, table, null, null, true);
    }
    assertScan(expected, table);

    // compact
    client.compactTable(creds, table, null, null, null, true, true, null);
    assertEquals(1, countFiles(table));
    assertScan(expected, table);

    // Clone the table
    client.cloneTable(creds, table, TABLE_TEST2, true, null, null);
    Set<String> tablesToScan = new HashSet<String>();
    tablesToScan.add(table);
    tablesToScan.add(TABLE_TEST2);
    tablesToScan.add("foo");

    client.createTable(creds, "foo", true, TimeType.MILLIS);

    // get disk usage
    List<DiskUsage> diskUsage = (client.getDiskUsage(creds, tablesToScan));
    assertEquals(2, diskUsage.size());
    // The original table and the clone are lumped together (they share the same files)
    assertEquals(2, diskUsage.get(0).getTables().size());
    // The empty table we created
    assertEquals(1, diskUsage.get(1).getTables().size());

    // Compact the clone so it writes its own files instead of referring to the original
    client.compactTable(creds, TABLE_TEST2, null, null, null, true, true, null);

    diskUsage = (client.getDiskUsage(creds, tablesToScan));
    assertEquals(3, diskUsage.size());
    // The original
    assertEquals(1, diskUsage.get(0).getTables().size());
    // The clone w/ its own files now
    assertEquals(1, diskUsage.get(1).getTables().size());
    // The empty table
    assertEquals(1, diskUsage.get(2).getTables().size());
    client.deleteTable(creds, "foo");
    client.deleteTable(creds, TABLE_TEST2);
  }

  @Test
  public void importExportTable() throws Exception {
    // Write some data
    String expected[][] = new String[10][];
    for (int i = 0; i < 10; i++) {
      client.updateAndFlush(creds, table, mutation("row" + i, "cf", "cq", "" + i));
      expected[i] = new String[] {"row" + i, "cf", "cq", "" + i};
      client.flushTable(creds, table, null, null, true);
    }
    assertScan(expected, table);

    // export/import
    MiniAccumuloClusterImpl cluster = SharedMiniClusterIT.getCluster();
    FileSystem fs = cluster.getFileSystem();
    Path base = cluster.getTemporaryPath();
    Path dir = new Path(base, "test");
    assertTrue(fs.mkdirs(dir));
    Path destDir = new Path(base, "test_dest");
    assertTrue(fs.mkdirs(destDir));
    client.offlineTable(creds, table, false);
    client.exportTable(creds, table, dir.toString());
    // copy files to a new location
    FSDataInputStream is = fs.open(new Path(dir, "distcp.txt"));
    try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
      while (true) {
        String line = r.readLine();
        if (line == null)
          break;
        Path srcPath = new Path(line);
        FileUtil.copy(fs, srcPath, fs, destDir, false, fs.getConf());
      }
    }
    client.deleteTable(creds, table);
    client.importTable(creds, "testify", destDir.toString());
    assertScan(expected, "testify");
    client.deleteTable(creds, "testify");

    try {
      // ACCUMULO-1558 a second import from the same dir should fail, the first import moved the files
      client.importTable(creds, "testify2", destDir.toString());
      fail();
    } catch (Exception e) {}

    assertFalse(client.listTables(creds).contains("testify2"));
  }

  @Test
  public void localityGroups() throws Exception {
    Map<String,Set<String>> groups = new HashMap<String,Set<String>>();
    groups.put("group1", Collections.singleton("cf1"));
    groups.put("group2", Collections.singleton("cf2"));
    client.setLocalityGroups(creds, table, groups);
    assertEquals(groups, client.getLocalityGroups(creds, table));
  }

  @Test
  public void tableProperties() throws Exception {
    Map<String,String> systemProps = client.getSystemConfiguration(creds);
    String systemTableSplitThreshold = systemProps.get("table.split.threshold");

    Map<String,String> orig = client.getTableProperties(creds, table);
    client.setTableProperty(creds, table, "table.split.threshold", "500M");

    // Get the new table property value
    Map<String,String> update = client.getTableProperties(creds, table);
    assertEquals(update.get("table.split.threshold"), "500M");

    // Table level properties shouldn't affect system level values
    assertEquals(systemTableSplitThreshold, client.getSystemConfiguration(creds).get("table.split.threshold"));

    client.removeTableProperty(creds, table, "table.split.threshold");
    update = client.getTableProperties(creds, table);
    assertEquals(orig, update);
  }

  @Test
  public void tableRenames() throws Exception {
    // rename table
    Map<String,String> tables = client.tableIdMap(creds);
    client.renameTable(creds, table, "bar");
    Map<String,String> tables2 = client.tableIdMap(creds);
    assertEquals(tables.get(table), tables2.get("bar"));
    // table exists
    assertTrue(client.tableExists(creds, "bar"));
    assertFalse(client.tableExists(creds, table));
    client.renameTable(creds, "bar", table);
  }

  @Test
  public void bulkImport() throws Exception {
    MiniAccumuloClusterImpl cluster = SharedMiniClusterIT.getCluster();
    FileSystem fs = cluster.getFileSystem();
    Path base = cluster.getTemporaryPath();
    Path dir = new Path(base, "test");
    assertTrue(fs.mkdirs(dir));

    // Write an RFile
    String filename = dir + "/bulk/import/rfile.rf";
    FileSKVWriter writer = FileOperations.getInstance().openWriter(filename, fs, fs.getConf(), DefaultConfiguration.getInstance());
    writer.startDefaultLocalityGroup();
    writer.append(new org.apache.accumulo.core.data.Key(new Text("a"), new Text("b"), new Text("c")), new Value("value".getBytes()));
    writer.close();

    // Create failures directory
    fs.mkdirs(new Path(dir + "/bulk/fail"));

    // Run the bulk import
    client.importDirectory(creds, table, dir + "/bulk/import", dir + "/bulk/fail", true);

    // Make sure we find the data
    String scanner = client.createScanner(creds, table, null);
    ScanResult more = client.nextK(scanner, 100);
    client.closeScanner(scanner);
    assertEquals(1, more.results.size());
    ByteBuffer maxRow = client.getMaxRow(creds, table, null, null, false, null, false);
    assertEquals(s2bb("a"), maxRow);
  }

  @Test
  public void testTableClassLoad() throws Exception {
    assertFalse(client.testTableClassLoad(creds, table, "abc123", SortedKeyValueIterator.class.getName()));
    assertTrue(client.testTableClassLoad(creds, table, VersioningIterator.class.getName(), SortedKeyValueIterator.class.getName()));
  }

  private Condition newCondition(String cf, String cq) {
    return new Condition(new Column(s2bb(cf), s2bb(cq), s2bb("")));
  }

  private Condition newCondition(String cf, String cq, String val) {
    return newCondition(cf, cq).setValue(s2bb(val));
  }

  private Condition newCondition(String cf, String cq, long ts, String val) {
    return newCondition(cf, cq).setValue(s2bb(val)).setTimestamp(ts);
  }

  private ColumnUpdate newColUpdate(String cf, String cq, String val) {
    return new ColumnUpdate(s2bb(cf), s2bb(cq)).setValue(s2bb(val));
  }

  private ColumnUpdate newColUpdate(String cf, String cq, long ts, String val) {
    return new ColumnUpdate(s2bb(cf), s2bb(cq)).setTimestamp(ts).setValue(s2bb(val));
  }

  private void assertScan(String[][] expected, String table) throws Exception {
    String scid = client.createScanner(creds, table, new ScanOptions());
    ScanResult keyValues = client.nextK(scid, expected.length + 1);

    assertEquals(expected.length, keyValues.results.size());
    assertFalse(keyValues.more);

    for (int i = 0; i < keyValues.results.size(); i++) {
      checkKey(expected[i][0], expected[i][1], expected[i][2], expected[i][3], keyValues.results.get(i));
    }

    client.closeScanner(scid);
  }

  @Test
  public void testConditionalWriter() throws Exception {
    client.addConstraint(creds, table, NumericValueConstraint.class.getName());
    UtilWaitThread.sleep(ZOOKEEPER_PROPAGATION_TIME);

    while (!client.listConstraints(creds, table).containsKey(NumericValueConstraint.class.getName())) {
      log.info("Failed to see constraint");
      Thread.sleep(1000);
    }

    String cwid = client.createConditionalWriter(creds, table, new ConditionalWriterOptions());

    Map<ByteBuffer,ConditionalUpdates> updates = new HashMap<ByteBuffer,ConditionalUpdates>();

    updates.put(
        s2bb("00345"),
        new ConditionalUpdates(Arrays.asList(newCondition("meta", "seq")), Arrays.asList(newColUpdate("meta", "seq", 10, "1"),
            newColUpdate("data", "img", "73435435"))));

    Map<ByteBuffer,ConditionalStatus> results = client.updateRowsConditionally(cwid, updates);

    assertEquals(1, results.size());
    assertEquals(ConditionalStatus.ACCEPTED, results.get(s2bb("00345")));

    assertScan(new String[][] { {"00345", "data", "img", "73435435"}, {"00345", "meta", "seq", "1"}}, table);

    // test not setting values on conditions
    updates.clear();

    updates.put(s2bb("00345"), new ConditionalUpdates(Arrays.asList(newCondition("meta", "seq")), Arrays.asList(newColUpdate("meta", "seq", "2"))));
    updates.put(s2bb("00346"), new ConditionalUpdates(Arrays.asList(newCondition("meta", "seq")), Arrays.asList(newColUpdate("meta", "seq", "1"))));

    results = client.updateRowsConditionally(cwid, updates);

    assertEquals(2, results.size());
    assertEquals(ConditionalStatus.REJECTED, results.get(s2bb("00345")));
    assertEquals(ConditionalStatus.ACCEPTED, results.get(s2bb("00346")));

    assertScan(new String[][] { {"00345", "data", "img", "73435435"}, {"00345", "meta", "seq", "1"}, {"00346", "meta", "seq", "1"}}, table);

    // test setting values on conditions
    updates.clear();

    updates.put(
        s2bb("00345"),
        new ConditionalUpdates(Arrays.asList(newCondition("meta", "seq", "1")), Arrays.asList(newColUpdate("meta", "seq", 20, "2"),
            newColUpdate("data", "img", "567890"))));

    updates.put(s2bb("00346"), new ConditionalUpdates(Arrays.asList(newCondition("meta", "seq", "2")), Arrays.asList(newColUpdate("meta", "seq", "3"))));

    results = client.updateRowsConditionally(cwid, updates);

    assertEquals(2, results.size());
    assertEquals(ConditionalStatus.ACCEPTED, results.get(s2bb("00345")));
    assertEquals(ConditionalStatus.REJECTED, results.get(s2bb("00346")));

    assertScan(new String[][] { {"00345", "data", "img", "567890"}, {"00345", "meta", "seq", "2"}, {"00346", "meta", "seq", "1"}}, table);

    // test setting timestamp on condition to a non-existant version
    updates.clear();

    updates.put(
        s2bb("00345"),
        new ConditionalUpdates(Arrays.asList(newCondition("meta", "seq", 10, "2")), Arrays.asList(newColUpdate("meta", "seq", 30, "3"),
            newColUpdate("data", "img", "1234567890"))));

    results = client.updateRowsConditionally(cwid, updates);

    assertEquals(1, results.size());
    assertEquals(ConditionalStatus.REJECTED, results.get(s2bb("00345")));

    assertScan(new String[][] { {"00345", "data", "img", "567890"}, {"00345", "meta", "seq", "2"}, {"00346", "meta", "seq", "1"}}, table);

    // test setting timestamp to an existing version

    updates.clear();

    updates.put(
        s2bb("00345"),
        new ConditionalUpdates(Arrays.asList(newCondition("meta", "seq", 20, "2")), Arrays.asList(newColUpdate("meta", "seq", 30, "3"),
            newColUpdate("data", "img", "1234567890"))));

    results = client.updateRowsConditionally(cwid, updates);

    assertEquals(1, results.size());
    assertEquals(ConditionalStatus.ACCEPTED, results.get(s2bb("00345")));

    assertScan(new String[][] { {"00345", "data", "img", "1234567890"}, {"00345", "meta", "seq", "3"}, {"00346", "meta", "seq", "1"}}, table);

    // run test w/ condition that has iterators
    // following should fail w/o iterator
    client.updateAndFlush(creds, table, Collections.singletonMap(s2bb("00347"), Arrays.asList(newColUpdate("data", "count", "1"))));
    client.updateAndFlush(creds, table, Collections.singletonMap(s2bb("00347"), Arrays.asList(newColUpdate("data", "count", "1"))));
    client.updateAndFlush(creds, table, Collections.singletonMap(s2bb("00347"), Arrays.asList(newColUpdate("data", "count", "1"))));

    updates.clear();
    updates.put(s2bb("00347"),
        new ConditionalUpdates(Arrays.asList(newCondition("data", "count", "3")), Arrays.asList(newColUpdate("data", "img", "1234567890"))));

    results = client.updateRowsConditionally(cwid, updates);

    assertEquals(1, results.size());
    assertEquals(ConditionalStatus.REJECTED, results.get(s2bb("00347")));

    assertScan(new String[][] { {"00345", "data", "img", "1234567890"}, {"00345", "meta", "seq", "3"}, {"00346", "meta", "seq", "1"},
        {"00347", "data", "count", "1"}}, table);

    // following test w/ iterator setup should succeed
    Condition iterCond = newCondition("data", "count", "3");
    Map<String,String> props = new HashMap<String,String>();
    props.put("type", "STRING");
    props.put("columns", "data:count");
    IteratorSetting is = new IteratorSetting(1, "sumc", SummingCombiner.class.getName(), props);
    iterCond.setIterators(Arrays.asList(is));

    updates.clear();
    updates.put(s2bb("00347"), new ConditionalUpdates(Arrays.asList(iterCond), Arrays.asList(newColUpdate("data", "img", "1234567890"))));

    results = client.updateRowsConditionally(cwid, updates);

    assertEquals(1, results.size());
    assertEquals(ConditionalStatus.ACCEPTED, results.get(s2bb("00347")));

    assertScan(new String[][] { {"00345", "data", "img", "1234567890"}, {"00345", "meta", "seq", "3"}, {"00346", "meta", "seq", "1"},
        {"00347", "data", "count", "1"}, {"00347", "data", "img", "1234567890"}}, table);

    // test a mutation that violated a constraint
    updates.clear();
    updates.put(s2bb("00347"),
        new ConditionalUpdates(Arrays.asList(newCondition("data", "img", "1234567890")), Arrays.asList(newColUpdate("data", "count", "A"))));

    results = client.updateRowsConditionally(cwid, updates);

    assertEquals(1, results.size());
    assertEquals(ConditionalStatus.VIOLATED, results.get(s2bb("00347")));

    assertScan(new String[][] { {"00345", "data", "img", "1234567890"}, {"00345", "meta", "seq", "3"}, {"00346", "meta", "seq", "1"},
        {"00347", "data", "count", "1"}, {"00347", "data", "img", "1234567890"}}, table);

    // run test with two conditions
    // both conditions should fail
    updates.clear();
    updates.put(
        s2bb("00347"),
        new ConditionalUpdates(Arrays.asList(newCondition("data", "img", "565"), newCondition("data", "count", "2")), Arrays.asList(
            newColUpdate("data", "count", "3"), newColUpdate("data", "img", "0987654321"))));

    results = client.updateRowsConditionally(cwid, updates);

    assertEquals(1, results.size());
    assertEquals(ConditionalStatus.REJECTED, results.get(s2bb("00347")));

    assertScan(new String[][] { {"00345", "data", "img", "1234567890"}, {"00345", "meta", "seq", "3"}, {"00346", "meta", "seq", "1"},
        {"00347", "data", "count", "1"}, {"00347", "data", "img", "1234567890"}}, table);

    // one condition should fail
    updates.clear();
    updates.put(
        s2bb("00347"),
        new ConditionalUpdates(Arrays.asList(newCondition("data", "img", "1234567890"), newCondition("data", "count", "2")), Arrays.asList(
            newColUpdate("data", "count", "3"), newColUpdate("data", "img", "0987654321"))));

    results = client.updateRowsConditionally(cwid, updates);

    assertEquals(1, results.size());
    assertEquals(ConditionalStatus.REJECTED, results.get(s2bb("00347")));

    assertScan(new String[][] { {"00345", "data", "img", "1234567890"}, {"00345", "meta", "seq", "3"}, {"00346", "meta", "seq", "1"},
        {"00347", "data", "count", "1"}, {"00347", "data", "img", "1234567890"}}, table);

    // one condition should fail
    updates.clear();
    updates.put(
        s2bb("00347"),
        new ConditionalUpdates(Arrays.asList(newCondition("data", "img", "565"), newCondition("data", "count", "1")), Arrays.asList(
            newColUpdate("data", "count", "3"), newColUpdate("data", "img", "0987654321"))));

    results = client.updateRowsConditionally(cwid, updates);

    assertEquals(1, results.size());
    assertEquals(ConditionalStatus.REJECTED, results.get(s2bb("00347")));

    assertScan(new String[][] { {"00345", "data", "img", "1234567890"}, {"00345", "meta", "seq", "3"}, {"00346", "meta", "seq", "1"},
        {"00347", "data", "count", "1"}, {"00347", "data", "img", "1234567890"}}, table);

    // both conditions should succeed

    ConditionalStatus result = client.updateRowConditionally(
        creds,
        table,
        s2bb("00347"),
        new ConditionalUpdates(Arrays.asList(newCondition("data", "img", "1234567890"), newCondition("data", "count", "1")), Arrays.asList(
            newColUpdate("data", "count", "3"), newColUpdate("data", "img", "0987654321"))));

    assertEquals(ConditionalStatus.ACCEPTED, result);

    assertScan(new String[][] { {"00345", "data", "img", "1234567890"}, {"00345", "meta", "seq", "3"}, {"00346", "meta", "seq", "1"},
        {"00347", "data", "count", "3"}, {"00347", "data", "img", "0987654321"}}, table);

    client.closeConditionalWriter(cwid);
    try {
      client.updateRowsConditionally(cwid, updates);
      fail("conditional writer not closed");
    } catch (UnknownWriter uk) {}

    // run test with colvis
    client.createLocalUser(creds, "cwuser", s2bb("bestpasswordever"));
    client.changeUserAuthorizations(creds, "cwuser", Collections.singleton(s2bb("A")));
    client.grantTablePermission(creds, "cwuser", table, TablePermission.WRITE);
    client.grantTablePermission(creds, "cwuser", table, TablePermission.READ);

    ByteBuffer cwuCreds = client.login("cwuser", Collections.singletonMap("password", "bestpasswordever"));

    cwid = client.createConditionalWriter(cwuCreds, table, new ConditionalWriterOptions().setAuthorizations(Collections.singleton(s2bb("A"))));

    updates.clear();
    updates.put(
        s2bb("00348"),
        new ConditionalUpdates(Arrays.asList(new Condition(new Column(s2bb("data"), s2bb("c"), s2bb("A")))), Arrays.asList(newColUpdate("data", "seq", "1"),
            newColUpdate("data", "c", "1").setColVisibility(s2bb("A")))));
    updates.put(s2bb("00349"),
        new ConditionalUpdates(Arrays.asList(new Condition(new Column(s2bb("data"), s2bb("c"), s2bb("B")))), Arrays.asList(newColUpdate("data", "seq", "1"))));

    results = client.updateRowsConditionally(cwid, updates);

    assertEquals(2, results.size());
    assertEquals(ConditionalStatus.ACCEPTED, results.get(s2bb("00348")));
    assertEquals(ConditionalStatus.INVISIBLE_VISIBILITY, results.get(s2bb("00349")));

    assertScan(new String[][] { {"00345", "data", "img", "1234567890"}, {"00345", "meta", "seq", "3"}, {"00346", "meta", "seq", "1"},
        {"00347", "data", "count", "3"}, {"00347", "data", "img", "0987654321"}, {"00348", "data", "seq", "1"}}, table);

    updates.clear();

    updates.clear();
    updates.put(
        s2bb("00348"),
        new ConditionalUpdates(Arrays.asList(new Condition(new Column(s2bb("data"), s2bb("c"), s2bb("A"))).setValue(s2bb("0"))), Arrays.asList(
            newColUpdate("data", "seq", "2"), newColUpdate("data", "c", "2").setColVisibility(s2bb("A")))));

    results = client.updateRowsConditionally(cwid, updates);

    assertEquals(1, results.size());
    assertEquals(ConditionalStatus.REJECTED, results.get(s2bb("00348")));

    assertScan(new String[][] { {"00345", "data", "img", "1234567890"}, {"00345", "meta", "seq", "3"}, {"00346", "meta", "seq", "1"},
        {"00347", "data", "count", "3"}, {"00347", "data", "img", "0987654321"}, {"00348", "data", "seq", "1"}}, table);

    updates.clear();
    updates.put(
        s2bb("00348"),
        new ConditionalUpdates(Arrays.asList(new Condition(new Column(s2bb("data"), s2bb("c"), s2bb("A"))).setValue(s2bb("1"))), Arrays.asList(
            newColUpdate("data", "seq", "2"), newColUpdate("data", "c", "2").setColVisibility(s2bb("A")))));

    results = client.updateRowsConditionally(cwid, updates);

    assertEquals(1, results.size());
    assertEquals(ConditionalStatus.ACCEPTED, results.get(s2bb("00348")));

    assertScan(new String[][] { {"00345", "data", "img", "1234567890"}, {"00345", "meta", "seq", "3"}, {"00346", "meta", "seq", "1"},
        {"00347", "data", "count", "3"}, {"00347", "data", "img", "0987654321"}, {"00348", "data", "seq", "2"}}, table);

    client.closeConditionalWriter(cwid);
    try {
      client.updateRowsConditionally(cwid, updates);
      fail("conditional writer not closed");
    } catch (UnknownWriter uk) {}

    client.dropLocalUser(creds, "cwuser");

  }

  private void checkKey(String row, String cf, String cq, String val, KeyValue keyValue) {
    assertEquals(row, ByteBufferUtil.toString(keyValue.key.row));
    assertEquals(cf, ByteBufferUtil.toString(keyValue.key.colFamily));
    assertEquals(cq, ByteBufferUtil.toString(keyValue.key.colQualifier));
    assertEquals("", ByteBufferUtil.toString(keyValue.key.colVisibility));
    assertEquals(val, ByteBufferUtil.toString(keyValue.value));
  }

  // scan metadata for file entries for the given table
  private int countFiles(String table) throws Exception {
    Map<String,String> tableIdMap = client.tableIdMap(creds);
    String tableId = tableIdMap.get(table);
    Key start = new Key();
    start.row = s2bb(tableId + ";");
    Key end = new Key();
    end.row = s2bb(tableId + "<");
    end = client.getFollowing(end, PartialKey.ROW);
    ScanOptions opt = new ScanOptions();
    opt.range = new Range(start, true, end, false);
    opt.columns = Collections.singletonList(new ScanColumn(s2bb("file")));
    String scanner = client.createScanner(creds, MetadataTable.NAME, opt);
    int result = 0;
    while (true) {
      ScanResult more = client.nextK(scanner, 100);
      result += more.getResults().size();
      if (!more.more)
        break;
    }
    return result;
  }

  private Map<ByteBuffer,List<ColumnUpdate>> mutation(String row, String cf, String cq, String value) {
    ColumnUpdate upd = new ColumnUpdate(s2bb(cf), s2bb(cq));
    upd.setValue(value.getBytes());
    return Collections.singletonMap(s2bb(row), Collections.singletonList(upd));
  }

  private ByteBuffer s2bb(String cf) {
    return ByteBuffer.wrap(cf.getBytes());
  }

  private Map<String,String> s2pp(String cf) {
    Map<String,String> toRet = new TreeMap<String,String>();
    toRet.put("password", cf);
    return toRet;
  }

  static private ByteBuffer t2bb(Text t) {
    return ByteBuffer.wrap(t.getBytes());
  }

  @Test
  public void testGetRowRange() throws Exception {
    Range range = client.getRowRange(s2bb("xyzzy"));
    org.apache.accumulo.core.data.Range range2 = new org.apache.accumulo.core.data.Range(new Text("xyzzy"));
    assertEquals(0, range.start.row.compareTo(t2bb(range2.getStartKey().getRow())));
    assertEquals(0, range.stop.row.compareTo(t2bb(range2.getEndKey().getRow())));
    assertEquals(range.startInclusive, range2.isStartKeyInclusive());
    assertEquals(range.stopInclusive, range2.isEndKeyInclusive());
    assertEquals(0, range.start.colFamily.compareTo(t2bb(range2.getStartKey().getColumnFamily())));
    assertEquals(0, range.start.colQualifier.compareTo(t2bb(range2.getStartKey().getColumnQualifier())));
    assertEquals(0, range.stop.colFamily.compareTo(t2bb(range2.getEndKey().getColumnFamily())));
    assertEquals(0, range.stop.colQualifier.compareTo(t2bb(range2.getEndKey().getColumnQualifier())));
    assertEquals(range.start.timestamp, range.start.timestamp);
    assertEquals(range.stop.timestamp, range.stop.timestamp);
  }

  @Test
  public void testCompactionStrategy() throws Exception {
    client.setProperty(creds, Property.VFS_CONTEXT_CLASSPATH_PROPERTY.getKey() + "context1", System.getProperty("user.dir")
        + "/src/test/resources/TestCompactionStrat.jar");
    client.setTableProperty(creds, table, Property.TABLE_CLASSPATH.getKey(), "context1");

    client.addSplits(creds, table, Collections.singleton(s2bb("efg")));

    client.updateAndFlush(creds, table, mutation("a", "cf", "cq", "v1"));
    client.flushTable(creds, table, null, null, true);

    client.updateAndFlush(creds, table, mutation("b", "cf", "cq", "v2"));
    client.flushTable(creds, table, null, null, true);

    client.updateAndFlush(creds, table, mutation("y", "cf", "cq", "v1"));
    client.flushTable(creds, table, null, null, true);

    client.updateAndFlush(creds, table, mutation("z", "cf", "cq", "v2"));
    client.flushTable(creds, table, null, null, true);

    assertEquals(4, countFiles(table));

    CompactionStrategyConfig csc = new CompactionStrategyConfig();

    // The EfgCompactionStrat will only compact tablets with and end row of efg
    csc.setClassName("org.apache.accumulo.test.EfgCompactionStrat");

    client.compactTable(creds, table, null, null, null, true, true, csc);

    assertEquals(3, countFiles(table));
  }
}

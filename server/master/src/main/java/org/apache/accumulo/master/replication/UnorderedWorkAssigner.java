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
package org.apache.accumulo.master.replication;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.replication.ReplicationTarget;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.accumulo.core.zookeeper.ZooUtil;
import org.apache.hadoop.fs.Path;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read work records from the replication table, create work entries for other nodes to complete.
 * <p>
 * Uses the DistributedWorkQueue to make the work available for any tserver. This approach does not consider the locality of the tabletserver performing the
 * work in relation to the data being replicated (local HDFS blocks).
 * <p>
 * The implementation allows for multiple tservers to concurrently replicate data to peer(s), however it is possible that data for a table is replayed on the
 * peer in a different order than the master. The {@link SequentialWorkAssigner} should be used if this must be guaranteed at the cost of replication
 * throughput.
 */
public class UnorderedWorkAssigner extends DistributedWorkQueueWorkAssigner {
  private static final Logger log = LoggerFactory.getLogger(DistributedWorkQueueWorkAssigner.class);
  private static final String NAME = "DistributedWorkQueue Replication Work Assigner";

  private Set<String> queuedWork;

  public UnorderedWorkAssigner() {}

  public UnorderedWorkAssigner(AccumuloConfiguration conf, Connector conn) {
    configure(conf, conn);
  }

  @Override
  public String getName() {
    return NAME;
  }

  protected Set<String> getQueuedWork() {
    return queuedWork;
  }

  protected void setQueuedWork(Set<String> queuedWork) {
    this.queuedWork = queuedWork;
  }

  /**
   * Initialize the queuedWork set with the work already sent out
   */
  protected void initializeQueuedWork() {
    if (null != queuedWork) {
      return;
    }

    queuedWork = new HashSet<>();
    while (true) {
      try {
        queuedWork.addAll(workQueue.getWorkQueued());
        return;
      } catch (KeeperException e) {
        if (KeeperException.Code.NONODE.equals(e.code())) {
          log.warn("Could not find ZK root for replication work queue, will retry", e);
          UtilWaitThread.sleep(500);
          continue;
        }

        log.error("Error reading existing queued replication work from ZooKeeper", e);
        throw new RuntimeException("Error reading existing queued replication work from ZooKeeper", e);
      } catch (InterruptedException e) {
        log.error("Error reading existing queued replication work from ZooKeeper", e);
        throw new RuntimeException("Error reading existing queued replication work from ZooKeeper", e);
      }
    }
  }

  /**
   * Distribute the work for the given path with filename
   * 
   * @param path
   *          Path to the file being replicated
   * @param target
   *          Target for the file to be replicated to
   */
  @Override
  protected boolean queueWork(Path path, ReplicationTarget target) {
    String queueKey = getQueueKey(path.getName(), target);
    if (queuedWork.contains(queueKey)) {
      return false;
    }

    try {
      log.debug("Queued work for {} and {}", queueKey, path);
      workQueue.addWork(queueKey, path.toString());
      queuedWork.add(queueKey);
    } catch (KeeperException | InterruptedException e) {
      log.warn("Could not queue work for {}", path, e);
      return false;
    }

    return true;
  }

  /**
   * Iterate over the queued work to remove entries that have been completed.
   */
  @Override
  protected void cleanupFinishedWork() {
    final Iterator<String> work = queuedWork.iterator();
    final String instanceId = conn.getInstance().getInstanceID();
    while (work.hasNext()) {
      String filename = work.next();
      // Null equates to the work was finished
      if (null == zooCache.get(ZooUtil.getRoot(instanceId) + Constants.ZREPLICATION_WORK_QUEUE + "/" + filename)) {
        work.remove();
      }
    }
  }

  @Override
  protected boolean shouldQueueWork(ReplicationTarget target) {
    // We don't care about ordering, just replicate it all
    return true;
  }

  @Override
  protected int getQueueSize() {
    return this.queuedWork.size();
  }

  @Override
  protected Set<String> getQueuedWork(ReplicationTarget target) {
    String desiredQueueKeySuffix = KEY_SEPARATOR + target.getPeerName() + KEY_SEPARATOR + target.getRemoteIdentifier() + KEY_SEPARATOR
        + target.getSourceTableId();
    Set<String> queuedWorkForTarget = new HashSet<>();
    for (String queuedWork : this.queuedWork) {
      if (queuedWork.endsWith(desiredQueueKeySuffix)) {
        queuedWorkForTarget.add(queuedWork);
      }
    }

    return queuedWorkForTarget;
  }

  @Override
  protected void removeQueuedWork(ReplicationTarget target, String queueKey) {
    this.queuedWork.remove(queueKey);
  }
}

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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.mock.MockInstance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.replication.ReplicationSchema.StatusSection;
import org.apache.accumulo.core.replication.ReplicationSchema.WorkSection;
import org.apache.accumulo.core.replication.ReplicationTable;
import org.apache.accumulo.core.replication.ReplicationTarget;
import org.apache.accumulo.core.replication.StatusUtil;
import org.apache.accumulo.server.conf.TableConfiguration;
import org.apache.accumulo.server.replication.ReplicationTable;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

/**
 * 
 */
public class WorkMakerTest {

  private final Map<String,TableConfiguration> tableConfs = new HashMap<String,TableConfiguration>();

  private MockInstance instance;
  private Connector conn;

  @Rule
  public TestName name = new TestName();

  @Before
  public void createMockAccumulo() throws Exception {
    instance = new MockInstance();
    conn = instance.getConnector("root", new PasswordToken(""));

    if (conn.tableOperations().exists(ReplicationTable.NAME)) {
      conn.tableOperations().delete(ReplicationTable.NAME);
    }

    conn.tableOperations().create(ReplicationTable.NAME);

    tableConfs.clear();
  }

  @Test
  public void singleUnitSingleTarget() throws Exception {
    String table = name.getMethodName();
    conn.tableOperations().create(name.getMethodName());
    String tableId = conn.tableOperations().tableIdMap().get(table);
    String file = "hdfs://localhost:8020/accumulo/wal/123456-1234-1234-12345678";

    Mutation m = new Mutation(new Path(file).toString());
    m.put(StatusSection.NAME, new Text(tableId), StatusUtil.fileClosedValue());
    BatchWriter bw = ReplicationTable.getBatchWriter(conn);
    bw.addMutation(m);
    bw.flush();

    // Assert that we have one record in the status section
    Scanner s = ReplicationTable.getScanner(conn);
    StatusSection.limit(s);
    Assert.assertEquals(1, Iterables.size(s));

    WorkMaker workMaker = new WorkMaker(conn);

    ReplicationTarget expected = new ReplicationTarget("remote_cluster_1", "4");
    workMaker.setBatchWriter(bw);
    workMaker.addWorkRecord(new Text(file), StatusUtil.fileClosedValue(), ImmutableMap.of("remote_cluster_1", "4"));

    s = ReplicationTable.getScanner(conn);
    WorkSection.limit(s);

    Iterator<Entry<Key,Value>> iter = s.iterator();
    Assert.assertTrue(iter.hasNext());

    Entry<Key,Value> workEntry = iter.next();
    Key workKey = workEntry.getKey();
    ReplicationTarget actual = ReplicationTarget.from(workKey.getColumnQualifier());

    Assert.assertEquals(file, workKey.getRow().toString());
    Assert.assertEquals(WorkSection.NAME, workKey.getColumnFamily());
    Assert.assertEquals(expected, actual);
    Assert.assertEquals(workEntry.getValue(), StatusUtil.fileClosedValue());

    Assert.assertFalse(iter.hasNext());
  }

  @Test
  public void singleUnitMultipleTargets() throws Exception {
    String table = name.getMethodName();
    conn.tableOperations().create(name.getMethodName());
    String tableId = conn.tableOperations().tableIdMap().get(table);
    String file = "hdfs://localhost:8020/accumulo/wal/123456-1234-1234-12345678";

    Mutation m = new Mutation(new Path(file).toString());
    m.put(StatusSection.NAME, new Text(tableId), StatusUtil.fileClosedValue());
    BatchWriter bw = ReplicationTable.getBatchWriter(conn);
    bw.addMutation(m);
    bw.flush();

    // Assert that we have one record in the status section
    Scanner s = ReplicationTable.getScanner(conn);
    StatusSection.limit(s);
    Assert.assertEquals(1, Iterables.size(s));

    WorkMaker workMaker = new WorkMaker(conn);

    Map<String,String> targetClusters = ImmutableMap.of("remote_cluster_1", "4", "remote_cluster_2", "6", "remote_cluster_3", "8");
    Set<ReplicationTarget> expectedTargets = new HashSet<ReplicationTarget>();
    for (Entry<String,String> cluster : targetClusters.entrySet()) {
      expectedTargets.add(new ReplicationTarget(cluster.getKey(), cluster.getValue()));
    }
    workMaker.setBatchWriter(bw);
    workMaker.addWorkRecord(new Text(file), StatusUtil.fileClosedValue(), targetClusters);

    s = ReplicationTable.getScanner(conn);
    WorkSection.limit(s);

    Set<ReplicationTarget> actualTargets = new HashSet<ReplicationTarget>();
    for (Entry<Key,Value> entry : s) {
      Assert.assertEquals(file, entry.getKey().getRow().toString());
      Assert.assertEquals(WorkSection.NAME, entry.getKey().getColumnFamily());

      ReplicationTarget target = ReplicationTarget.from(entry.getKey().getColumnQualifier());
      actualTargets.add(target);
    }

    for (ReplicationTarget expected : expectedTargets) {
      Assert.assertTrue("Did not find expected target: " + expected, actualTargets.contains(expected));
      actualTargets.remove(expected);
    }

    Assert.assertTrue("Found extra replication work entries: " + actualTargets, actualTargets.isEmpty());
  }

  @Test
  public void dontCreateWorkForEntriesWithNothingToReplicate() throws Exception {
    String table = name.getMethodName();
    conn.tableOperations().create(name.getMethodName());
    String tableId = conn.tableOperations().tableIdMap().get(table);
    String file = "hdfs://localhost:8020/accumulo/wal/123456-1234-1234-12345678";

    Mutation m = new Mutation(new Path(file).toString());
    m.put(StatusSection.NAME, new Text(tableId), StatusUtil.newFileValue());
    BatchWriter bw = ReplicationTable.getBatchWriter(conn);
    bw.addMutation(m);
    bw.flush();

    // Assert that we have one record in the status section
    Scanner s = ReplicationTable.getScanner(conn);
    StatusSection.limit(s);
    Assert.assertEquals(1, Iterables.size(s));

    WorkMaker workMaker = new WorkMaker(conn);

    conn.tableOperations().setProperty(ReplicationTable.NAME, Property.TABLE_REPLICATION_TARGETS.getKey() + "remote_cluster_1", "4");

    workMaker.setBatchWriter(bw);

    // If we don't shortcircuit out, we should get an exception because ServerConfiguration.getTableConfiguration
    // won't work with MockAccumulo
    workMaker.run();

    s = ReplicationTable.getScanner(conn);
    WorkSection.limit(s);

    Assert.assertEquals(0, Iterables.size(s));
  }
}

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
package org.apache.accumulo.test;

import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.metadata.schema.MetadataSchema;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.accumulo.minicluster.impl.MiniAccumuloConfigImpl;
import org.apache.accumulo.test.functional.ConfigurableMacIT;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.junit.Ignore;
import org.junit.Test;

// ACCUMULO-2952
@Ignore
public class BalanceFasterIT extends ConfigurableMacIT {

  @Override
  public void configure(MiniAccumuloConfigImpl cfg, Configuration hadoopCoreSite) {
    cfg.setNumTservers(3);
  }

  @Test(timeout = 90 * 1000)
  public void test() throws Exception {
    // create a table, add a bunch of splits
    String tableName = getUniqueNames(1)[0];
    Connector conn = getConnector();
    conn.tableOperations().create(tableName);
    SortedSet<Text> splits = new TreeSet<Text>();
    for (int i = 0; i < 1000; i++) {
      splits.add(new Text("" + i));
    }
    conn.tableOperations().addSplits(tableName, splits);
    // give a short wait for balancing
    UtilWaitThread.sleep(10 * 1000);
    // find out where the tabets are
    Scanner s = conn.createScanner(MetadataTable.NAME, Authorizations.EMPTY);
    s.fetchColumnFamily(MetadataSchema.TabletsSection.CurrentLocationColumnFamily.NAME);
    s.setRange(MetadataSchema.TabletsSection.getRange());
    Map<String,Integer> counts = new HashMap<String,Integer>();
    while (true) {
      int total = 0;
      counts.clear();
      for (Entry<Key,Value> kv : s) {
        String host = kv.getValue().toString();
        if (!counts.containsKey(host))
          counts.put(host, 0);
        counts.put(host, counts.get(host) + 1);
        total++;
      }
      // are enough tablets online?
      if (total > 1000)
        break;
    }
    // should be on all three servers
    assertTrue(counts.size() == 3);
    // and distributed evenly
    Iterator<Integer> i = counts.values().iterator();
    int a = i.next();
    int b = i.next();
    int c = i.next();
    assertTrue(Math.abs(a - b) < 3);
    assertTrue(Math.abs(a - c) < 3);
    assertTrue(a > 330);
  }

}

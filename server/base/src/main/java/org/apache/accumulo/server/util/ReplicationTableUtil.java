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
package org.apache.accumulo.server.util;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.impl.Writer;
import org.apache.accumulo.core.data.KeyExtent;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.protobuf.ProtobufUtil;
import org.apache.accumulo.core.replication.ReplicationSchema.ReplicationSection;
import org.apache.accumulo.core.replication.ReplicationTable;
import org.apache.accumulo.core.replication.proto.Replication.Status;
import org.apache.accumulo.core.security.Credentials;
import org.apache.accumulo.core.tabletserver.thrift.ConstraintViolationException;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.accumulo.server.client.HdfsZooInstance;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Logger;

/**
 * provides a reference to the replication table for updates by tablet servers
 */
public class ReplicationTableUtil {

  private static final Text EMPTY_TEXT = new Text();
  private static Map<Credentials,Writer> replicationTables = new HashMap<Credentials,Writer>();
  private static final Logger log = Logger.getLogger(ReplicationTableUtil.class);

  private ReplicationTableUtil() {}

  public synchronized static Writer getReplicationTable(Credentials credentials) {
    Writer replicationTable = replicationTables.get(credentials);
    if (replicationTable == null) {
      Instance inst = HdfsZooInstance.getInstance();
      Connector conn;
      try {
        conn = inst.getConnector(credentials.getPrincipal(), credentials.getToken());
      } catch (AccumuloException e) {
        log.error(e);
        throw new RuntimeException(e);
      } catch (AccumuloSecurityException e) {
        log.error(e);
        throw new RuntimeException(e);
      }

      // Create the table if it doesn't already exist
      ReplicationTable.create(conn.tableOperations());

      replicationTable = new Writer(inst, credentials, ReplicationTable.ID);
      replicationTables.put(credentials, replicationTable);
    }
    return replicationTable;
  }

  /**
   * Write the given Mutation to the replication table. 
   */
  public static void update(Credentials credentials, Mutation m, KeyExtent extent) {
    Writer t = getReplicationTable(credentials);
    while (true) {
      try {
        t.update(m);
        return;
      } catch (AccumuloException e) {
        log.error(e, e);
      } catch (AccumuloSecurityException e) {
        log.error(e, e);
      } catch (ConstraintViolationException e) {
        log.error(e, e);
      } catch (TableNotFoundException e) {
        log.error(e, e);
      }
      UtilWaitThread.sleep(1000);
    }
  }

  /**
   * Write {@link ReplicationSection#getRowPrefix} entries for each provided file with the given {@link Status}.
   */
  public static void updateReplication(Credentials creds, KeyExtent extent, Collection<String> files, Status stat) {
    // TODO could use batch writer, would need to handle failure and retry like update does - ACCUMULO-1294
    for (String file : files) {
      // TODO Can preclude this addition if the extent is for a table we don't need to replicate
      update(creds, createUpdateMutation(file, stat, extent), extent);
    }
  }

  protected static Mutation createUpdateMutation(String file, Status stat, KeyExtent extent) {
    Value v = ProtobufUtil.toValue(stat);
    int offset = file.indexOf('/');
    String fileOnly;
    if (-1 != offset) {
      fileOnly = file.substring(offset + 1);
    } else {
      log.warn("Expected forward-slash-delimited host/file: '" + file + "'");
      fileOnly = file;
    }
    Mutation m = new Mutation(new Text(ReplicationSection.getRowPrefix() + fileOnly));
    m.put(extent.getTableId(), EMPTY_TEXT, v);
    return m;
  }
}

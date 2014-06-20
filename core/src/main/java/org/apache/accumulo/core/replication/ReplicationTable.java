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
package org.apache.accumulo.server.replication;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableExistsException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.iterators.Combiner;
import org.apache.accumulo.core.iterators.IteratorUtil.IteratorScope;
import org.apache.accumulo.core.replication.ReplicationSchema.StatusSection;
import org.apache.accumulo.core.replication.ReplicationSchema.WorkSection;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.fate.util.UtilWaitThread;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Logger;

import com.google.common.collect.ImmutableMap;

public class ReplicationTable {
  private static final Logger log = Logger.getLogger(ReplicationTable.class);

  public static final String NAME = "replication";

  public static final String STATUS_LG_NAME = StatusSection.NAME.toString();
  public static final Set<Text> STATUS_LG_COLFAMS = Collections.singleton(StatusSection.NAME);
  public static final String WORK_LG_NAME = WorkSection.NAME.toString();
  public static final Set<Text> WORK_LG_COLFAMS = Collections.singleton(WorkSection.NAME);
  public static final Map<String,Set<Text>> LOCALITY_GROUPS = ImmutableMap.of(STATUS_LG_NAME, STATUS_LG_COLFAMS, WORK_LG_NAME, WORK_LG_COLFAMS);

  public static synchronized void create(TableOperations tops) {
    if (tops.exists(NAME)) {
      return;
    }

    for (int i = 0; i < 5; i++) {
      try {
        tops.create(NAME);
        return;
      } catch (AccumuloException e) {
        log.error("Failed to create replication table", e);
      } catch (AccumuloSecurityException e) {
        log.error("Failed to create replication table", e);
      } catch (TableExistsException e) {
        return;
      }
      log.error("Retrying table creation in 1 second...");
      UtilWaitThread.sleep(1000);
    }
  }

  /**
   * Attempts to configure the replication table, will return false if it fails
   * 
   * @param tops
   *          TableOperations for the instance
   * @return True if the replication table is properly configured
   */
  protected static synchronized boolean configure(TableOperations tops) {
    Map<String,EnumSet<IteratorScope>> iterators = null;
    try {
      iterators = tops.listIterators(NAME);
    } catch (AccumuloSecurityException | AccumuloException | TableNotFoundException e) {
      log.error("Could not fetch iterators for " + NAME, e);
      return false;
    }

    if (!iterators.containsKey(COMBINER_NAME)) {
      // Set our combiner and combine all columns
      IteratorSetting setting = new IteratorSetting(50, COMBINER_NAME, StatusCombiner.class);
      Combiner.setCombineAllColumns(setting, true);
      try {
        tops.attachIterator(NAME, setting);
      } catch (AccumuloSecurityException | AccumuloException | TableNotFoundException e) {
        log.error("Could not set StatusCombiner on replication table", e);
        return false;
      }
    }

    Map<String,Set<Text>> localityGroups;
    try {
      localityGroups = tops.getLocalityGroups(NAME);
    } catch (TableNotFoundException | AccumuloException e) {
      log.error("Could not fetch locality groups", e);
      return false;
    }

    Set<Text> statusColfams = localityGroups.get(STATUS_LG_NAME), workColfams = localityGroups.get(WORK_LG_NAME);
    if (null == statusColfams || null == workColfams) {
      try {
        tops.setLocalityGroups(NAME, LOCALITY_GROUPS);
      } catch (AccumuloException | AccumuloSecurityException | TableNotFoundException e) {
        log.error("Could not set locality groups on replication table", e);
        return false;
      }
    }

    return true;
  }

  public static Scanner getScanner(Connector conn, Authorizations auths) throws TableNotFoundException {
    return conn.createScanner(NAME, auths);
  }

  public static Scanner getScanner(Connector conn) throws TableNotFoundException {
    return getScanner(conn, new Authorizations());
  }

  public static BatchWriter getBatchWriter(Connector conn) throws TableNotFoundException {
    return getBatchWriter(conn, new BatchWriterConfig());
  }

  public static BatchWriter getBatchWriter(Connector conn, BatchWriterConfig config) throws TableNotFoundException {
    return conn.createBatchWriter(NAME, config);
  }
}

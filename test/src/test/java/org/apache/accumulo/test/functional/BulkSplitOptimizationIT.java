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
package org.apache.accumulo.test.functional;

import java.io.File;
import java.util.Collections;

import org.apache.accumulo.core.cli.ClientOpts.Password;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.util.CachedConfiguration;
import org.apache.accumulo.core.util.UtilWaitThread;
import org.apache.accumulo.minicluster.impl.MiniAccumuloConfigImpl;
import org.apache.accumulo.test.VerifyIngest;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * This test verifies that when a lot of files are bulk imported into a table with one tablet and then splits that not all map files go to the children tablets.
 */

public class BulkSplitOptimizationIT extends ConfigurableMacIT {

  private static final String TABLE_NAME = "test_ingest";

  @Rule
  public TemporaryFolder folder = new TemporaryFolder(new File(System.getProperty("user.dir") + "/target"));

  @Override
  public void configure(MiniAccumuloConfigImpl cfg, Configuration hadoopCoreSite) {
    cfg.setSiteConfig(Collections.singletonMap(Property.TSERV_MAJC_DELAY.getKey(), "1s"));
  }

  @Override
  protected int defaultTimeoutSeconds() {
    return 2 * 60;
  }

  static final int ROWS = 100000;
  static final int SPLITS = 99;

  @Test
  public void testBulkSplitOptimization() throws Exception {
    final Connector c = getConnector();
    c.tableOperations().create(TABLE_NAME);
    c.tableOperations().setProperty(TABLE_NAME, Property.TABLE_MAJC_RATIO.getKey(), "1000");
    c.tableOperations().setProperty(TABLE_NAME, Property.TABLE_FILE_MAX.getKey(), "1000");
    c.tableOperations().setProperty(TABLE_NAME, Property.TABLE_SPLIT_THRESHOLD.getKey(), "1G");

    FileSystem fs = FileSystem.get(CachedConfiguration.getInstance());
    FunctionalTestUtils.createRFiles(c, fs, folder.getRoot() + "/testmf", ROWS, SPLITS, 8);
    File input = new File(folder.getRoot() + "/testmf");
    System.out.println("Number of generated files: " + input.listFiles().length);
    FunctionalTestUtils.bulkImport(c, fs, TABLE_NAME, folder.getRoot() + "/testmf");
    FunctionalTestUtils.checkSplits(c, TABLE_NAME, 0, 0);
    FunctionalTestUtils.checkRFiles(c, TABLE_NAME, 1, 1, 100, 100);

    // initiate splits
    getConnector().tableOperations().setProperty(TABLE_NAME, Property.TABLE_SPLIT_THRESHOLD.getKey(), "100K");

    UtilWaitThread.sleep(2000);

    // wait until over split threshold -- should be 78 splits
    while (getConnector().tableOperations().listSplits(TABLE_NAME).size() < 75) {
      UtilWaitThread.sleep(500);
    }

    FunctionalTestUtils.checkSplits(c, TABLE_NAME, 50, 100);
    VerifyIngest.Opts opts = new VerifyIngest.Opts();
    opts.timestamp = 1;
    opts.dataSize = 50;
    opts.random = 56;
    opts.rows = 100000;
    opts.startRow = 0;
    opts.cols = 1;
    opts.password = new Password(ROOT_PASSWORD);
    VerifyIngest.verifyIngest(c, opts, SOPTS);

    // ensure each tablet does not have all map files, should be ~2.5 files per tablet
    FunctionalTestUtils.checkRFiles(c, TABLE_NAME, 50, 100, 1, 4);
  }

}

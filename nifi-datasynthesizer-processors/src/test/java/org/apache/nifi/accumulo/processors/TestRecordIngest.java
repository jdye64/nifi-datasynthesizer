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
package org.apache.nifi.accumulo.processors;

import org.apache.accumulo.core.client.*;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.accumulo.minicluster.MiniAccumuloCluster;
import org.apache.hadoop.io.Text;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.serialization.record.MockRecordParser;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class TestRecordIngest {

    public static final String DEFAULT_COLUMN_FAMILY = "family1";

    /**
     * Though deprecated in 2.0 it still functions very well
     */
    private static MiniAccumuloCluster accumulo;

    private TestRunner getTestRunner(String table, String columnFamily) {
        final TestRunner runner = TestRunners.newTestRunner(RecordIngest.class);
        runner.enforceReadStreamsClosed(false);
        runner.setProperty(RecordIngest.TABLE_NAME, table);
        return runner;
    }




    @BeforeClass
    public static void setupInstance() throws IOException, InterruptedException, AccumuloSecurityException, AccumuloException, TableExistsException {
        Path tempDirectory = Files.createTempDirectory("acc"); // JUnit and Guava supply mechanisms for creating temp directories
        accumulo = new MiniAccumuloCluster(tempDirectory.toFile(), "password");
        accumulo.start();
    }

    private Set<Key> generateTestData(TestRunner runner, boolean valueincq, String delim, String cv) throws IOException {

        final MockRecordParser parser = new MockRecordParser();
        try {
            runner.addControllerService("parser", parser);
        } catch (InitializationException e) {
            throw new IOException(e);
        }
        runner.enableControllerService(parser);
        runner.setProperty(RecordIngest.RECORD_READER_FACTORY, "parser");

        long ts = System.currentTimeMillis();

        parser.addSchemaField("id", RecordFieldType.STRING);
        parser.addSchemaField("name", RecordFieldType.STRING);
        parser.addSchemaField("code", RecordFieldType.STRING);
        parser.addSchemaField("timestamp", RecordFieldType.LONG);

        Set<Key> expectedKeys = new HashSet<>();
        ColumnVisibility colViz = new ColumnVisibility();
        if (null != cv)
            colViz = new ColumnVisibility(cv);
        Random random = new Random();
        for (int x = 0; x < 5; x++) {
            //final int row = random.nextInt(10000000);
            final String row = UUID.randomUUID().toString();
            final String cf = UUID.randomUUID().toString();
            final String cq = UUID.randomUUID().toString();
            Text keyCq = new Text("name");
            if (valueincq){
                if (null != delim && !delim.isEmpty())
                    keyCq.append(delim.getBytes(),0,delim.length());
                keyCq.append(cf.getBytes(),0,cf.length());
            }
            expectedKeys.add(new Key(new Text(row), new Text("family1"), keyCq, colViz,ts));
            keyCq = new Text("code");
            if (valueincq){
                if (null != delim && !delim.isEmpty())
                    keyCq.append(delim.getBytes(),0,delim.length());
                keyCq.append(cq.getBytes(),0,cq.length());
            }
            expectedKeys.add(new Key(new Text(row), new Text("family1"), keyCq, colViz, ts));
            parser.addRecord(row, cf, cq, ts);
        }

        return expectedKeys;
    }

    void printKeys(String tableName, Set<Key> expectedKeys, Authorizations auths) throws AccumuloSecurityException, AccumuloException, TableNotFoundException {
        if (null == auths)
            auths = new Authorizations();
        try(BatchScanner scanner = accumulo.getConnector("root","password").createBatchScanner(tableName,auths,1)) {
            List<Range> ranges = new ArrayList<>();
            ranges.add(new Range());
            scanner.setRanges(ranges);
            for (Map.Entry<Key, Value> kv : scanner) {
                System.out.println(kv.getKey());
            }
        }

    }

    void verifyKey(String tableName, Set<Key> expectedKeys, Authorizations auths) throws AccumuloSecurityException, AccumuloException, TableNotFoundException {
        if (null == auths)
            auths = new Authorizations();
        try(BatchScanner scanner = accumulo.getConnector("root","password").createBatchScanner(tableName,auths,1)) {
            List<Range> ranges = new ArrayList<>();
            ranges.add(new Range());
            scanner.setRanges(ranges);
            for (Map.Entry<Key, Value> kv : scanner) {
                Assert.assertTrue(kv.getKey() + " not in expected keys",expectedKeys.remove(kv.getKey()));
            }
        }
        Assert.assertEquals(0, expectedKeys.size());

    }

    private void basicPutSetup(boolean valueincq) throws Exception {
        basicPutSetup(valueincq,null,null,null,false);
    }

    private void basicPutSetup(boolean valueincq, final String delim) throws Exception {
        basicPutSetup(valueincq,delim,null,null,false);
    }

    private void basicPutSetup(boolean valueincq,String delim, String auths, Authorizations defaultVis, boolean deletes) throws Exception {
        String tableName = UUID.randomUUID().toString();
        tableName=tableName.replace("-","a");
        if (null != defaultVis)
        accumulo.getConnector("root","password").securityOperations().changeUserAuthorizations("root",defaultVis);
        TestRunner runner = getTestRunner(tableName, DEFAULT_COLUMN_FAMILY);
        runner.setProperty(RecordIngest.CREATE_TABLE, "True");
        runner.setProperty(RecordIngest.DEFAULT_VISIBILITY, "");
        runner.setProperty(RecordIngest.DATA_NAME, "test");
        runner.setProperty(RecordIngest.EDGE_TYPES, "testedge");
        runner.setProperty("FROM.testedge","/ID");
        runner.setProperty("TO.testedge","/CODE");

        if (null != defaultVis){
            runner.setProperty(RecordIngest.DEFAULT_VISIBILITY, auths);
        }
        Set<Key> expectedKeys = generateTestData(runner,valueincq,delim, auths);
        runner.enqueue("Test".getBytes("UTF-8")); // This is to coax the processor into reading the data in the reader.l
        runner.run();

        List<MockFlowFile> results = runner.getFlowFilesForRelationship(RecordIngest.REL_SUCCESS);
        Assert.assertTrue("Wrong count", results.size() == 1);
        //verifyKey(tableName, expectedKeys, defaultVis);
        printKeys(tableName, expectedKeys, defaultVis);
        printKeys("graph", expectedKeys, defaultVis);
        if (deletes){
            runner.enqueue("Test".getBytes("UTF-8")); // This is to coax the processor into reading the data in the reader.l
            runner.run();
            runner.getFlowFilesForRelationship(RecordIngest.REL_SUCCESS);
            verifyKey(tableName, new HashSet<>(), defaultVis);
        }

    }




    @Test
    public void testByteEncodedPut() throws Exception {
        basicPutSetup(false);
    }

    @Test
    public void testByteEncodedPutThenDelete() throws Exception {
        basicPutSetup(true,null,"A&B",new Authorizations("A","B"),true);
    }


    @Test
    public void testByteEncodedPutCq() throws Exception {
        basicPutSetup(true);
    }

    @Test
    public void testByteEncodedPutCqDelim() throws Exception {
        basicPutSetup(true,"\u0000");
    }

    @Test
    public void testByteEncodedPutCqWithVis() throws Exception {
        basicPutSetup(true,null,"A&B",new Authorizations("A","B"),false);
    }
}
/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_CLIENT_BLOCK_WRITE_LOCATEFOLLOWINGBLOCK_RETRIES_KEY;
import static org.junit.Assert.*;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.DF;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeManager;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSOutputStream;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockManagerTestUtil;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.datanode.DataNodeTestUtils;

import static org.junit.Assert.assertTrue;


/**
 * This tests InterDataNodeProtocol for block handling.
 */
public class TestNamenodeCapacityReport {
  private static final Log LOG =
      LogFactory.getLog(TestNamenodeCapacityReport.class);

  /**
   * The following test first creates a file.
   * It verifies the block information from a datanode.
   * Then, it updates the block with new information and verifies again.
   */
  @Test
  public void testVolumeSize() throws Exception {
    Configuration conf = new HdfsConfiguration();
    MiniDFSCluster cluster = null;

    // Set aside fifth of the total capacity as reserved
    long reserved = 10000;
    conf.setLong(DFSConfigKeys.DFS_DATANODE_DU_RESERVED_KEY, reserved);
    
    try {
      cluster = new MiniDFSCluster.Builder(conf).build();
      cluster.waitActive();
      
      final FSNamesystem namesystem = cluster.getNamesystem();
      final DatanodeManager dm =
          cluster.getNamesystem().getBlockManager().getDatanodeManager();
      
      // Ensure the data reported for each data node is right
      final List<DatanodeDescriptor> live = new ArrayList<>();
      final List<DatanodeDescriptor> dead = new ArrayList<>();
      dm.fetchDatanodes(live, dead, false);
      
      assertTrue(live.size() == 1);
      
      long used, remaining, configCapacity, nonDFSUsed, bpUsed;
      float percentUsed, percentRemaining, percentBpUsed;
      
      for (final DatanodeDescriptor datanode : live) {
        used = datanode.getDfsUsed();
        remaining = datanode.getRemaining();
        nonDFSUsed = datanode.getNonDfsUsed();
        configCapacity = datanode.getCapacity();
        percentUsed = datanode.getDfsUsedPercent();
        percentRemaining = datanode.getRemainingPercent();
        bpUsed = datanode.getBlockPoolUsed();
        percentBpUsed = datanode.getBlockPoolUsedPercent();
        
        LOG.info("Datanode configCapacity " + configCapacity + " used " + used +
            " non DFS used " + nonDFSUsed + " remaining " + remaining +
            " perentUsed " + percentUsed + " percentRemaining " +
            percentRemaining);
        
        assertTrue(configCapacity == (used + remaining + nonDFSUsed));
        assertTrue(percentUsed == DFSUtil.getPercentUsed(used, configCapacity));
        assertTrue(percentRemaining ==
            DFSUtil.getPercentRemaining(remaining, configCapacity));
        assertTrue(
            percentBpUsed == DFSUtil.getPercentUsed(bpUsed, configCapacity));
      }
      
      DF df = new DF(new File(cluster.getDataDirectory()), conf);

      //
      // Currently two data directories are created by the data node
      // in the MiniDFSCluster. This results in each data directory having
      // capacity equals to the disk capacity of the data directory.
      // Hence the capacity reported by the data node is twice the disk space
      // the disk capacity
      //
      // So multiply the disk capacity and reserved space by two 
      // for accommodating it
      //
      int numOfDataDirs = 2;
      
      long diskCapacity = numOfDataDirs * df.getCapacity();
      reserved *= numOfDataDirs;
      
      configCapacity = namesystem.getCapacityTotal();
      used = namesystem.getCapacityUsed();
      nonDFSUsed = namesystem.getNonDfsUsedSpace();
      remaining = namesystem.getCapacityRemaining();
      percentUsed = namesystem.getPercentUsed();
      percentRemaining = namesystem.getPercentRemaining();
      bpUsed = namesystem.getBlockPoolUsedSpace();
      percentBpUsed = namesystem.getPercentBlockPoolUsed();
      
      LOG.info("Data node directory " + cluster.getDataDirectory());

      LOG.info("Name node diskCapacity " + diskCapacity + " configCapacity " +
          configCapacity + " reserved " + reserved + " used " + used +
          " remaining " + remaining + " nonDFSUsed " + nonDFSUsed +
          " remaining " + remaining + " percentUsed " + percentUsed +
          " percentRemaining " + percentRemaining + " bpUsed " + bpUsed +
          " percentBpUsed " + percentBpUsed);
      
      // Ensure new total capacity reported excludes the reserved space
      assertTrue(configCapacity == diskCapacity - reserved);
      
      // Ensure new total capacity reported excludes the reserved space
      assertTrue(configCapacity == (used + remaining + nonDFSUsed));

      // Ensure percent used is calculated based on used and present capacity
      assertTrue(percentUsed == DFSUtil.getPercentUsed(used, configCapacity));

      // Ensure percent used is calculated based on used and present capacity
      assertTrue(
          percentBpUsed == DFSUtil.getPercentUsed(bpUsed, configCapacity));

      // Ensure percent used is calculated based on used and present capacity
      assertTrue(percentRemaining ==
          ((float) remaining * 100.0f) / (float) configCapacity);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }
  
  private static final float EPSILON = 0.0001f;
  @Test
  public void testXceiverCount() throws Exception {
    Configuration conf = new HdfsConfiguration();
    // retry one time, if close fails
    conf.setInt(DFS_CLIENT_BLOCK_WRITE_LOCATEFOLLOWINGBLOCK_RETRIES_KEY, 1);
    conf.setInt(DFSConfigKeys.DFS_BLOCK_FETCHER_BUCKETS_PER_THREAD, 1000);
    MiniDFSCluster cluster = null;
    final int nodes = 8;
    final int fileCount = 5;
    final short fileRepl = 3;
    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(nodes).build();
      cluster.waitActive();
      final FSNamesystem namesystem = cluster.getNamesystem();
      final DatanodeManager dnm = namesystem.getBlockManager().getDatanodeManager();
      List<DataNode> datanodes = cluster.getDataNodes();
      final DistributedFileSystem fs = cluster.getFileSystem();
      // trigger heartbeats in case not already sent
      triggerHeartbeats(datanodes);
      
      // check that all nodes are live and in service
      int expectedTotalLoad = nodes;  // xceiver server adds 1 to load
      int expectedInServiceNodes = nodes;
      int expectedInServiceLoad = nodes;
      checkClusterHealth(nodes, namesystem, expectedTotalLoad, expectedInServiceNodes, expectedInServiceLoad);
      
      // shutdown half the nodes and force a heartbeat check to ensure
      // counts are accurate
      for (int i=0; i < nodes/2; i++) {
        DataNode dn = datanodes.get(i);
        DatanodeDescriptor dnd = dnm.getDatanode(dn.getDatanodeId());
        dn.shutdown();
        dnd.setLastUpdate(0L);
        BlockManagerTestUtil.checkHeartbeat(namesystem.getBlockManager());
        //Verify decommission of dead node won't impact nodesInService metrics.
        dnm.getDecomManager().startDecommission(dnd);
        expectedInServiceNodes--;
        assertEquals(expectedInServiceNodes, namesystem.getNumLiveDataNodes());
        assertEquals(expectedInServiceNodes, getNumDNInService(namesystem));
        //Verify recommission of dead node won't impact nodesInService metrics.
        dnm.getDecomManager().stopDecommission(dnd);
        assertEquals(expectedInServiceNodes, getNumDNInService(namesystem));
      }
      // restart the nodes to verify that counts are correct after
      // node re-registration 
      cluster.restartDataNodes();
      cluster.waitActive();
      datanodes = cluster.getDataNodes();
      expectedInServiceNodes = nodes;
      assertEquals(nodes, datanodes.size());
      checkClusterHealth(nodes, namesystem, expectedTotalLoad, expectedInServiceNodes, expectedInServiceLoad);
      
      // create streams and hsync to force datastreamers to start
      DFSOutputStream[] streams = new DFSOutputStream[fileCount];
      for (int i=0; i < fileCount; i++) {
        streams[i] = (DFSOutputStream)fs.create(new Path("/f"+i), fileRepl)
            .getWrappedStream();
        streams[i].write("1".getBytes());
        streams[i].hsync();
        // the load for writers is 2 because both the write xceiver & packet
        // responder threads are counted in the load
        expectedTotalLoad += 2*fileRepl;
        expectedInServiceLoad += 2*fileRepl;
      }
      // force nodes to send load update
      triggerHeartbeats(datanodes);
      checkClusterHealth(nodes, namesystem, expectedTotalLoad, expectedInServiceNodes, expectedInServiceLoad);

      // decomm a few nodes, substract their load from the expected load,
      // trigger heartbeat to force load update
      for (int i=0; i < fileRepl; i++) {
        expectedInServiceNodes--;
        DatanodeDescriptor dnd =
            dnm.getDatanode(datanodes.get(i).getDatanodeId());
        expectedInServiceLoad -= dnd.getXceiverCount();
        dnm.getDecomManager().startDecommission(dnd);
        DataNodeTestUtils.triggerHeartbeat(datanodes.get(i));
        Thread.sleep(100);
        checkClusterHealth(nodes, namesystem, expectedTotalLoad, expectedInServiceNodes, expectedInServiceLoad);
      }
      
      // check expected load while closing each stream.  recalc expected
      // load based on whether the nodes in the pipeline are decomm
      for (int i=0; i < fileCount; i++) {
        int decomm = 0;
        for (DatanodeInfo dni : streams[i].getPipeline()) {
          DatanodeDescriptor dnd = dnm.getDatanode(dni);
          expectedTotalLoad -= 2;
          if (dnd.isDecommissionInProgress() || dnd.isDecommissioned()) {
            decomm++;
          } else {
            expectedInServiceLoad -= 2;
          }
        }
        try {
          streams[i].close();
        } catch (IOException ioe) {
          // nodes will go decommissioned even if there's a UC block whose
          // other locations are decommissioned too.  we'll ignore that
          // bug for now
          if (decomm < fileRepl) {
            throw ioe;
          }
        }
        triggerHeartbeats(datanodes);
        // verify node count and loads 
        checkClusterHealth(nodes, namesystem, expectedTotalLoad, expectedInServiceNodes, expectedInServiceLoad);
      }
      // shutdown each node, verify node counts based on decomm state
      for (int i=0; i < nodes; i++) {
        DataNode dn = datanodes.get(i);
        dn.shutdown();
        // force it to appear dead so live count decreases
        DatanodeDescriptor dnDesc = dnm.getDatanode(dn.getDatanodeId());
        dnDesc.setLastUpdate(0L);
        BlockManagerTestUtil.checkHeartbeat(namesystem.getBlockManager());
        assertEquals(nodes-1-i, namesystem.getNumLiveDataNodes());
        // first few nodes are already out of service
        if (i >= fileRepl) {
          expectedInServiceNodes--;
        }
        assertEquals(expectedInServiceNodes, getNumDNInService(namesystem));
        
        // live nodes always report load of 1.  no nodes is load 0
        double expectedXceiverAvg = (i == nodes-1) ? 0.0 : 1.0;
        assertEquals((double)expectedXceiverAvg,
            getInServiceXceiverAverage(namesystem), EPSILON);
      }
      
      // final sanity check
      checkClusterHealth(0, namesystem, 0.0, 0, 0.0);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  private static void checkClusterHealth(
    int numOfLiveNodes,
    FSNamesystem namesystem, double expectedTotalLoad,
    int expectedInServiceNodes, double expectedInServiceLoad) {

    assertEquals(numOfLiveNodes, namesystem.getNumLiveDataNodes());
    assertEquals(expectedInServiceNodes, getNumDNInService(namesystem));
    assertEquals(expectedTotalLoad, namesystem.getTotalLoad(), EPSILON);
    if (expectedInServiceNodes != 0) {
      assertEquals(expectedInServiceLoad / expectedInServiceNodes,
        getInServiceXceiverAverage(namesystem), EPSILON);
    } else {
      assertEquals(0.0, getInServiceXceiverAverage(namesystem), EPSILON);
    }
  }

  private static int getNumDNInService(FSNamesystem fsn) {
    return fsn.getBlockManager().getDatanodeManager().getFSClusterStats()
      .getNumDatanodesInService();
  }

  private static double getInServiceXceiverAverage(FSNamesystem fsn) {
    return fsn.getBlockManager().getDatanodeManager().getFSClusterStats()
      .getInServiceXceiverAverage();
  }

  private void triggerHeartbeats(List<DataNode> datanodes)
      throws IOException, InterruptedException {
    for (DataNode dn : datanodes) {
      DataNodeTestUtils.triggerHeartbeat(dn);
    }
    Thread.sleep(100);
  }
}

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
package org.apache.hadoop.hdfs.server.blockmanagement;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Lists;
import io.hops.common.INodeUtil;
import io.hops.exception.StorageException;
import io.hops.metadata.HdfsStorageFactory;
import io.hops.metadata.hdfs.dal.INodeDataAccess;
import io.hops.metadata.hdfs.dal.ReplicaDataAccess;
import io.hops.metadata.hdfs.entity.INodeIdentifier;
import io.hops.security.GroupAlreadyExistsException;
import io.hops.security.UserAlreadyExistsException;
import io.hops.security.UserAlreadyInGroupException;
import io.hops.security.UsersGroups;
import io.hops.transaction.handler.HDFSOperationType;
import io.hops.transaction.handler.HopsTransactionalRequestHandler;
import io.hops.transaction.handler.LightWeightRequestHandler;
import io.hops.transaction.lock.LockFactory;
import io.hops.transaction.lock.TransactionLockTypes.INodeLockType;
import io.hops.transaction.lock.TransactionLocks;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor.BlockTargetPair;
import org.apache.hadoop.hdfs.server.datanode.DataNodeTestUtils;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.INode;
import org.apache.hadoop.hdfs.server.namenode.INodeDirectory;
import org.apache.hadoop.hdfs.server.namenode.INodeFile;
import org.apache.hadoop.hdfs.server.protocol.BlockReport;
import org.apache.hadoop.hdfs.server.protocol.DatanodeRegistration;
import org.apache.hadoop.hdfs.server.protocol.DatanodeStorage;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.net.NetworkTopology;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.hops.transaction.lock.LockFactory.BLK;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_SUBTREE_EXECUTOR_LIMIT_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_SUBTREE_EXECUTOR_LIMIT_KEY;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.server.namenode.NameNodeAdapter;
import org.apache.hadoop.test.GenericTestUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

public class TestBlockManager {
  static final Log LOG = LogFactory.getLog(TestBlockManager.class);
  
  private DatanodeStorageInfo[] storages;
  private List<DatanodeDescriptor> nodes;
  private List<DatanodeDescriptor> rackA;
  private List<DatanodeDescriptor> rackB;

  /**
   * Some of these tests exercise code which has some randomness involved -
   * ie even if there's a bug, they may pass because the random node selection
   * chooses the correct result.
   * <p/>
   * Since they're true unit tests and run quickly, we loop them a number
   * of times trying to trigger the incorrect behavior.
   */
  private static final int NUM_TEST_ITERS = 30;
  
  private static final int BLOCK_SIZE = 64*1024;

  private FSNamesystem fsn;
  static private BlockManager bm;
  private int numBuckets;

  static private final String USER = "user";
  static private final String GROUP = "grp";

  @Before
  public void setupMockCluster() throws IOException {
    Configuration conf = new HdfsConfiguration();
    conf.set(DFSConfigKeys.NET_TOPOLOGY_SCRIPT_FILE_NAME_KEY, "need to set a dummy value here so it assumes a multi-rack cluster");
    HdfsStorageFactory.setConfiguration(conf);
    ExecutorService subTreeOpsPool = Executors.newFixedThreadPool(
        conf.getInt(DFS_SUBTREE_EXECUTOR_LIMIT_KEY,
            DFS_SUBTREE_EXECUTOR_LIMIT_DEFAULT));
    fsn = Mockito.mock(FSNamesystem.class);
    Mockito.doReturn(subTreeOpsPool).when(fsn).getFSOperationsExecutor();
    HdfsStorageFactory.resetDALInitialized();
    HdfsStorageFactory.setConfiguration(conf);
    formatStorage(conf);
    bm = new BlockManager(fsn, conf);
    final String[] racks = {
        "/rackA",
        "/rackA",
        "/rackA",
        "/rackB",
        "/rackB",
        "/rackB"};
    storages = DFSTestUtil.createDatanodeStorageInfos(racks);
    nodes = Arrays.asList(DFSTestUtil.toDatanodeDescriptor(storages));

    rackA = nodes.subList(0, 3);
    rackB = nodes.subList(3, 6);
    numBuckets = conf.getInt(DFSConfigKeys.DFS_NUM_BUCKETS_KEY, DFSConfigKeys
        .DFS_NUM_BUCKETS_DEFAULT);

    DFSTestUtil.createRootFolder();
  }

  private void formatStorage(Configuration conf) throws IOException {
    HdfsStorageFactory.formatStorage();
    UsersGroups.createSyncRow();
    try {
      UsersGroups.addUser(USER);
    } catch (UserAlreadyExistsException e) {}
    try {
      UsersGroups.addGroup(GROUP);
    } catch (GroupAlreadyExistsException e ){}
    try {
      UsersGroups.addUserToGroup(USER, GROUP);
    } catch (UserAlreadyInGroupException e) {}
  }

  private void addNodes(Iterable<DatanodeDescriptor> nodesToAdd)
      throws IOException {
    NetworkTopology cluster = bm.getDatanodeManager().getNetworkTopology();
    // construct network topology
    for (DatanodeDescriptor dn : nodesToAdd) {
      cluster.add(dn);
      dn.getStorageInfos()[0].setUtilizationForTesting(
          2 * HdfsConstants.MIN_BLOCKS_FOR_WRITE*BLOCK_SIZE, 0L,
          2 * HdfsConstants.MIN_BLOCKS_FOR_WRITE*BLOCK_SIZE, 0L);
      dn.updateHeartbeat(
          BlockManagerTestUtil.getStorageReportsForDatanode(dn), 0L, 0L, 0, 0,
          null);
      bm.getDatanodeManager().checkIfClusterIsNowMultiRack(dn);
      bm.getDatanodeManager().addDnToStorageMapInDB(dn);
      bm.getDatanodeManager().addDatanode(dn);
    }
  }

  private void removeNode(DatanodeDescriptor deadNode) throws IOException {
    NetworkTopology cluster = bm.getDatanodeManager().getNetworkTopology();
    cluster.remove(deadNode);
    bm.datanodeRemoved(deadNode, false);
  }


  /**
   * Test that replication of under-replicated blocks is detected
   * and basically works
   */
  @Test
  public void testBasicReplication() throws Exception {
    addNodes(nodes);

    for (int i = 0; i < NUM_TEST_ITERS; i++) {
      doBasicTest(i);
    }
  }
  
  private void doBasicTest(int testIndex) throws IOException {
    List<DatanodeStorageInfo> origStorages = getStorages(0, 1);
    List<DatanodeDescriptor> origNodes = getNodes(origStorages);
    BlockInfoContiguous blockInfo = addBlockOnNodes((long) testIndex, origNodes);

    DatanodeStorageInfo[] pipeline = scheduleSingleReplication(blockInfo);

    assertTrue("Source of replication should be one of the nodes the block " +
            "was on (" + origStorages.toString() + "), but" +
            "was on: " + pipeline[0], origStorages.contains(pipeline[0]));

    assertTrue("Destination of replication should be on the other rack. " +
        "Was: " + pipeline[1], rackB.contains(pipeline[1].getDatanodeDescriptor()));
  }
  

  /**
   * Regression test for HDFS-1480
   * - Cluster has 2 racks, A and B, each with three nodes.
   * - Block initially written on A1, A2, B1
   * - Admin decommissions two of these nodes (let's say A1 and A2 but it
   * doesn't matter)
   * - Re-replication should respect rack policy
   */
  @Test
  public void testTwoOfThreeNodesDecommissioned() throws Exception {
    addNodes(nodes);
    for (int i = 0; i < NUM_TEST_ITERS; i++) {
      doTestTwoOfThreeNodesDecommissioned(i);
    }
  }
  
  private void doTestTwoOfThreeNodesDecommissioned(int testIndex)
      throws Exception {
    // Block originally on A1, A2, B1
    List<DatanodeStorageInfo> origStorages = getStorages(0, 1, 3);
    List<DatanodeDescriptor> origNodes = getNodes(origStorages);
    BlockInfoContiguous blockInfo = addBlockOnNodes(testIndex, origNodes);
    
    // Decommission two of the nodes (A1, A2)
    List<DatanodeDescriptor> decomNodes = startDecommission(0, 1);

    DatanodeStorageInfo[] pipeline = scheduleSingleReplication(blockInfo);
    assertTrue("Source of replication should be one of the nodes the block " +
            "was on(" + origStorages.toString() + "). " +
            "Was: " + pipeline[0], origStorages.contains(pipeline[0]));
    assertEquals("Should have three targets", 3, pipeline.length);
    
    boolean foundOneOnRackA = false;
    for (int i = 1; i < pipeline.length; i++) {
      DatanodeDescriptor target = pipeline[i].getDatanodeDescriptor();
      if (rackA.contains(target)) {
        foundOneOnRackA = true;
      }
      assertFalse(decomNodes.contains(target));
      assertFalse(origNodes.contains(target));
    }
    
    assertTrue("Should have at least one target on rack A. Pipeline: " +
            Joiner.on(",").join(pipeline), foundOneOnRackA);
  }
  

  /**
   * Test what happens when a block is on three nodes, and all three of those
   * nodes are decommissioned. It should properly re-replicate to three new
   * nodes.
   */
  @Test
  public void testAllNodesHoldingReplicasDecommissioned() throws Exception {
    addNodes(nodes);
    for (int i = 0; i < NUM_TEST_ITERS; i++) {
      doTestAllNodesHoldingReplicasDecommissioned(i);
    }
  }

  private void doTestAllNodesHoldingReplicasDecommissioned(int testIndex)
      throws Exception {
    // Block originally on A1, A2, B1
    List<DatanodeStorageInfo> origStorages = getStorages(0, 1, 3);
    List<DatanodeDescriptor> origNodes = getNodes(origStorages);
    BlockInfoContiguous blockInfo = addBlockOnNodes(testIndex, origNodes);
    
    // Decommission all of the nodes
    List<DatanodeDescriptor> decomNodes = startDecommission(0, 1, 3);

    DatanodeStorageInfo[] pipeline = scheduleSingleReplication(blockInfo);
    assertTrue("Source of replication should be one of the nodes the block " +
            "was on. Was: " + pipeline[0], origStorages.contains(pipeline[0]));
    assertEquals("Should have three targets", 4, pipeline.length);
    
    boolean foundOneOnRackA = false;
    boolean foundOneOnRackB = false;
    for (int i = 1; i < pipeline.length; i++) {
      DatanodeDescriptor target = pipeline[i].getDatanodeDescriptor();
      if (rackA.contains(target)) {
        foundOneOnRackA = true;
      } else if (rackB.contains(target)) {
        foundOneOnRackB = true;
      }
      assertFalse(decomNodes.contains(target));
      assertFalse(origNodes.contains(target));
    }
    
    assertTrue("Should have at least one target on rack A. Pipeline: " +
            Joiner.on(",").join(pipeline), foundOneOnRackA);
    assertTrue("Should have at least one target on rack B. Pipeline: " +
            Joiner.on(",").join(pipeline), foundOneOnRackB);
  }

  /**
   * Test what happens when there are two racks, and an entire rack is
   * decommissioned.
   * <p/>
   * Since the cluster is multi-rack, it will consider the block
   * under-replicated rather than create a third replica on the
   * same rack. Adding a new node on a third rack should cause re-replication
   * to that node.
   */
  @Test
  public void testOneOfTwoRacksDecommissioned() throws Exception {
    addNodes(nodes);
    for (int i = 0; i < NUM_TEST_ITERS; i++) {
      doTestOneOfTwoRacksDecommissioned(i);
    }
  }

  private void doTestOneOfTwoRacksDecommissioned(int testIndex)
      throws Exception {
    // Block originally on A1, A2, B1
    List<DatanodeStorageInfo> origStorages = getStorages(0, 1, 3);
    List<DatanodeDescriptor> origNodes = getNodes(origStorages);
    BlockInfoContiguous blockInfo = addBlockOnNodes(testIndex, origNodes);
    
    // Decommission all of the nodes in rack A
    List<DatanodeDescriptor> decomNodes = startDecommission(0, 1, 2);

    DatanodeStorageInfo[] pipeline = scheduleSingleReplication(blockInfo);
    assertTrue("Source of replication should be one of the nodes the block " + "was on. Was: " + pipeline[0],
        origStorages.contains(pipeline[0]));
    // Only up to two nodes can be picked per rack when there are two racks.
    assertEquals("Should have two targets", 2, pipeline.length);

    boolean foundOneOnRackB = false;
    for (int i = 1; i < pipeline.length; i++) {
      DatanodeDescriptor target = pipeline[i].getDatanodeDescriptor();
      if (rackB.contains(target)) {
        foundOneOnRackB = true;
      }
      assertFalse(decomNodes.contains(target));
      assertFalse(origNodes.contains(target));
    }

    assertTrue("Should have at least one target on rack B. Pipeline: " + Joiner.on(",").join(pipeline), foundOneOnRackB);

    // Mark the block as received on the target nodes in the pipeline
    fulfillPipeline(blockInfo, pipeline);

    // the block is still under-replicated. Add a new node. This should allow
    // the third off-rack replica.
    DatanodeDescriptor rackCNode = DFSTestUtil.getDatanodeDescriptor("7.7.7.7", "/rackC");
    rackCNode.updateStorage(new DatanodeStorage(DatanodeStorage.generateUuid()));
    addNodes(ImmutableList.of(rackCNode));
    try {
      DatanodeStorageInfo[] pipeline2 = scheduleSingleReplication(blockInfo);
      assertEquals(2, pipeline2.length);
      assertEquals(rackCNode, pipeline2[1].getDatanodeDescriptor());
    } finally {
      removeNode(rackCNode);
    }
  }

  /**
   * Unit test version of testSufficientlyReplBlocksUsesNewRack from
   * {@link TestBlocksWithNotEnoughRacks}.
   */
  @Test
  public void testSufficientlyReplBlocksUsesNewRack() throws Exception {
    addNodes(nodes);
    for (int i = 0; i < NUM_TEST_ITERS; i++) {
      doTestSufficientlyReplBlocksUsesNewRack(i);
    }
  }

  private void doTestSufficientlyReplBlocksUsesNewRack(int testIndex)
      throws IOException {
    // Originally on only nodes in rack A.
    List<DatanodeDescriptor> origNodes = rackA;
    BlockInfoContiguous blockInfo = addBlockOnNodes(testIndex, origNodes);
    DatanodeStorageInfo pipeline[] = scheduleSingleReplication(blockInfo);
    
    assertEquals(2, pipeline.length); // single new copy
    assertTrue("Source of replication should be one of the nodes the block " +
            "was on. Was: " + pipeline[0],
        origNodes.contains(pipeline[0].getDatanodeDescriptor()));
    assertTrue("Destination node of replication should be on the other rack. " +
        "Was: " + pipeline[1].getDatanodeDescriptor(),
        rackB.contains(pipeline[1].getDatanodeDescriptor()));
  }
  
  @Test
  public void testBlocksAreNotUnderreplicatedInSingleRack() throws Exception {
    List<DatanodeDescriptor> nodes = ImmutableList
        .of(BlockManagerTestUtil.getDatanodeDescriptor("1.1.1.1", "/rackA", true),
            BlockManagerTestUtil.getDatanodeDescriptor("2.2.2.2", "/rackA", true),
            BlockManagerTestUtil.getDatanodeDescriptor("3.3.3.3", "/rackA", true),
            BlockManagerTestUtil.getDatanodeDescriptor("4.4.4.4", "/rackA", true),
            BlockManagerTestUtil.getDatanodeDescriptor("5.5.5.5", "/rackA", true),
            BlockManagerTestUtil.getDatanodeDescriptor("6.6.6.6", "/rackA", true));
    addNodes(nodes);
    List<DatanodeDescriptor> origNodes = nodes.subList(0, 3);

    for (int i = 0; i < NUM_TEST_ITERS; i++) {
      doTestSingleRackClusterIsSufficientlyReplicated(i, origNodes);
    }
  }
  
  private void doTestSingleRackClusterIsSufficientlyReplicated(int testIndex,
      List<DatanodeDescriptor> origNodes) throws Exception {
    assertEquals(0, bm.numOfUnderReplicatedBlocks());
    addBlockOnNodes((long) testIndex, origNodes);
    bm.processMisReplicatedBlocks();
    assertEquals(0, bm.numOfUnderReplicatedBlocks());
  }
  
  
  /**
   * Tell the block manager that replication is completed for the given
   * pipeline.
   */
  private void fulfillPipeline(final BlockInfoContiguous blockInfo,
      DatanodeStorageInfo[] pipeline) throws IOException {
    HopsTransactionalRequestHandler handler =
        new HopsTransactionalRequestHandler(
            HDFSOperationType.FULFILL_PIPELINE) {
          INodeIdentifier inodeIdentifier;

          @Override
          public void setUp() throws StorageException {
            inodeIdentifier = INodeUtil.resolveINodeFromBlock(blockInfo);
          }

          @Override
          public void acquireLock(TransactionLocks locks) throws IOException {
            LockFactory lf = LockFactory.getInstance();
            locks.add(
                lf.getIndividualINodeLock(INodeLockType.WRITE, inodeIdentifier))
                .add(lf.getIndividualBlockLock(blockInfo.getBlockId(),
                    inodeIdentifier)).add(
                lf.getBlockRelated(BLK.RE, BLK.ER, BLK.CR, BLK.UR, BLK.UC,
                    BLK.PE, BLK.IV));
          }

          @Override
          public Object performTask() throws IOException {
            DatanodeStorageInfo storage = (DatanodeStorageInfo) getParams()[0];
            bm.addBlock(storage, blockInfo, null);
            return null;
          }
        };
    
    for (int i = 1; i < pipeline.length; i++) {
      handler.setParams(pipeline[i]).handle();
    }
  }

  static private BlockInfoContiguous blockOnNodes(final long blkId,
      final List<DatanodeDescriptor> nodes, final long inode_id)
      throws IOException {
    return (BlockInfoContiguous) new HopsTransactionalRequestHandler(
        HDFSOperationType.BLOCK_ON_NODES) {
      @Override
      public void acquireLock(TransactionLocks locks) throws IOException {
        LockFactory lf = LockFactory.getInstance();
        locks.add(
            lf.getIndividualBlockLock(blkId, new INodeIdentifier(inode_id)))
            .add(lf.getBlockRelated(BLK.RE));
      }

      @Override
      public Object performTask() throws IOException {
        Block block = new Block(blkId);
        BlockInfoContiguous blockInfo = new BlockInfoContiguous(block, inode_id);

        for (DatanodeDescriptor dn : nodes) {
          for (DatanodeStorageInfo storage : dn.getStorageInfos()) {
            blockInfo.addStorage(storage);
          }
        }
        return blockInfo;
      }
    }.handle();
  }

  private List<DatanodeDescriptor> getNodes(int ... indexes) {
    List<DatanodeDescriptor> ret = Lists.newArrayList();
    for (int idx : indexes) {
      ret.add(nodes.get(idx));
    }
    return ret;
  }

  private List<DatanodeDescriptor> getNodes(List<DatanodeStorageInfo> storages) {
    List<DatanodeDescriptor> ret = Lists.newArrayList();
    for (DatanodeStorageInfo s : storages) {
      ret.add(s.getDatanodeDescriptor());
    }
    return ret;
  }

  private List<DatanodeStorageInfo> getStorages(int... indexes) {
    List<DatanodeStorageInfo> ret = Lists.newArrayList();
    for (int idx : indexes) {
      ret.add(storages[idx]);
    }
    return ret;
  }
  
  private List<DatanodeDescriptor> startDecommission(int... indexes) {
    List<DatanodeDescriptor> nodes = getNodes(indexes);
    for (DatanodeDescriptor node : nodes) {
      node.startDecommission();
    }
    return nodes;
  }

  private BlockInfoContiguous addBlockOnNodes(final long blockId,
      List<DatanodeDescriptor> nodes) throws IOException {
    return addBlockOnNodes(blockId, nodes, 100);
  }
    
  static private BlockInfoContiguous addBlockOnNodes(final long blockId,
      List<DatanodeDescriptor> nodes, final int inodeId) throws IOException {

    LightWeightRequestHandler handle =
        new LightWeightRequestHandler(HDFSOperationType.TEST) {
          @Override
          public INodeFile performTask() throws IOException {
            INodeFile file = new INodeFile(inodeId, new PermissionStatus(USER, GROUP,
                new FsPermission((short) 0777)), null, (short) 3,
                System.currentTimeMillis(), System.currentTimeMillis(), 1000l, (byte) 0);
            file.setHasBlocksNoPersistance(true);
            file.setLocalNameNoPersistance("hop" + inodeId);
            file.setParentIdNoPersistance(INodeDirectory.ROOT_INODE_ID);
            file.setPartitionIdNoPersistance(INodeDirectory.ROOT_INODE_ID);
            List<INode> newed = new ArrayList<>();
            newed.add(file);
            INodeDataAccess da = (INodeDataAccess) HdfsStorageFactory
                .getDataAccess(INodeDataAccess.class);
            da.prepare(new ArrayList<INode>(), newed, new ArrayList<INode>());
            return file;
          }
        };

    final BlockCollection bc = (INodeFile) handle.handle();

    final BlockInfoContiguous blockInfo = blockOnNodes(blockId, nodes, inodeId);

    new HopsTransactionalRequestHandler(HDFSOperationType.BLOCK_ON_NODES) {
      INodeIdentifier inodeIdentifier;

      @Override
      public void setUp() throws StorageException, IOException {
        inodeIdentifier = INodeUtil.resolveINodeFromBlockID(blockId);
      }

      @Override
      public void acquireLock(TransactionLocks locks) throws IOException {
        LockFactory lf = LockFactory.getInstance();
        locks.add(lf.getIndividualBlockLock(blockId, inodeIdentifier));
      }

      @Override
      public Object performTask() throws StorageException, IOException {
        bm.blocksMap.addBlockCollection(blockInfo, bc);
        return null;
      }
    }.handle();
    
    return blockInfo;
  }

  private DatanodeStorageInfo[] scheduleSingleReplication(final BlockInfoContiguous block)
      throws IOException {
    final List<Block> list_p1 = new ArrayList<>();
    final List<List<Block>> list_all = new ArrayList<>();
    new HopsTransactionalRequestHandler(
        HDFSOperationType.SCHEDULE_SINGLE_REPLICATION) {
      INodeIdentifier inodeIdentifier;

      @Override
      public void setUp() throws StorageException {
        inodeIdentifier = INodeUtil.resolveINodeFromBlock(block);
      }

      @Override
      public void acquireLock(TransactionLocks locks) throws IOException {
        LockFactory lf = LockFactory.getInstance();
        locks.add(
            lf.getIndividualINodeLock(INodeLockType.WRITE, inodeIdentifier))
            .add(lf.getIndividualBlockLock(block.getBlockId(), inodeIdentifier))
            .add(lf.getBlockRelated(BLK.RE, BLK.ER, BLK.CR, BLK.UR, BLK.PE));
      }

      @Override
      public Object performTask() throws StorageException, IOException {
        // list for priority 1

        list_p1.add(block);

        // list of lists for each priority

        list_all.add(new ArrayList<Block>()); // for priority 0
        list_all.add(list_p1); // for priority 1

        assertEquals("Block not initially pending replication", 0,
            bm.pendingReplications.getNumReplicas(block));
        return null;
      }
    }.handle(fsn);

    assertEquals("computeReplicationWork should indicate replication is needed",
        1, bm.computeReplicationWorkForBlocks(list_all));

    return (DatanodeStorageInfo[]) new HopsTransactionalRequestHandler(
        HDFSOperationType.SCHEDULE_SINGLE_REPLICATION) {
      INodeIdentifier inodeIdentifier;

      @Override
      public void setUp() throws StorageException {
        inodeIdentifier = INodeUtil.resolveINodeFromBlock(block);
      }

      @Override
      public void acquireLock(TransactionLocks locks) throws IOException {
        LockFactory lf = LockFactory.getInstance();
        locks.add(
            lf.getIndividualINodeLock(INodeLockType.WRITE, inodeIdentifier))
            .add(lf.getIndividualBlockLock(block.getBlockId(), inodeIdentifier))
            .add(lf.getBlockRelated(BLK.RE, BLK.ER, BLK.CR, BLK.UR, BLK.PE));
      }

      @Override
      public Object performTask() throws IOException {

        assertTrue("replication is pending after work is computed",
            bm.pendingReplications.getNumReplicas(block) > 0);

        LinkedListMultimap<DatanodeStorageInfo, BlockTargetPair> repls =
            getAllPendingReplications();
        assertEquals(1, repls.size());
        Entry<DatanodeStorageInfo, BlockTargetPair> repl =
            repls.entries().iterator().next();

        DatanodeStorageInfo[] targets = repl.getValue().targets;

        DatanodeStorageInfo[] pipeline =
            new DatanodeStorageInfo[1 + targets.length];
        pipeline[0] = repl.getKey();
        System.arraycopy(targets, 0, pipeline, 1, targets.length);

        return pipeline;
      }
    }.handle(fsn);
  }

  private LinkedListMultimap<DatanodeStorageInfo, BlockTargetPair> getAllPendingReplications() {
    LinkedListMultimap<DatanodeStorageInfo, BlockTargetPair> repls =
        LinkedListMultimap.create();
    for (DatanodeDescriptor dn : nodes) {
      List<BlockTargetPair> thisRepls = dn.getReplicationCommand(10);
      if (thisRepls != null) {
        for(DatanodeStorageInfo storage : dn.getStorageInfos()) {
          repls.putAll(storage, thisRepls);
        }
      }
    }
    return repls;
  }

  /**
   * Test that a source node for a highest-priority replication is chosen even
   * if all available
   * source nodes have reached their replication limits.
   */
  @Test
  public void testHighestPriReplSrcChosenDespiteMaxReplLimit()
      throws Exception {
    Configuration conf = new HdfsConfiguration();
    HdfsStorageFactory.setConfiguration(conf);
    HdfsStorageFactory.resetDALInitialized();
    formatStorage(conf);
    bm.maxReplicationStreams = 0;
    bm.replicationStreamsHardLimit = 1;

    final long blockId = 42;         // arbitrary
    final Block aBlock = new Block(blockId, 0, 0);

    final List<DatanodeDescriptor> origNodes = getNodes(0, 1);
    
    addNodes(origNodes);
    // Add the block to the first node.
    addBlockOnNodes(blockId, origNodes.subList(0, 1));

    final List<DatanodeDescriptor> cntNodes = new LinkedList<>();
    final List<DatanodeStorageInfo> liveNodes = new LinkedList<>();
    
    
    new HopsTransactionalRequestHandler(HDFSOperationType.TEST) {
      INodeIdentifier inodeIdentifier;

      @Override
      public void setUp() throws StorageException {
        inodeIdentifier = INodeUtil.resolveINodeFromBlock(aBlock);
      }

      @Override
      public void acquireLock(TransactionLocks locks) throws IOException {
        LockFactory lf = LockFactory.getInstance();
        locks.add(
            lf.getIndividualINodeLock(INodeLockType.WRITE, inodeIdentifier))
            .add(
                lf.getIndividualBlockLock(aBlock.getBlockId(), inodeIdentifier))
            .add(lf.getBlockRelated(BLK.RE, BLK.ER, BLK.CR, BLK.UR, BLK.PE));
      }

      @Override
      public Object performTask() throws IOException {
        assertNotNull("Chooses source node for a highest-priority replication" +
                " even if all available source nodes have reached their replication" +
                " limits below the hard limit.",
            bm.chooseSourceDatanode(aBlock, cntNodes, liveNodes,
                new NumberReplicas(),
                UnderReplicatedBlocks.QUEUE_HIGHEST_PRIORITY));

        assertNull(
            "Does not choose a source node for a less-than-highest-priority" +
                " replication since all available source nodes have reached" +
                " their replication limits.",
            bm.chooseSourceDatanode(aBlock, cntNodes, liveNodes,
                new NumberReplicas(),
                UnderReplicatedBlocks.QUEUE_VERY_UNDER_REPLICATED));

        // Increase the replication count to test replication count > hard limit
        DatanodeStorageInfo targets[] = { origNodes.get(1).getStorageInfos()[0] };
        origNodes.get(0).addBlockToBeReplicated(aBlock, targets);

        assertNull("Does not choose a source node for a highest-priority" +
                " replication when all available nodes exceed the hard limit.",
            bm.chooseSourceDatanode(aBlock, cntNodes, liveNodes,
                new NumberReplicas(),
                UnderReplicatedBlocks.QUEUE_HIGHEST_PRIORITY));
        
        return null;
      }
    }.handle();
  }

  @Test
  public void testFavorDecomUntilHardLimit() throws Exception {
    Configuration conf = new HdfsConfiguration();
    HdfsStorageFactory.setConfiguration(conf);
    HdfsStorageFactory.resetDALInitialized();
    formatStorage(conf);
    bm.maxReplicationStreams = 0;
    bm.replicationStreamsHardLimit = 1;

    long blockId = 42;         // arbitrary
    final Block aBlock = new Block(blockId, 0, 0);
    final List<DatanodeDescriptor> origNodes = getNodes(0, 1);
    addNodes(origNodes);
    // Add the block to the first node.
    addBlockOnNodes(blockId,origNodes.subList(0,1));
    origNodes.get(0).startDecommission();

    final List<DatanodeDescriptor> cntNodes = new LinkedList<DatanodeDescriptor>();
    final List<DatanodeStorageInfo> liveNodes = new LinkedList<DatanodeStorageInfo>();

    new HopsTransactionalRequestHandler(HDFSOperationType.TEST) {
      INodeIdentifier inodeIdentifier;

      @Override
      public void setUp() throws StorageException {
        inodeIdentifier = INodeUtil.resolveINodeFromBlock(aBlock);
      }

      @Override
      public void acquireLock(TransactionLocks locks) throws IOException {
        LockFactory lf = LockFactory.getInstance();
        locks.add(
            lf.getIndividualINodeLock(INodeLockType.WRITE, inodeIdentifier))
            .add(
                lf.getIndividualBlockLock(aBlock.getBlockId(), inodeIdentifier))
            .add(lf.getBlockRelated(BLK.RE, BLK.ER, BLK.CR, BLK.UR, BLK.PE));
      }

      @Override
      public Object performTask() throws IOException {
        assertNotNull("Chooses decommissioning source node for a normal replication"
            + " if all available source nodes have reached their replication"
            + " limits below the hard limit.",
            bm.chooseSourceDatanode(
                aBlock,
                cntNodes,
                liveNodes,
                new NumberReplicas(),
                UnderReplicatedBlocks.QUEUE_UNDER_REPLICATED));

        // Increase the replication count to test replication count > hard limit
        DatanodeStorageInfo targets[] = {origNodes.get(1).getStorageInfos()[0]};
        origNodes.get(0).addBlockToBeReplicated(aBlock, targets);

        assertNull("Does not choose a source decommissioning node for a normal"
            + " replication when all available nodes exceed the hard limit.",
            bm.chooseSourceDatanode(
                aBlock,
                cntNodes,
                liveNodes,
                new NumberReplicas(),
                UnderReplicatedBlocks.QUEUE_UNDER_REPLICATED));
        return null;
      }
    }.handle();
  }



  @Test
  public void testSafeModeIBR() throws Exception {
    DatanodeDescriptor node = spy(nodes.get(0));
    DatanodeStorageInfo ds = node.getStorageInfos()[0];
    node.isAlive = true;

    DatanodeRegistration nodeReg =
        new DatanodeRegistration(node, null, null, "");

    // pretend to be in safemode
    doReturn(true).when(fsn).isInStartupSafeMode();

    // register new node
    bm.getDatanodeManager().registerDatanode(nodeReg);
    bm.getDatanodeManager().addDatanode(node); // swap in spy    
    assertEquals(node, bm.getDatanodeManager().getDatanode(node));
    assertEquals(0, ds.getBlockReportCount());
    // send block report, should be processed
    reset(node);

    bm.processReport(node, new DatanodeStorage(ds.getStorageID()),
        BlockReport.builder(numBuckets).build(), null, false);
    DatanodeStorage[] storages = {new DatanodeStorage(ds.getStorageID())};
    bm.blockReportCompleted(node, storages, true);
    assertEquals(1, ds.getBlockReportCount());
    // send block report again, should NOT be processed
    reset(node);
    bm.processReport(node, new DatanodeStorage(ds.getStorageID()),
        BlockReport.builder(numBuckets).build(), null, false);
    assertEquals(1, ds.getBlockReportCount());

    // re-register as if node restarted, should update existing node
    bm.getDatanodeManager().removeDatanode(node, false);
    reset(node);
    bm.getDatanodeManager().registerDatanode(nodeReg);
    verify(node).updateRegInfo(nodeReg);
    // send block report, should be processed after restart
    reset(node);
    bm.processReport(node, new DatanodeStorage(ds.getStorageID()),
        BlockReport.builder(numBuckets).build(), null, false);
    bm.blockReportCompleted(node, storages, true);
    // Reinitialize as registration with empty storage list pruned
    // node.storageMap.
    ds = node.getStorageInfos()[0];
    assertEquals(1, ds.getBlockReportCount());
  }
  
  @Test
  public void testSafeModeIBRAfterIncremental() throws Exception {
    DatanodeDescriptor node = spy(nodes.get(0));
    DatanodeStorageInfo ds = node.getStorageInfos()[0];

    node.isAlive = true;

    DatanodeRegistration nodeReg =
        new DatanodeRegistration(node, null, null, "");

    // pretend to be in safemode
    doReturn(true).when(fsn).isInStartupSafeMode();

    // register new node
    bm.getDatanodeManager().registerDatanode(nodeReg);
    bm.getDatanodeManager().addDatanode(node); // swap in spy    
    assertEquals(node, bm.getDatanodeManager().getDatanode(node));
    assertEquals(0, ds.getBlockReportCount());
    // send block report while pretending to already have blocks
    reset(node);
    doReturn(1).when(node).numBlocks();
    bm.processReport(node, new DatanodeStorage(ds.getStorageID()),
        BlockReport.builder(numBuckets).build(), null, false);
    DatanodeStorage[] storages = {new DatanodeStorage(ds.getStorageID())};
    bm.blockReportCompleted(node, storages, true);
    assertEquals(1, ds.getBlockReportCount());
  }
  
  /**
   * Tests that a namenode doesn't choose a datanode with full disks to 
   * store blocks.
   * @throws Exception
   */
  @Test
  public void testStorageWithRemainingCapacity() throws Exception {
    final Configuration conf = new HdfsConfiguration();
    final MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).format(true).build();
    FileSystem fs = FileSystem.get(conf);
    Path file1 = null;
    try {
      cluster.waitActive();
      final FSNamesystem namesystem = cluster.getNamesystem();
      final String poolId = namesystem.getBlockPoolId();
      final DatanodeRegistration nodeReg =
        DataNodeTestUtils.getDNRegistrationForBP(cluster.getDataNodes().
        		get(0), poolId);
      final DatanodeDescriptor dd = NameNodeAdapter.getDatanode(namesystem,
    		  nodeReg);
      // By default, MiniDFSCluster will create 1 datanode with 2 storages.
      // Assigning 64k for remaining storage capacity and will 
      //create a file with 100k.
      for(DatanodeStorageInfo storage:  dd.getStorageInfos()) { 
    	  storage.setUtilizationForTesting(65536, 0, 65536, 0);
      }
      //sum of the remaining capacity of both the storages
      dd.setRemaining(131072);
      file1 = new Path("testRemainingStorage.dat");
      try {
        DFSTestUtil.createFile(fs, file1, 102400, 102400, 102400, (short)1,
        		0x1BAD5EED);
      }
      catch (RemoteException re) {
    	   GenericTestUtils.assertExceptionContains("nodes instead of "
    	  		+ "minReplication", re);
      }
    }
    finally {
      // Clean up
      assertTrue(fs.exists(file1));
      fs.delete(file1, true);
      assertTrue(!fs.exists(file1));
      cluster.shutdown();
    }
  }
  
  @Test
  public void testUseDelHint() throws IOException {
    DatanodeStorageInfo delHint = new DatanodeStorageInfo(
        DFSTestUtil.getLocalDatanodeDescriptor(), new DatanodeStorage("id"));
    List<DatanodeStorageInfo> moreThan1Racks = Arrays.asList(delHint);
    List<StorageType> excessTypes = new ArrayList<StorageType>();

    excessTypes.add(StorageType.DEFAULT);
    Assert.assertTrue(BlockManager.useDelHint(true, delHint, null,
        moreThan1Racks, excessTypes));
    excessTypes.remove(0);
    excessTypes.add(StorageType.SSD);
    Assert.assertFalse(BlockManager.useDelHint(true, delHint, null,
        moreThan1Racks, excessTypes));
  }
  
  @Test
  public void testRemoveBlocks() throws IOException, InterruptedException, ExecutionException {
    List<DatanodeDescriptor> testNodes = new ArrayList<>();
    testNodes.add(nodes.get(0));
    testNodes.add(nodes.get(1));

    ExecutorService executor = Executors.newFixedThreadPool(10);
    List<Future<Object>> futures = new ArrayList<>();
    long blockId = 0;
    List<Long> blockIds = new ArrayList<>();
    for (int i = 2; i < 4000 + 2; i++) {
      for (int j = 0; j < 2; j++) {
        blockIds.add(blockId);
        futures.add(executor.submit(new SliceRunner(blockId++, testNodes, i)));
      }
    }

    for (Future<Object> futur : futures) {
      futur.get();
    }

    executor.shutdown();

    executor.awaitTermination(10, TimeUnit.SECONDS);

    //check that the replicas info are present in the database
    for (int sid : testNodes.get(0).getSidsOnNode()) {
      checkNbReplicas(sid, blockIds.size());
    }
    for (int sid : testNodes.get(1).getSidsOnNode()) {
      checkNbReplicas(sid, blockIds.size());
    }
    bm.removeBlocks(blockIds, testNodes.get(0));

    //the replicas should have been removed from node 0 but not from node 1
    for (int sid : testNodes.get(0).getSidsOnNode()) {
      checkNbReplicas(sid, 0);
    }
    for (int sid : testNodes.get(1).getSidsOnNode()) {
      checkNbReplicas(sid, blockIds.size());
    }
  }
  
  private static class SliceRunner implements Callable<Object> {
    long blockId;
    List<DatanodeDescriptor> testNodes;
    int inodeId;
    
    public SliceRunner(long blockId, List<DatanodeDescriptor> testNodes, int inodeId) {
      this.blockId = blockId;
      this.testNodes = testNodes;
      this.inodeId = inodeId;
    }

    @Override
    public Object call() throws Exception{
      addBlockOnNodes(blockId++, testNodes, inodeId);
      return null;
    }
  }
 
  private void checkNbReplicas(final int sid, final int expected) throws IOException {
    new LightWeightRequestHandler(HDFSOperationType.TEST) {
      @Override
      public Object performTask() throws IOException {
        ReplicaDataAccess da = (ReplicaDataAccess) HdfsStorageFactory
            .getDataAccess(ReplicaDataAccess.class);
        assertEquals(expected, da.countAllReplicasForStorageId(sid));
        return null;
      }
    }.handle();
  }
}

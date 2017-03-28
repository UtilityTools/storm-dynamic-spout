package com.salesforce.storm.spout.sideline.persistence;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.salesforce.storm.spout.sideline.config.SidelineSpoutConfig;
import com.salesforce.storm.spout.sideline.kafka.ConsumerState;
import com.salesforce.storm.spout.sideline.trigger.SidelineRequest;
import com.salesforce.storm.spout.sideline.trigger.SidelineRequestIdentifier;
import com.salesforce.storm.spout.sideline.trigger.SidelineType;
import org.apache.curator.test.InstanceSpec;
import org.apache.curator.test.TestingServer;
import org.apache.kafka.common.TopicPartition;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests our Zookeeper Persistence layer.
 */
public class ZookeeperPersistenceManagerTest {
    /**
     * By default, no exceptions should be thrown.
     */
    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    // For logging within test.
    private static final Logger logger = LoggerFactory.getLogger(ZookeeperPersistenceManagerTest.class);

    // An internal zookeeper server used for testing.
    private static TestingServer zkServer;

    /**
     * This gets run before all test methods in class.
     * It stands up an internal zookeeper server that is shared for all test methods in this class.
     */
    @BeforeClass
    public static void setupZkServer() throws Exception {
        InstanceSpec zkInstanceSpec = new InstanceSpec(null, -1, -1, -1, true, -1, -1, 1000);
        zkServer = new TestingServer(zkInstanceSpec, true);
    }

    /**
     * After running all the test methods in this class, destroy our internal zk server.
     */
    @AfterClass
    public static void destroyZkServer() throws Exception {
        zkServer.stop();
        zkServer.close();
    }

    /**
     * Tests that if you're missing the configuration item for ZkRootNode it will throw
     * an IllegalStateException.
     */
    @Test
    public void testOpenMissingConfigForZkRootNode() {
        final List<String> inputHosts = Lists.newArrayList("localhost:2181", "localhost2:2183");

        // Create our config
        final Map topologyConfig = createDefaultConfig(inputHosts, null);

        // Create instance and open it.
        ZookeeperPersistenceManager persistenceManager = new ZookeeperPersistenceManager();

        expectedException.expect(IllegalStateException.class);
        persistenceManager.open(topologyConfig);
    }

    /**
     * Tests that the constructor does what we think.
     */
    @Test
    public void testOpen() {
        final String expectedZkConnectionString = "localhost:2181,localhost2:2183";
        final List<String> inputHosts = Lists.newArrayList("localhost:2181", "localhost2:2183");
        final String expectedZkRoot = getRandomZkRootNode();
        final String expectedConsumerId = "MyConsumerId";
        final String expectedZkConsumerStatePath = expectedZkRoot + "/consumers/" + expectedConsumerId;
        final String expectedZkRequestStatePath = expectedZkRoot + "/requests/" + expectedConsumerId;

        // Create our config
        final Map topologyConfig = createDefaultConfig(inputHosts, expectedZkRoot);

        // Create instance and open it.
        ZookeeperPersistenceManager persistenceManager = new ZookeeperPersistenceManager();
        persistenceManager.open(topologyConfig);

        // Validate
        assertEquals("Unexpected zk connection string", expectedZkConnectionString, persistenceManager.getZkConnectionString());
        assertEquals("Unexpected zk root string", expectedZkRoot, persistenceManager.getZkRoot());

        // Validate that getZkXXXXStatePath returns the expected value
        assertEquals("Unexpected zkConsumerStatePath returned", expectedZkConsumerStatePath, persistenceManager.getZkConsumerStatePath(expectedConsumerId));
        assertEquals("Unexpected zkRequestStatePath returned", expectedZkRequestStatePath, persistenceManager.getZkRequestStatePath(expectedConsumerId));

        // Close everyone out
        persistenceManager.close();
    }

    /**
     * Does an end to end test of this persistence layer for storing/retrieving Consumer state.
     * 1 - Sets up an internal Zk server
     * 2 - Connects to it
     * 3 - writes state data to it
     * 4 - reads state data from it
     * 5 - compares that its valid.
     */
    @Test
    public void testEndToEndConsumerStatePersistence() throws InterruptedException {
        final String topicName = "MyTopic";
        final String zkRootPath = getRandomZkRootNode();
        final String consumerId = "myConsumer" + Clock.systemUTC().millis();

        // Create our config
        final Map topologyConfig = createDefaultConfig(zkServer.getConnectString(), zkRootPath);

        // Create instance and open it.
        ZookeeperPersistenceManager persistenceManager = new ZookeeperPersistenceManager();
        persistenceManager.open(topologyConfig);

        // Create state
        final ConsumerState consumerState = ConsumerState.builder()
            .withPartition(new TopicPartition(topicName, 0), 0L)
            .withPartition(new TopicPartition(topicName, 1), 100L)
            .withPartition(new TopicPartition(topicName, 3), 300L)
            .build();

        // Persist it
        logger.info("Persisting {}", consumerState);
        persistenceManager.persistConsumerState(consumerId, consumerState);

        // Attempt to read it?
        ConsumerState result = persistenceManager.retrieveConsumerState(consumerId);
        logger.info("Result {}", result);

        // Validate result
        assertNotNull("Got an object back", result);

        // Should have 3 entries
        assertEquals("Should have 3 entries", 3, result.size());
        assertTrue("Contains Partition 0", result.containsKey(new TopicPartition(topicName, 0)));
        assertEquals("Contains Partition 0 with value 0L", 0L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 0)));
        assertTrue("Contains Partition 1", result.containsKey(new TopicPartition(topicName, 1)));
        assertEquals("Contains Partition 1 with value 100L", 100L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 1)));
        assertTrue("Contains Partition 3", result.containsKey(new TopicPartition(topicName, 3)));
        assertEquals("Contains Partition 3 with value 300L", 300L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 3)));

        // Close outs
        persistenceManager.close();

        // Create new instance, reconnect to ZK, make sure we can still read it out with our new instance.
        persistenceManager = new ZookeeperPersistenceManager();
        persistenceManager.open(topologyConfig);

        // Re-retrieve, should still be there.
        // Attempt to read it?
        result = persistenceManager.retrieveConsumerState(consumerId);
        logger.info("Result {}", result);

        // Validate result
        assertNotNull("Got an object back", result);

        // Should have 3 entries
        assertEquals("Should have 3 entries", 3, result.size());
        assertTrue("Contains Partition 0", result.containsKey(new TopicPartition(topicName, 0)));
        assertEquals("Contains Partition 0 with value 0L", 0L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 0)));
        assertTrue("Contains Partition 1", result.containsKey(new TopicPartition(topicName, 1)));
        assertEquals("Contains Partition 1 with value 100L", 100L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 1)));
        assertTrue("Contains Partition 3", result.containsKey(new TopicPartition(topicName, 3)));
        assertEquals("Contains Partition 3 with value 300L", 300L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 3)));

        // Close outs
        persistenceManager.close();
    }

    /**
     * Tests storing state for a consumer, then updating that state and storing the updated entry.
     * Verifies that the entry is updated as we'd expect.
     */
    @Test
    public void testEndToEndConsumerStatePersistenceUpdatingEntryForSameConsumerId() throws InterruptedException {
        final String topicName = "MyTopic";
        final String zkRootPath = getRandomZkRootNode();
        final String consumerId = "myConsumer" + Clock.systemUTC().millis();

        // Create our config
        final Map topologyConfig = createDefaultConfig(zkServer.getConnectString(), zkRootPath);

        // Create instance and open it.
        ZookeeperPersistenceManager persistenceManager = new ZookeeperPersistenceManager();
        persistenceManager.open(topologyConfig);

        // Create state
        final ConsumerState consumerStateOriginal = ConsumerState.builder()
            .withPartition(new TopicPartition(topicName, 0), 0L)
            .withPartition(new TopicPartition(topicName, 1), 100L)
            .withPartition(new TopicPartition(topicName, 3), 300L)
            .build();

        // Persist it
        logger.info("Persisting {}", consumerStateOriginal);
        persistenceManager.persistConsumerState(consumerId, consumerStateOriginal);

        // Attempt to read it?
        ConsumerState result = persistenceManager.retrieveConsumerState(consumerId);
        logger.info("Result {}", result);

        // Validate result
        assertNotNull("Got an object back", result);

        // Should have 3 entries
        assertEquals("Should have 3 entries", 3, result.size());
        assertTrue("Contains Partition 0", result.containsKey(new TopicPartition(topicName, 0)));
        assertEquals("Contains Partition 0 with value 0L", 0L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 0)));
        assertTrue("Contains Partition 1", result.containsKey(new TopicPartition(topicName, 1)));
        assertEquals("Contains Partition 1 with value 100L", 100L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 1)));
        assertTrue("Contains Partition 3", result.containsKey(new TopicPartition(topicName, 3)));
        assertEquals("Contains Partition 3 with value 300L", 300L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 3)));

        // Now attempt to update the state
        final ConsumerState consumerStateUpdated = ConsumerState.builder()
            .withPartition(new TopicPartition(topicName, 0), 100L)
            .withPartition(new TopicPartition(topicName, 1), 120L)
            .withPartition(new TopicPartition(topicName, 3), 320L)
            .build();

        // Persisted the updated state
        persistenceManager.persistConsumerState(consumerId, consumerStateUpdated);

        // Attempt to read it?
        result = persistenceManager.retrieveConsumerState(consumerId);
        logger.info("Result {}", result);

        // Validate result
        assertNotNull("Got an object back", result);

        // Should have 3 entries
        assertEquals("Should have 3 entries", 3, result.size());
        assertTrue("Contains Partition 0", result.containsKey(new TopicPartition(topicName, 0)));
        assertEquals("Contains Partition 0 with value 100L", 100L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 0)));
        assertTrue("Contains Partition 1", result.containsKey(new TopicPartition(topicName, 1)));
        assertEquals("Contains Partition 1 with value 120L", 120L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 1)));
        assertTrue("Contains Partition 3", result.containsKey(new TopicPartition(topicName, 3)));
        assertEquals("Contains Partition 3 with value 320L", 320L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 3)));

        // Close outs
        persistenceManager.close();
    }

    /**
     * Tests end to end persistence of Consumer state, using an independent ZK client to verify things are written
     * into zookeeper as we expect.
     *
     * We do the following:
     * 1 - Connect to ZK and ensure that the zkRootNode path does NOT exist in Zookeeper yet
     *     If it does, we'll clean it up.
     * 2 - Create an instance of our state manager passing an expected root node
     * 3 - Attempt to persist some state
     * 4 - Go into zookeeper directly and verify the state got written under the appropriate prefix path (zkRootNode).
     * 5 - Read the stored value directly out of zookeeper and verify the right thing got written.
     */
    @Test
    public void testEndToEndConsumerStatePersistenceWithValidationWithIndependentZkClient() throws IOException, KeeperException, InterruptedException {
        // Define our ZK Root Node
        final String zkRootNodePath = getRandomZkRootNode();
        final String zkConsumersRootNodePath = zkRootNodePath + "/consumers";
        final String consumerId = "MyConsumer" + Clock.systemUTC().millis();

        // 1 - Connect to ZK directly
        ZooKeeper zookeeperClient = new ZooKeeper(zkServer.getConnectString(), 6000, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                logger.info("Got event {}", event);
            }
        });

        // Ensure that our node does not exist before we run test,
        // Validate that our assumption that this node does not exist!
        Stat doesNodeExist = zookeeperClient.exists(zkRootNodePath, false);
        // We need to clean up
        if (doesNodeExist != null) {
            zookeeperClient.delete(zkRootNodePath, doesNodeExist.getVersion());

            // Check again
            doesNodeExist = zookeeperClient.exists(zkRootNodePath, false);
            if (doesNodeExist != null) {
                throw new RuntimeException("Failed to ensure zookeeper was clean before running test");
            }
        }

        // 2. Create our instance and open it
        final Map topologyConfig = createDefaultConfig(zkServer.getConnectString(), zkRootNodePath);
        ZookeeperPersistenceManager persistenceManager = new ZookeeperPersistenceManager();
        persistenceManager.open(topologyConfig);

        // 3. Attempt to persist some state.
        final String topicName = "MyTopic";

        // Define our expected result that will be stored in zookeeper
        final String expectedStoredState = "{\""+topicName+"-0\":0,\""+topicName+"-1\":100,\""+topicName+"-3\":300}";

        final ConsumerState consumerState = ConsumerState.builder()
            .withPartition(new TopicPartition(topicName, 0), 0L)
            .withPartition(new TopicPartition(topicName, 1), 100L)
            .withPartition(new TopicPartition(topicName, 3), 300L)
            .build();

        // Persist it
        logger.info("Persisting {}", consumerState);
        persistenceManager.persistConsumerState(consumerId, consumerState);

        // Since this is an async operation, use await() to watch for the change
        await()
                .atMost(6, TimeUnit.SECONDS)
                .until(() -> {
                    return zookeeperClient.exists(zkConsumersRootNodePath, false);
                }, notNullValue());

        // 4. Go into zookeeper and see where data got written
        doesNodeExist = zookeeperClient.exists(zkConsumersRootNodePath, false);
        logger.debug("Result {}", doesNodeExist);
        assertNotNull("Our root node should now exist", doesNodeExist);

        // Now attempt to read our state
        List<String> childrenNodes = zookeeperClient.getChildren(zkConsumersRootNodePath, false);
        logger.debug("Children Node Names {}", childrenNodes);

        // We should have a single child
        assertEquals("Should have a single filter", 1, childrenNodes.size());

        // Grab the child node node
        final String childNodeName = childrenNodes.get(0);
        assertNotNull("Child Node Name should not be null", childNodeName);
        assertEquals("Child Node name not correct", consumerId, childNodeName);

        // 5. Grab the value and validate it
        final byte[] storedDataBytes = zookeeperClient.getData(zkConsumersRootNodePath + "/" + consumerId, false, null);
        logger.debug("Stored data bytes {}", storedDataBytes);
        assertNotEquals("Stored bytes should be non-zero", 0, storedDataBytes.length);

        // Convert to a string
        final String storedDataStr = new String(storedDataBytes, Charsets.UTF_8);
        logger.info("Stored data string {}", storedDataStr);
        assertNotNull("Stored data string should be non-null", storedDataStr);
        assertEquals("Got unexpected state", expectedStoredState, storedDataStr);

        // Test clearing state actually clears state.
        persistenceManager.clearConsumerState(consumerId);

        // Validate the node no longer exists
        // Since this is an async operation, use await() to watch for the change
        await()
                .atMost(6, TimeUnit.SECONDS)
                .until(() -> {
                    return zookeeperClient.exists(zkConsumersRootNodePath + "/" + consumerId, false);
                }, nullValue());

        // Close everyone out
        persistenceManager.close();
        zookeeperClient.close();
    }

    /**
     * Does an end to end test of this persistence layer for storing/retrieving request state.
     * 1 - Sets up an internal Zk server
     * 2 - Connects to it
     * 3 - writes state data to it
     * 4 - reads state data from it
     * 5 - compares that its valid.
     */
    @Test
    public void testEndToEndRequestStatePersistence() throws InterruptedException {
        final String topicName = "MyTopic1";
        final String zkRootPath = getRandomZkRootNode();
        final SidelineRequestIdentifier sidelineRequestIdentifier = new SidelineRequestIdentifier();
        final SidelineRequest sidelineRequest = new SidelineRequest(Collections.emptyList());

        // Create our config
        final Map topologyConfig = createDefaultConfig(zkServer.getConnectString(), zkRootPath);

        // Create instance and open it.
        ZookeeperPersistenceManager persistenceManager = new ZookeeperPersistenceManager();
        persistenceManager.open(topologyConfig);

        // Create state
        final ConsumerState consumerState = ConsumerState.builder()
            .withPartition(new TopicPartition(topicName, 0), 10L)
            .withPartition(new TopicPartition(topicName, 1), 1000L)
            .withPartition(new TopicPartition(topicName, 3), 3000L)
            .build();

        // Persist it
        logger.info("Persisting {}", consumerState);
        persistenceManager.persistSidelineRequestState(SidelineType.START, sidelineRequestIdentifier, sidelineRequest, consumerState, null);

        // Attempt to read it?
        ConsumerState result = persistenceManager.retrieveSidelineRequest(sidelineRequestIdentifier).startingState;
        logger.info("Result {}", result);

        // Validate result
        assertNotNull("Got an object back", result);

        // Should have 3 entries
        assertEquals("Should have 3 entries", 3, result.size());
        assertTrue("Contains Partition 0", result.containsKey(new TopicPartition(topicName, 0)));
        assertEquals("Contains Partition 0 with value 0L", 10L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 0)));
        assertTrue("Contains Partition 1", result.containsKey(new TopicPartition(topicName, 1)));
        assertEquals("Contains Partition 1 with value 100L", 1000L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 1)));
        assertTrue("Contains Partition 3", result.containsKey(new TopicPartition(topicName, 3)));
        assertEquals("Contains Partition 3 with value 300L", 3000L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 3)));

        // Close outs
        persistenceManager.close();

        // Create new instance, reconnect to ZK, make sure we can still read it out with our new instance.
        persistenceManager = new ZookeeperPersistenceManager();
        persistenceManager.open(topologyConfig);

        // Re-retrieve, should still be there.
        // Attempt to read it?
        result = persistenceManager.retrieveSidelineRequest(sidelineRequestIdentifier).startingState;
        logger.info("Result {}", result);

        // Validate result
        assertNotNull("Got an object back", result);

        // Should have 3 entries
        assertEquals("Should have 3 entries", 3, result.size());
        assertTrue("Contains Partition 0", result.containsKey(new TopicPartition(topicName, 0)));
        assertEquals("Contains Partition 0 with value 0L", 10L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 0)));
        assertTrue("Contains Partition 1", result.containsKey(new TopicPartition(topicName, 1)));
        assertEquals("Contains Partition 1 with value 100L", 1000L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 1)));
        assertTrue("Contains Partition 3", result.containsKey(new TopicPartition(topicName, 3)));
        assertEquals("Contains Partition 3 with value 300L", 3000L, (long) result.getOffsetForTopicAndPartition(new TopicPartition(topicName, 3)));

        // Close outs
        persistenceManager.close();
    }

    /**
     * Tests end to end persistence of Consumer state, using an independent ZK client to verify things are written
     * into zookeeper as we expect.
     *
     * We do the following:
     * 1 - Connect to ZK and ensure that the zkRootNode path does NOT exist in Zookeeper yet
     *     If it does, we'll clean it up.
     * 2 - Create an instance of our state manager passing an expected root node
     * 3 - Attempt to persist some state
     * 4 - Go into zookeeper directly and verify the state got written under the appropriate prefix path (zkRootNode).
     * 5 - Read the stored value directly out of zookeeper and verify the right thing got written.
     */
    @Test
    public void testEndToEndRequestStatePersistenceWithValidationWithIndependentZkClient() throws IOException, KeeperException, InterruptedException {
        // Define our ZK Root Node
        final String zkRootNodePath = getRandomZkRootNode();
        final String zkRequestsRootNodePath = zkRootNodePath + "/requests";
        final SidelineRequestIdentifier sidelineRequestIdentifier = new SidelineRequestIdentifier();
        final SidelineRequest sidelineRequest = new SidelineRequest(Collections.emptyList());

        // 1 - Connect to ZK directly
        ZooKeeper zookeeperClient = new ZooKeeper(zkServer.getConnectString(), 6000, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                logger.info("Got event {}", event);
            }
        });

        // Ensure that our node does not exist before we run test,
        // Validate that our assumption that this node does not exist!
        Stat doesNodeExist = zookeeperClient.exists(zkRootNodePath, false);
        // We need to clean up
        if (doesNodeExist != null) {
            zookeeperClient.delete(zkRootNodePath, doesNodeExist.getVersion());

            // Check again
            doesNodeExist = zookeeperClient.exists(zkRootNodePath, false);
            if (doesNodeExist != null) {
                throw new RuntimeException("Failed to ensure zookeeper was clean before running test");
            }
        }

        // 2. Create our instance and open it
        final Map topologyConfig = createDefaultConfig(zkServer.getConnectString(), zkRootNodePath);
        ZookeeperPersistenceManager persistenceManager = new ZookeeperPersistenceManager();
        persistenceManager.open(topologyConfig);

        // 3. Attempt to persist some state.
        final String topicName = "MyTopic";

        // Define our expected result that will be stored in zookeeper
        // TODO: I hate this, let's build the damn map...
        final String expectedStoredState = "{\"filterChainSteps\":\"rO0ABXNyAB9qYXZhLnV0aWwuQ29sbGVjdGlvbnMkRW1wdHlMaXN0ergXtDynnt4CAAB4cA==\",\"type\":\"START\",\"startingState\":{\""+topicName+"-0\":0,\""+topicName+"-1\":100,\""+topicName+"-3\":300}}";

        final ConsumerState consumerState = ConsumerState.builder()
            .withPartition(new TopicPartition(topicName, 0), 0L)
            .withPartition(new TopicPartition(topicName, 1), 100L)
            .withPartition(new TopicPartition(topicName, 3), 300L)
            .build();

        // Persist it
        logger.info("Persisting {}", consumerState);
        persistenceManager.persistSidelineRequestState(SidelineType.START, sidelineRequestIdentifier, sidelineRequest, consumerState, null);

        // Since this is an async operation, use await() to watch for the change
        await()
                .atMost(6, TimeUnit.SECONDS)
                .until(() -> {
                    return zookeeperClient.exists(zkRequestsRootNodePath, false);
                }, notNullValue());

        // 4. Go into zookeeper and see where data got written
        doesNodeExist = zookeeperClient.exists(zkRequestsRootNodePath, false);
        logger.debug("Result {}", doesNodeExist);
        assertNotNull("Our root node should now exist", doesNodeExist);

        // Now attempt to read our state
        List<String> childrenNodes = zookeeperClient.getChildren(zkRequestsRootNodePath, false);
        logger.debug("Children Node Names {}", childrenNodes);

        // We should have a single child
        assertEquals("Should have a single filter", 1, childrenNodes.size());

        // Grab the child node node
        final String childNodeName = childrenNodes.get(0);
        assertNotNull("Child Node Name should not be null", childNodeName);
        assertEquals("Child Node name not correct", sidelineRequestIdentifier.toString(), childNodeName);

        // 5. Grab the value and validate it
        final byte[] storedDataBytes = zookeeperClient.getData(zkRequestsRootNodePath + "/" + sidelineRequestIdentifier.toString(), false, null);
        logger.debug("Stored data bytes {}", storedDataBytes);
        assertNotEquals("Stored bytes should be non-zero", 0, storedDataBytes.length);

        // Convert to a string
        final String storedDataStr = new String(storedDataBytes, Charsets.UTF_8);
        logger.info("Stored data string {}", storedDataStr);
        assertNotNull("Stored data string should be non-null", storedDataStr);
        assertEquals("Got unexpected state", expectedStoredState, storedDataStr);

        // Now test clearing
        persistenceManager.clearSidelineRequest(sidelineRequestIdentifier);

        // Validate in the Zk Client.
        doesNodeExist = zookeeperClient.exists(zkRequestsRootNodePath + "/" + sidelineRequestIdentifier.toString(), false);
        logger.debug("Result {}", doesNodeExist);
        assertNull("Our root node should No longer exist", doesNodeExist);

        // Close everyone out
        persistenceManager.close();
        zookeeperClient.close();
    }

    /**
     * Verify we get an exception if you try to persist before calling open().
     */
    @Test
    public void testPersistConsumerStateBeforeBeingOpened() {
        // Create our instance
        ZookeeperPersistenceManager persistenceManager = new ZookeeperPersistenceManager();

        // Call method and watch for exception
        expectedException.expect(IllegalStateException.class);
        persistenceManager.persistConsumerState("MyConsumerId", ConsumerState.builder().build());
    }

    /**
     * Verify we get an exception if you try to retrieve before calling open().
     */
    @Test
    public void testRetrieveConsumerStateBeforeBeingOpened() {
        // Create our instance
        ZookeeperPersistenceManager persistenceManager = new ZookeeperPersistenceManager();

        // Call method and watch for exception
        expectedException.expect(IllegalStateException.class);
        persistenceManager.retrieveConsumerState("MyConsumerId");
    }

    /**
     * Verify we get an exception if you try to persist before calling open().
     */
    @Test
    public void testClearConsumerStateBeforeBeingOpened() {
        // Create our instance
        ZookeeperPersistenceManager persistenceManager = new ZookeeperPersistenceManager();

        // Call method and watch for exception
        expectedException.expect(IllegalStateException.class);
        persistenceManager.clearConsumerState("MyConsumerId");
    }

    /**
     * Verify we get an exception if you try to persist before calling open().
     */
    @Test
    public void testPersistSidelineRequestStateBeforeBeingOpened() {
        // Create our instance
        ZookeeperPersistenceManager persistenceManager = new ZookeeperPersistenceManager();

        final SidelineRequest sidelineRequest = new SidelineRequest(Collections.emptyList());

        // Call method and watch for exception
        expectedException.expect(IllegalStateException.class);
        persistenceManager.persistSidelineRequestState(SidelineType.START, new SidelineRequestIdentifier(), sidelineRequest, ConsumerState.builder().build(), null);
    }

    /**
     * Verify we get an exception if you try to retrieve before calling open().
     */
    @Test
    public void testRetrieveSidelineRequestStateBeforeBeingOpened() {
        // Create our instance
        ZookeeperPersistenceManager persistenceManager = new ZookeeperPersistenceManager();

        // Call method and watch for exception
        expectedException.expect(IllegalStateException.class);
        persistenceManager.retrieveSidelineRequest(new SidelineRequestIdentifier());
    }

    /**
     * Verify we get an exception if you try to persist before calling open().
     */
    @Test
    public void testClearSidelineRequestBeforeBeingOpened() {
        // Create our instance
        ZookeeperPersistenceManager persistenceManager = new ZookeeperPersistenceManager();

        // Call method and watch for exception
        expectedException.expect(IllegalStateException.class);
        persistenceManager.clearSidelineRequest(new SidelineRequestIdentifier());
    }

    /**
     * Helper method.
     */
    private Map createDefaultConfig(List<String> zkServers, String zkRootNode) {
        Map config = Maps.newHashMap();
        config.put(SidelineSpoutConfig.PERSISTENCE_ZK_SERVERS, zkServers);
        config.put(SidelineSpoutConfig.PERSISTENCE_ZK_ROOT, zkRootNode);
        return config;
    }

    /**
     * Helper method.
     */
    private Map createDefaultConfig(String zkServers, String zkRootNode) {
        return createDefaultConfig(Lists.newArrayList(zkServers.split(",")), zkRootNode);
    }

    /**
     * Helper method to generate a random zkRootNode path to use.
     */
    private String getRandomZkRootNode() {
        return "/testRoot" + System.currentTimeMillis();
    }
}
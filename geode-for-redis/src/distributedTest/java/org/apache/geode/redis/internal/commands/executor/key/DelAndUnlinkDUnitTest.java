/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.redis.internal.commands.executor.key;

import static org.apache.geode.test.dunit.rules.RedisClusterStartupRule.BIND_ADDRESS;
import static org.apache.geode.test.dunit.rules.RedisClusterStartupRule.REDIS_CLIENT_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;

import org.apache.geode.test.dunit.rules.MemberVM;
import org.apache.geode.test.dunit.rules.RedisClusterStartupRule;
import org.apache.geode.test.junit.rules.ExecutorServiceRule;

public class DelAndUnlinkDUnitTest {
  private static final int KEYS_FOR_BUCKET_TEST = 5000;

  @Rule
  public RedisClusterStartupRule clusterStartUp = new RedisClusterStartupRule();

  @Rule
  public ExecutorServiceRule executor = new ExecutorServiceRule();

  private static JedisCluster jedis;

  @Before
  public void testSetup() {
    MemberVM locator = clusterStartUp.startLocatorVM(0);
    clusterStartUp.startRedisVM(1, locator.getPort());
    clusterStartUp.startRedisVM(2, locator.getPort());
    clusterStartUp.startRedisVM(3, locator.getPort());
    int redisServerPort = clusterStartUp.getRedisPort(1);
    jedis = new JedisCluster(new HostAndPort(BIND_ADDRESS, redisServerPort), REDIS_CLIENT_TIMEOUT);
    clusterStartUp.flushAll();
  }

  @After
  public void tearDown() {
    jedis.close();
  }

  @Test
  public void shouldDistributeDataAmongCluster_andRetainDataAfterServerCrash() {
    final String hashtag = clusterStartUp.getKeyOnServer("unlink", 1);

    // Create key value pairs
    final int initialListSize = 10;
    String[] allKeys = new String[initialListSize];

    for (int i = 0; i < initialListSize; i++) {
      String key = makeKeyWithHashtag(hashtag, 1);
      allKeys[i] = key;

      String value = makeElementString(key, i);
      jedis.set(key, value);
    }

    // Remove all keys except for UniqueKey
    for (String key : allKeys) {
      jedis.unlink(key);
    }

    clusterStartUp.crashVM(1); // kill primary server

    // Make sure that all keys were removed successfully
    for (String key : allKeys) {
      assertThat(jedis.exists(key)).isFalse();
    }
  }

  @Test
  public void givenBucketsMoveDuringUnlink_thenOperationsAreNotLost() throws Exception {
    AtomicBoolean running = new AtomicBoolean(true);

    List<String> listHashtags = makeListHashtags();

    String[] arrayOfKeys1 = new String[KEYS_FOR_BUCKET_TEST];
    String[] arrayOfKeys2 = new String[KEYS_FOR_BUCKET_TEST];
    String[] arrayOfKeys3 = new String[KEYS_FOR_BUCKET_TEST];

    for (int i = 0; i < KEYS_FOR_BUCKET_TEST; i++) {
      String key1 = makeKeyWithHashtag(listHashtags.get(0), i);
      String key2 = makeKeyWithHashtag(listHashtags.get(1), i);
      String key3 = makeKeyWithHashtag(listHashtags.get(2), i);

      jedis.set(key1, makeElementString(key1, i));
      jedis.sadd(key2, makeElementString(key1, i));
      jedis.lpush(key3, makeElementString(key1, i));

      arrayOfKeys1[i] = key1;
      arrayOfKeys2[i] = key2;
      arrayOfKeys3[i] = key3;
    }

    Future<Integer> future1 =
        executor.submit(() -> performUnlinkAndVerify(listHashtags.get(0), running));
    Future<Integer> future2 =
        executor.submit(() -> performUnlinkAndVerify(listHashtags.get(1), running));
    Future<Integer> future3 =
        executor.submit(() -> performUnlinkAndVerify(listHashtags.get(2), running));

    for (int i = 0; i < 50; i++) {
      clusterStartUp.moveBucketForKey(listHashtags.get(i % listHashtags.size()));
      Thread.sleep(500);
    }
    running.set(false);

    future1.get();
    future2.get();
    future3.get();
  }

  private Integer performUnlinkAndVerify(String hashtag, AtomicBoolean isRunning) {
    int iterationCount = 0;
    while (isRunning.get() && iterationCount < KEYS_FOR_BUCKET_TEST) {
      String key = makeKeyWithHashtag(hashtag, iterationCount);
      assertThat(jedis.unlink(key)).isEqualTo(1L);
      assertThat(jedis.exists(key)).isFalse();
      iterationCount++;
    }

    return iterationCount;
  }

  private List<String> makeListHashtags() {
    List<String> listHashtags = new ArrayList<>();
    listHashtags.add(makeHashtag(1));
    listHashtags.add(makeHashtag(2));
    listHashtags.add(makeHashtag(3));
    return listHashtags;
  }

  private String makeHashtag(int vmId) {
    return clusterStartUp.getKeyOnServer("unlink", vmId);
  }

  private String makeKeyWithHashtag(String hashtag, int index) {
    return "{" + hashtag + "}-key-" + index;
  }

  private String makeElementString(String key, int iterationCount) {
    return "-" + key + "-" + iterationCount + "-";
  }
}

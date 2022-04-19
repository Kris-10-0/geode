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

import static org.apache.geode.redis.RedisCommandArgumentsTestHelper.assertAtLeastNArgs;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.Protocol;

import org.apache.geode.redis.ConcurrentLoopingThreads;
import org.apache.geode.redis.RedisIntegrationTest;
import org.apache.geode.test.awaitility.GeodeAwaitility;

public abstract class AbstractUnlinkIntegrationTest implements RedisIntegrationTest {

  private JedisCluster jedis;
  private static final int REDIS_CLIENT_TIMEOUT =
      Math.toIntExact(GeodeAwaitility.getTimeout().toMillis());

  @Before
  public void setUp() {
    jedis = new JedisCluster(new HostAndPort("localhost", getPort()), REDIS_CLIENT_TIMEOUT);
  }

  @After
  public void tearDown() {
    flushAll();
    jedis.close();
  }

  @Test
  public void errors_GivenTooFewArguments() {
    assertAtLeastNArgs(jedis, Protocol.Command.UNLINK, 1);
  }


  @Test
  public void unlink_removesExistingKeys_returnsAmountOfKeysRemoved() {
    assertThat(jedis.unlink("{tag1}nonExistentKey")).isEqualTo(0L);

    String key1 = "{tag1}firstKey";
    String key2 = "{tag1}secondKey";
    String key3 = "{tag1}thirdKey";

    jedis.set(key1, "value1");
    assertThat(jedis.unlink(key1)).isEqualTo(1L);
    assertThat(jedis.exists(key1)).isFalse();

    jedis.set(key1, "value1");
    jedis.sadd(key3, "value2.1", "value2.2", "value2.3");
    assertThat(jedis.unlink(key1, key2, key3)).isEqualTo(2L);
    assertThat(jedis.exists(key1)).isFalse();
    assertThat(jedis.exists(key3)).isFalse();
  }

  @Test
  public void unlink_withBinaryKey() {
    byte[] key = new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

    jedis.set(key, "foo".getBytes());
    jedis.unlink(key);

    assertThat(jedis.get(key)).isNull();
  }

  @Test
  public void ensureListConsistency_whenRunningConcurrently() {
    String key1 = "{tag1}key1";
    String key2 = "{tag1}key2";

    jedis.set(key1, "value1");
    jedis.set(key2, "value2");

    // Ensures for each key that only one key is
    AtomicLong unlinkedCount = new AtomicLong();
    new ConcurrentLoopingThreads(1000,
        i -> unlinkedCount.set(jedis.unlink(key1, key2)),
        i -> jedis.set(key2, "newValue"))
            .runWithAction(() -> {
              assertThat(unlinkedCount.get()).satisfiesAnyOf(
                  count -> assertThat(count).isEqualTo(1L),
                  count -> assertThat(count).isEqualTo(2L));
              assertThat(jedis.exists(key1)).isFalse();
              assertThat(jedis.get(key2)).satisfiesAnyOf(
                  value -> assertThat(value).isNull(),
                  value -> assertThat(value).isEqualTo("newValue"));
              jedis.set(key1, "value1");
              jedis.set(key2, "value2");
            });
  }
}

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

import org.apache.geode.redis.internal.commands.Command;
import org.apache.geode.redis.internal.commands.executor.CommandExecutor;
import org.apache.geode.redis.internal.commands.executor.RedisResponse;
import org.apache.geode.redis.internal.data.RedisDataType;
import org.apache.geode.redis.internal.data.RedisKey;
import org.apache.geode.redis.internal.netty.ExecutionHandlerContext;

import java.util.List;

import static org.apache.geode.redis.internal.RedisConstants.ERROR_NOT_INTEGER;
import static org.apache.geode.redis.internal.RedisConstants.ERROR_SORT_CLUSTER_MODE;
import static org.apache.geode.redis.internal.RedisConstants.ERROR_SYNTAX;
import static org.apache.geode.redis.internal.RedisConstants.ERROR_WRONG_TYPE;
import static org.apache.geode.redis.internal.data.RedisDataType.REDIS_LIST;
import static org.apache.geode.redis.internal.data.RedisDataType.REDIS_SET;
import static org.apache.geode.redis.internal.data.RedisDataType.REDIS_SORTED_SET;
import static org.apache.geode.redis.internal.netty.Coder.bytesToLong;
import static org.apache.geode.redis.internal.netty.Coder.bytesToString;
import static org.apache.geode.redis.internal.netty.Coder.equalsIgnoreCaseBytes;
import static org.apache.geode.redis.internal.netty.StringBytesGlossary.ALPHA;
import static org.apache.geode.redis.internal.netty.StringBytesGlossary.ASC;
import static org.apache.geode.redis.internal.netty.StringBytesGlossary.BY;
import static org.apache.geode.redis.internal.netty.StringBytesGlossary.DESC;
import static org.apache.geode.redis.internal.netty.StringBytesGlossary.GET;
import static org.apache.geode.redis.internal.netty.StringBytesGlossary.LIMIT;
import static org.apache.geode.redis.internal.netty.StringBytesGlossary.STORE;

public class SortExecutor implements CommandExecutor {
    private enum SORT_TYPE {
        ASCENDING,
        DESCENDING,
        NO_SORT
    }

    @Override
    public RedisResponse executeCommand(Command command, ExecutionHandlerContext context)  {
        List<byte[]> commandElems = command.getProcessedCommand();
        RedisKey key = command.getKey();

        return context.dataLockedExecute(key, false, value -> {
            // Validates data type before parsing through command elements
            RedisDataType dataType = value.getType();
            if (dataType != REDIS_LIST | dataType != REDIS_SET | dataType != REDIS_SORTED_SET) {
                return RedisResponse.error(ERROR_WRONG_TYPE);
            }

            return sort(commandElems);
        });
    }

    private RedisResponse sort(List<byte[]> commandElems) {
        boolean alpha = false;
        SORT_TYPE ascendingOrder = SORT_TYPE.ASCENDING;
        Long limitOffset = null;
        Long limitCount = null;
        byte[] destKeyInBytes = null; // Iterate over entire list before creating RedisKey

        int commandSize = commandElems.size();

        // All modifiers, except GET, can be only used once. It takes the last modifier listed as the one to use.
        for(int i = 2; i < commandElems.size(); i++) {
            byte[] arg = commandElems.get(i);

            if(equalsIgnoreCaseBytes(arg, ALPHA)) {
                alpha = true;
            } else if(equalsIgnoreCaseBytes(arg, ASC)) {
                ascendingOrder = SORT_TYPE.ASCENDING;;
            } else if(equalsIgnoreCaseBytes(arg, DESC)) {
                ascendingOrder = SORT_TYPE.DESCENDING;
            } else if(equalsIgnoreCaseBytes(arg, LIMIT)) {
                if(commandSize <= i+2) { // Invalid amount of args
                    return RedisResponse.error(ERROR_SYNTAX);
                }

                try {
                    limitOffset = bytesToLong(commandElems.get(++i));
                    limitCount = bytesToLong(commandElems.get(++i));
                } catch (NumberFormatException e) {
                    return RedisResponse.error(ERROR_NOT_INTEGER);
                }
            } else if(equalsIgnoreCaseBytes(arg, STORE)) {
                if(commandSize <= i+1) { // Invalid amount of args
                    return RedisResponse.error(ERROR_SYNTAX);
                }
                destKeyInBytes = commandElems.get(++i);
            } else if(equalsIgnoreCaseBytes(arg, BY) || equalsIgnoreCaseBytes(arg, GET)) {
                if(commandSize <= i+1) { // Invalid amount of args
                    return RedisResponse.error(ERROR_SYNTAX);
                }


                /** Since we are always running in cluster mode BY and GET will always
                 *  return with an error.
                 *
                 *  If this changes, below are the notes on BY and GET
                 *  BY:  If * is not found in string, then the list is not sorted.
                 *       By only uses strings and hashes to get external keys.
                 *       The -> indicates that it will be getting from a hash.
                 *  GET:
                 *  */
                return RedisResponse.error(String.format(ERROR_SORT_CLUSTER_MODE, bytesToString(arg)));
            } else {
                return RedisResponse.error(ERROR_SYNTAX);
            }
        }



        return RedisResponse.ok();
    }

}

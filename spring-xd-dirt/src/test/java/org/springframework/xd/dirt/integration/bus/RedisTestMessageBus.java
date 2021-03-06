/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.xd.dirt.integration.bus;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.xd.dirt.integration.bus.serializer.MultiTypeCodec;
import org.springframework.xd.dirt.integration.redis.RedisMessageBus;


/**
 * Test support class for {@link RedisMessageBus}.
 * 
 * @author Ilayaperumal Gopinathan
 */
public class RedisTestMessageBus extends AbstractTestMessageBus {

	private StringRedisTemplate template;

	public RedisTestMessageBus(RedisConnectionFactory connectionFactory, MultiTypeCodec<Object> codec) {
		super(new RedisMessageBus(connectionFactory, codec));
		template = new StringRedisTemplate(connectionFactory);
	}

	@Override
	public void cleanup() {
		if (!queues.isEmpty()) {
			for (String queue : queues) {
				template.delete(queue);
			}
		}
	}
}

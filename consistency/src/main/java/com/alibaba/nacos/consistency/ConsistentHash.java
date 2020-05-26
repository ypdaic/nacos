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
package com.alibaba.nacos.consistency;

import com.alibaba.nacos.common.utils.ConvertUtils;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
@SuppressWarnings("all")
public final class ConsistentHash<T> {

	private static final String CONSISTENT_HASH_NUM_REPLICAS = "nacos.core.consisten-hash.num-replica";

	private final HashFunction function = Hashing.md5();

	private volatile TreeMap<Integer, T> circle = new TreeMap<>();

	private final AtomicReferenceFieldUpdater<ConsistentHash, TreeMap> UPDATER =
			AtomicReferenceFieldUpdater.newUpdater(ConsistentHash.class, TreeMap.class, "circle");

	private int numberOfReplicas = 64;

	public ConsistentHash(Collection<T> members) {
		init(members);
	}

	private void init(Collection<T> members) {
		int num = Integer.getInteger(CONSISTENT_HASH_NUM_REPLICAS, numberOfReplicas);
		this.numberOfReplicas = ConvertUtils.convertPow4Two(num);

		adjustCircle(members);
	}

	public T distro(Object key) {
		Objects.requireNonNull(key, "key");
		if (circle.isEmpty()) {
			return null;
		}
		int hash = function.hashString(Objects.toString(key), StandardCharsets.UTF_8).asInt();
		if (!circle.containsKey(hash)) {
			SortedMap<Integer, T> tailMap = circle.tailMap(hash);
			hash = tailMap.isEmpty() ? circle.firstKey() : tailMap.firstKey();
		}
		return circle.get(hash);
	}

	public boolean responibleForTarget(Object key, T expect) {
		final T taregt = distro(key);
		return Objects.equals(expect, taregt);
	}

	public void adjustCircle(Collection<T> members) {
		TreeMap<Integer, T> tmp = new TreeMap<>();
		for (T t : members) {
			for (int i = 0; i < numberOfReplicas; i ++) {
				tmp.put(function.hashString(Objects.toString(t) + i, StandardCharsets.UTF_8).asInt(), t);
			}
		}
		UPDATER.compareAndSet(this, circle, tmp);
	}

}

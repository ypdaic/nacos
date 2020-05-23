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
package com.alibaba.nacos.core.distributed;

import com.alibaba.nacos.common.JustForTest;
import com.alibaba.nacos.common.utils.ConvertUtils;
import com.alibaba.nacos.core.cluster.Member;
import com.alibaba.nacos.core.cluster.MemberChangeEvent;
import com.alibaba.nacos.core.cluster.MemberChangeListener;
import com.alibaba.nacos.core.notify.NotifyCenter;
import com.alibaba.nacos.core.utils.ApplicationUtils;
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
public final class ConsistentHash implements MemberChangeListener {

	private static final String CONSISTENT_HASH_NUM_REPLICAS = "nacos.core.consisten-hash.num-replica";

	private static final ConsistentHash INSTANCE = new ConsistentHash();

	static {
		NotifyCenter.registerSubscribe(INSTANCE);
	}

	public static ConsistentHash getInstance() {
		return INSTANCE;
	}

	private final HashFunction function = Hashing.md5();

	private volatile TreeMap<Integer, Member> circle = new TreeMap<>();

	private static final AtomicReferenceFieldUpdater<ConsistentHash, TreeMap> UPDATER =
			AtomicReferenceFieldUpdater.newUpdater(ConsistentHash.class, TreeMap.class, "circle");

	private int numberOfReplicas = 64;

	private ConsistentHash() {
	}

	void init(Collection<Member> members) {
		int num = ApplicationUtils.getProperty(CONSISTENT_HASH_NUM_REPLICAS, Integer.class, numberOfReplicas);
		this.numberOfReplicas = ConvertUtils.convertPow4Two(num);

		adjustCircle(members);
	}

	public Member distro(Object key) {
		if (circle.isEmpty()) {
			return null;
		}

		int hash = function.hashString(Objects.toString(key), StandardCharsets.UTF_8).asInt();
		if (!circle.containsKey(hash)) {
			SortedMap<Integer, Member> tailMap = circle.tailMap(hash);
			hash = tailMap.isEmpty() ? circle.firstKey() : tailMap.firstKey();
		}
		return circle.get(hash);
	}

	private void adjustCircle(Collection<Member> members) {
		TreeMap<Integer, Member> tmp = new TreeMap<>();
		for (Member member : members) {
			for (int i = 0; i < numberOfReplicas; i ++) {
				tmp.put(function.hashString(member.getAddress() + i, StandardCharsets.UTF_8).asInt(), member);
			}
		}
		UPDATER.compareAndSet(this, circle, tmp);
	}

	@Override
	public void onEvent(MemberChangeEvent event) {
		adjustCircle(event.getMembers());
	}
}

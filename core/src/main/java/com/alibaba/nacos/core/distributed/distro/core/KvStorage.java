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
package com.alibaba.nacos.core.distributed.distro.core;

import com.alibaba.nacos.core.distributed.distro.grpc.Record;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public class KvStorage {

	private final Map<String, Map<String, Record>> multipleStorage;

	public KvStorage() {
		this.multipleStorage = new ConcurrentHashMap<>(4);
	}

	public boolean contains(final String group, final String key) {
		return multipleStorage.getOrDefault(group, Collections.emptyMap()).containsKey(key);
	}

	public void put(final String group, String key, Record value) {
		multipleStorage.computeIfAbsent(group, s -> new ConcurrentHashMap<>());
		multipleStorage.getOrDefault(group, Collections.emptyMap()).put(key, value);
	}

	public Record remove(final String group, final String key) {
		return multipleStorage.getOrDefault(group, Collections.emptyMap()).remove(key);
	}

	public Set<String> keys(final String group) {
		return multipleStorage.getOrDefault(group, Collections.emptyMap()).keySet();
	}

	public Record get(final String group, final String key) {
		return multipleStorage.getOrDefault(group, Collections.emptyMap()).get(key);
	}

	public Map<String, Record> batchGet(final String group, final List<String> keys) {
		Map<String, Record> map = new HashMap<>(128);
		Map<String, Record> data = multipleStorage.getOrDefault(group, Collections.emptyMap());
		if (keys.isEmpty()) {
			map.putAll(data);
			return map;
		}
		for (String key : keys) {
			Record value = data.get(key);
			if (value == null) {
				continue;
			}
			map.put(key, value);
		}
		return map;
	}

	public Map<String, Map<String, Record>> snapshotRead() {
		return Collections.unmodifiableMap(multipleStorage);
	}
}

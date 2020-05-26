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

import com.alibaba.nacos.api.exception.NacosUncheckException;
import com.alibaba.nacos.common.utils.ConvertUtils;
import com.alibaba.nacos.common.utils.LoggerUtils;
import com.alibaba.nacos.consistency.ConsistentHash;
import com.alibaba.nacos.core.distributed.distro.DistroConfig;
import com.alibaba.nacos.core.distributed.distro.DistroSysConstants;
import com.alibaba.nacos.core.distributed.distro.grpc.Checksum;
import com.alibaba.nacos.core.distributed.distro.grpc.Merge;
import com.alibaba.nacos.core.distributed.distro.grpc.Record;
import com.alibaba.nacos.core.distributed.distro.utils.DistroExecutor;
import com.alibaba.nacos.core.distributed.distro.utils.ErrorCode;
import com.alibaba.nacos.core.utils.ApplicationUtils;
import com.alibaba.nacos.core.utils.Loggers;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public class DataSyncer {

	public static final String CACHE_KEY_SPLITER = "@@@@";

	private final DistroConfig config;
	private final MultiKvStorage storage;
	private final RemoteServer remoteServer;
	private final ConsistentHash<String> consistentHash;

	private Map<String, String> taskRecord = new ConcurrentHashMap<>();

	public DataSyncer(DistroConfig config, MultiKvStorage storage, RemoteServer remoteServer,
			ConsistentHash<String> consistentHash) {
		this.config = config;
		this.storage = storage;
		this.remoteServer = remoteServer;
		this.consistentHash = consistentHash;
	}

	public void start() {
		DistroExecutor.schedulePartitionDataTimedSync(new TimedSync());
	}

	public void submit(SyncTask task, long delay) {
		// If it's a new task:
		if (task.getRetryCount() == 0) {
			Iterator<String> iterator = task.getKeys().iterator();
			while (iterator.hasNext()) {
				String key = iterator.next();
				if (StringUtils.isNotBlank(taskRecord.putIfAbsent(
						buildKey(task.getGroup(), key, task.getTargetServer()), key))) {
					// associated key already exist:
					if (Loggers.DISTRO.isDebugEnabled()) {
						Loggers.DISTRO.debug("sync already in process, key: {}", key);
					}
					iterator.remove();
				}
			}
		}

		if (task.getKeys().isEmpty()) {
			// all keys are removed:
			return;
		}

		DistroExecutor.submitDataSync(() -> {
			// 1. check the server
			if (config.getMembers().isEmpty()) {
				Loggers.DISTRO.warn("try to sync data but server list is empty.");
				return;
			}

			final String group = task.getGroup();
			final List<String> keys = task.getKeys();

			LoggerUtils.printIfDebugEnabled(Loggers.DISTRO,
					"try to sync data for this keys {}.", keys);

			// 2. get the datums by keys and check the datum is empty or not
			Map<String, Record> datumMap = storage.batchGet(group, keys);
			if (datumMap == null || datumMap.isEmpty()) {
				// clear all flags of this task:
				for (String key : keys) {
					taskRecord.remove(buildKey(group, key, task.getTargetServer()));
				}
				return;
			}

			long timestamp = System.currentTimeMillis();
			final String server = task.getTargetServer();
			remoteServer.findClient(server).sendMergeReq(
					Merge.newBuilder().setGroup(group).setOrigin(config.getSelfMember())
							.putAllData(datumMap).build()).whenComplete((result, ex) -> {
				if (Objects.nonNull(ex)) {
					result = false;
					Loggers.DISTRO.error("Synchronization data is abnormal : {}",
							ex.toString());
				}
				if (!result) {
					task.setLastExecuteTime(timestamp);
					task.setRetryCount(task.getRetryCount() + 1);
					retrySync(task);
				}
				else {
					// clear all flags of this task:
					for (String key : task.getKeys()) {
						taskRecord.remove(buildKey(task.getGroup(), key,
								task.getTargetServer()));
					}
				}
			});

		}, delay);
	}

	public void retrySync(SyncTask syncTask) {
		final String server = syncTask.getTargetServer();
		if (!config.getMembers().contains(server)) {
			// if server is no longer in healthy server list, ignore this task:
			// fix #1665 remove existing tasks
			if (syncTask.getKeys() != null) {
				for (String key : syncTask.getKeys()) {
					taskRecord.remove(buildKey(syncTask.getGroup(), key, server));
				}
			}
			return;
		}

		// TODO may choose other retry policy.
		submit(syncTask, ConvertUtils
				.toLong(config.getVal(DistroSysConstants.SYNC_RETRY_DELAY_KEY),
						DistroSysConstants.DEFAULT_SYNC_RETRY_DELAY));
	}

	public class TimedSync implements Runnable {

		@Override
		public void run() {

			try {
				LoggerUtils.printIfDebugEnabled(Loggers.DISTRO, "server list is: {}",
						config.getMembers());

				// send local timestamps to other servers:
				storage.snapshotRead().forEach((group, valueMap) -> {
					Map<String, String> keyChecksums = new HashMap<>(64);

					valueMap.forEach((key, value) -> {
						if (!consistentHash
								.responibleForTarget(key, config.getSelfMember())) {
							return;
						}
						keyChecksums.put(key, value.getCheckSum());
					});

					if (keyChecksums.isEmpty()) {
						return;
					}
					LoggerUtils.printIfDebugEnabled(Loggers.DISTRO, "sync checksums: {}",
							keyChecksums);

					// TODO There will be a broadcast storm
					for (final String member : config.getMembers()) {
						if (Objects.equals(ApplicationUtils.getLocalAddress(), member)) {
							continue;
						}
						try {
							remoteServer.findClient(member).pullByChecksum(
									Checksum.newBuilder().setGroup(group)
											.setOrigin(config.getSelfMember())
											.putAllData(keyChecksums).build());
						}
						catch (Throwable ex) {
							throw new NacosUncheckException(
									ErrorCode.DISTRO_START_FAILED.getCode(), ex);
						}
					}
				});

			}
			catch (Exception e) {
				Loggers.DISTRO.error("timed sync task failed.", e);
			}
		}

	}

	public String buildKey(final String group, final String key,
			final String targetServer) {
		return group + CACHE_KEY_SPLITER + key + CACHE_KEY_SPLITER + targetServer;
	}

}

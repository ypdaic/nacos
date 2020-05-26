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
import com.alibaba.nacos.common.utils.ConcurrentHashSet;
import com.alibaba.nacos.common.utils.LoggerUtils;
import com.alibaba.nacos.common.utils.ThreadUtils;
import com.alibaba.nacos.consistency.ConsistentHash;
import com.alibaba.nacos.consistency.ap.LogProcessor4AP;
import com.alibaba.nacos.consistency.entity.Log;
import com.alibaba.nacos.consistency.entity.Response;
import com.alibaba.nacos.core.distributed.distro.DistroConfig;
import com.alibaba.nacos.core.distributed.distro.exception.NoSuchDistroGroupException;
import com.alibaba.nacos.core.distributed.distro.grpc.ExceptionListener;
import com.alibaba.nacos.core.distributed.distro.grpc.Load;
import com.alibaba.nacos.core.distributed.distro.grpc.Query;
import com.alibaba.nacos.core.distributed.distro.grpc.Record;
import com.alibaba.nacos.core.distributed.distro.utils.DistroExecutor;
import com.alibaba.nacos.core.distributed.distro.utils.ErrorCode;
import com.alibaba.nacos.core.utils.ApplicationUtils;
import com.alibaba.nacos.core.utils.Loggers;
import com.google.common.hash.Hashing;
import com.google.protobuf.ByteString;
import io.grpc.BindableService;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.util.MutableHandlerRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
@SuppressWarnings("all")
public class DistroServer {

	private final int port;

	private final DistroConfig config;

	private final RemoteServer remoteServer;

	private final AtomicBoolean start = new AtomicBoolean(false);

	private final MultiKvStorage storage;

	private final DataSyncer syncer;

	private final TaskDispatcher dispatcher;

	private io.grpc.Server server;

	private MutableHandlerRegistry handlerRegistry = new MutableHandlerRegistry();

	private ConcurrentHashMap<BindableService, ServerServiceDefinition> serviceInfo = new ConcurrentHashMap<BindableService, ServerServiceDefinition>(
			4);

	private Set<String> syncChecksumTasks = new ConcurrentHashSet<>();

	private final Map<String, LogProcessor4AP> processor4APMap;

	private final ConsistentHash<String> consistentHash;

	public DistroServer(DistroConfig config,
			Map<String, LogProcessor4AP> processor4APMap) {
		DistroExecutor.init(config);
		this.config = config;
		this.consistentHash = new ConsistentHash(config.getMembers());
		this.processor4APMap = processor4APMap;
		this.remoteServer = new RemoteServer(config, this);
		this.storage = new MultiKvStorage();
		this.syncer = new DataSyncer(config, storage, remoteServer, consistentHash);
		this.dispatcher = new TaskDispatcher(syncer, config);
		this.port = Integer.parseInt(config.getSelfMember().split(":")[1]);
		registerService(new DistroServiceHandler(this, storage));
	}

	public ConsistentHash<String> getConsistentHash() {
		return consistentHash;
	}

	public void start() {
		if (!start.compareAndSet(false, true)) {
			return;
		}
		this.server = NettyServerBuilder.forPort(port).intercept(new ServerInterceptor() {
			@Override
			public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
					ServerCall<ReqT, RespT> serverCall, Metadata metadata,
					ServerCallHandler<ReqT, RespT> next) {
				ServerCall.Listener<ReqT> reqTListener = next
						.startCall(serverCall, metadata);
				return new ExceptionListener(reqTListener, serverCall);
			}
		}).fallbackHandlerRegistry(handlerRegistry).build();
		try {
			server.start();
			dispatcher.start();
			remoteServer.start();
			syncer.start();
			CountDownLatch latch = new CountDownLatch(1);
			DistroExecutor
					.executeByCommon(() -> loadData(latch));
			ThreadUtils.latchAwait(latch);
		}
		catch (Throwable e) {
			throw new NacosUncheckException(ErrorCode.DISTRO_START_FAILED.getCode(),
					ErrorCode.DISTRO_START_FAILED.getErrMsg(), e);
		}
	}

	public DistroConfig getConfig() {
		return config;
	}

	public void registerService(Object ref) {
		BindableService bindableService = (BindableService) ref;
		ServerServiceDefinition serverServiceDefinition = bindableService.bindService();
		serviceInfo.put(bindableService, serverServiceDefinition);
		handlerRegistry.addService(serverServiceDefinition);
	}

	public void onMemberChange(Set<String> newMembers) {
		config.updateMembers(config.getSelfMember(), newMembers);
		remoteServer.changeRemoteServer();
	}

	public Response apply(Log log) {
		final String group = log.getGroup();
		final ByteString data = log.getData();
		final Record record = Record.newBuilder()
				.setCheckSum(Hashing.md5().hashBytes(data.toByteArray()).toString())
				.setData(data).build();
		return innerApply(group, log.getKey(), record, log);
	}

	public Response innerApply(final String group, final String key, final Record record,
			final Log log) {
		storage.put(group, key, record);
		return Optional.ofNullable(processor4APMap.get(group))
				.orElseThrow(() -> new NoSuchDistroGroupException(group)).onApply(log);
	}

	public Response remove(Log log) {
		final String group = log.getGroup();
		storage.remove(group, log.getKey());
		return Optional.ofNullable(processor4APMap.get(group))
				.orElseThrow(() -> new NoSuchDistroGroupException(group)).onRemove(log);
	}

	void onReceiveChecksums(final String group, final Map<String, String> checksumMap,
			final String server) {

		if (syncChecksumTasks.contains(server)) {
			// Already in process of this server:
			Loggers.DISTRO.warn("sync checksum task already in process with {}", server);
			return;
		}

		syncChecksumTasks.add(server);

		try {

			List<String> toUpdateKeys = new ArrayList<>();
			List<String> toRemoveKeys = new ArrayList<>();
			for (Map.Entry<String, String> entry : checksumMap.entrySet()) {

				final String key = entry.getKey();

				if (consistentHash.responibleForTarget(key, config.getSelfMember())) {
					// this key should not be sent from remote server:
					Loggers.DISTRO.error("receive responsible key timestamp of " + entry
							.getKey() + " from " + server);
					// abort the procedure:
					return;
				}

				if (!storage.contains(group, key) || storage.get(group, key) == null
						|| !storage.get(group, key).getCheckSum()
						.equals(entry.getValue())) {
					toUpdateKeys.add(entry.getKey());
				}
			}

			for (String key : storage.keys(group)) {
				if (!Objects.equals(server, consistentHash.distro(key))) {
					continue;
				}
				if (!checksumMap.containsKey(key)) {
					toRemoveKeys.add(key);
				}
			}

			LoggerUtils.printIfDebugEnabled(Loggers.DISTRO,
					"to remove keys: {}, to update keys: {}, source: {}", toRemoveKeys,
					toUpdateKeys, server);

			storage.batchRemove(group, toRemoveKeys);
			final LogProcessor4AP processor = processor4APMap.get(group);
			Optional.ofNullable(processor).ifPresent(p -> {
				for (String key : toRemoveKeys) {
					p.onRemove(Log.newBuilder().setKey(key).build());
				}
			});

			if (toUpdateKeys.isEmpty()) {
				return;
			}

			try {
				remoteServer.findClient(server)
						.query(Query.newBuilder().setGroup(group).addAllKeys(toUpdateKeys)
								.build());
			}
			catch (Exception e) {
				Loggers.DISTRO.error("get data from " + server + " failed!", e);
			}
		}
		finally {
			// Remove this 'in process' flag:
			syncChecksumTasks.remove(server);
		}
	}

	private void loadData(CountDownLatch latch) {
		if (ApplicationUtils.getStandaloneMode()) {
			return;
		}
		// size = 1 means only myself in the list, we need at least one another server alive:
		while (config.getMembers().size() <= 1) {
			ThreadUtils.sleep(1_000L);
			Loggers.DISTRO.info("waiting server list init...");
		}

		for (; ; ) {
			for (final String member : config.getMembers()) {
				if (Objects.equals(member, ApplicationUtils.getLocalAddress())) {
					continue;
				}
				if (remoteFromOneServer(member)) {
					latch.countDown();
					return;
				}
			}
		}
	}

	private boolean remoteFromOneServer(final String member) {
		RemoteServer.DistroClient client = remoteServer.findClient(member);

		if (!client.isReady()) {
			return false;
		}

		LoggerUtils.printIfDebugEnabled(Loggers.DISTRO, "sync from " + member);

		// try sync data from remote server:
		return remoteServer.findClient(member).load(Load.newBuilder().build(),
				values -> onReceiveRemote(values.getGroup(), values.getDataMap())).join();
	}

	public void onReceiveRemote(final String group, final Map<String, Record> value) {
		value.forEach((key, record) -> {
			final Log log = Log.newBuilder().setGroup(group).setKey(key)
					.setData(record.getData()).build();
			innerApply(group, key, record, log);
		});
	}

	public void shutdown() {
		if (!start.compareAndSet(true, false)) {
			return;
		}
		server.shutdown();
		dispatcher.shutdown();
		remoteServer.shutdown();
	}

}

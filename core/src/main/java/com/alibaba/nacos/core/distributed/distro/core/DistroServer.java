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
import com.alibaba.nacos.consistency.IdGenerator;
import com.alibaba.nacos.consistency.ap.LogProcessor4AP;
import com.alibaba.nacos.consistency.entity.Log;
import com.alibaba.nacos.consistency.entity.Response;
import com.alibaba.nacos.core.cluster.Member;
import com.alibaba.nacos.core.distributed.ConsistentHash;
import com.alibaba.nacos.core.distributed.distro.DistroConfig;
import com.alibaba.nacos.core.distributed.distro.exception.NoSuchDistroGroupException;
import com.alibaba.nacos.core.distributed.distro.grpc.ExceptionListener;
import com.alibaba.nacos.core.distributed.distro.grpc.Record;
import com.alibaba.nacos.core.distributed.distro.grpc.Request;
import com.alibaba.nacos.core.distributed.distro.grpc.Value;
import com.alibaba.nacos.core.distributed.distro.utils.DistroUtils;
import com.alibaba.nacos.core.distributed.distro.utils.ErrorCode;
import com.alibaba.nacos.core.distributed.id.SnakeFlowerIdGenerator;
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
import io.grpc.stub.StreamObserver;
import io.grpc.util.MutableHandlerRegistry;
import org.apache.http.util.NetUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
@SuppressWarnings("all")
public class DistroServer {

	private final int port;

	private final DistroConfig config;

	private final RemoteServer remoteServer;

	private final AtomicBoolean start = new AtomicBoolean(false);

	private final KvStorage storage;

	private final DataSyncer syncer;

	private final TaskDispatcher dispatcher;

	private io.grpc.Server server;

	private MutableHandlerRegistry handlerRegistry = new MutableHandlerRegistry();

	private ConcurrentHashMap<BindableService, ServerServiceDefinition> serviceInfo = new ConcurrentHashMap<BindableService, ServerServiceDefinition>(
			4);

	private Set<String> syncChecksumTasks = new ConcurrentHashSet<>();

	private IdGenerator generator = new SnakeFlowerIdGenerator();

	private final ConsistentHash consistentHash = ConsistentHash.getInstance();

	private final Map<String, LogProcessor4AP> processor4APMap;

	public DistroServer(DistroConfig config,
			Map<String, LogProcessor4AP> processor4APMap) {
		this.config = config;
		this.processor4APMap = processor4APMap;
		this.remoteServer = new RemoteServer(config);
		this.storage = new KvStorage();
		this.syncer = new DataSyncer(config, storage, remoteServer);
		this.dispatcher = new TaskDispatcher(syncer, config);
		this.port = Integer.parseInt(config.getSelfMember().split(":")[1]);
		registerService(new DistroServiceHandler(this, storage));
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
			syncRemoteServerData();
		}
		catch (Throwable e) {
			throw new NacosUncheckException(ErrorCode.DISTRO_START_FAILED.getCode(),
					ErrorCode.DISTRO_START_FAILED.getErrMsg(), e);
		}
	}

	private void syncRemoteServerData() throws Exception {
		if (ApplicationUtils.getStandaloneMode()) {
			return;
		}
		// size = 1 means only myself in the list, we need at least one another server alive:
		while (config.getMembers().size() <= 1) {
			ThreadUtils.sleep(1_000L);
			Loggers.DISTRO.info("waiting server list init...");
		}

		for (final String member : config.getMembers()) {
			if (Objects.equals(member, ApplicationUtils.getLocalAddress())) {
				continue;
			}

			RemoteServer.DistroClient client = remoteServer.findClient(member);

			if (!client.isReady()) {
				continue;
			}

			LoggerUtils.printIfDebugEnabled(Loggers.DISTRO, "sync from " + member);

			CountDownLatch latch = new CountDownLatch(1);

			// try sync data from remote server:
			StreamObserver<Request> observer = remoteServer.acquire(DistroUtils.ALL_GROUP,
					Collections.emptyList(), member, new BiConsumer<Value, Throwable>() {
						@Override
						public void accept(Value value, Throwable ex) {
							final String group = value.getGroup();
							value.getDataMap().forEach((key, record) -> {
								innerApply(group, key, record, Log.newBuilder()
										.setGroup(group)
										.setKey(key)
										.setData(record.getData())
										.build());
							});

							String isFinished = value.getExtendInfoOrDefault(DistroUtils.FINISHED, "0");
							if (Objects.equals(isFinished, "1")) {
								latch.countDown();
							}
						}
					});

			ThreadUtils.latchAwait(latch);

		}
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

	void onReceiveChecksums(final String group,
			final Map<String, String> checksumMap, final String server) {

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

				if (consistentHash.responibleBySelf(key)) {
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

				if (!Objects.equals(server, consistentHash.distro(key).getAddress())) {
					continue;
				}

				if (!checksumMap.containsKey(key)) {
					toRemoveKeys.add(key);
				}
			}

			LoggerUtils.printIfDebugEnabled(Loggers.DISTRO,
					"to remove keys: {}, to update keys: {}, source: {}", toRemoveKeys,
					toUpdateKeys, server);

			for (String key : toRemoveKeys) {
				// need to remove
			}

			if (toUpdateKeys.isEmpty()) {
				return;
			}

			try {
				Value result = remoteServer.acquire(group, toUpdateKeys, server);
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

	public void shutdown() {
		if (!start.compareAndSet(true, false)) {
			return;
		}
		server.shutdown();
		dispatcher.shutdown();
	}

}

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

import com.alibaba.nacos.common.utils.ThreadUtils;
import com.alibaba.nacos.consistency.SerializeFactory;
import com.alibaba.nacos.consistency.Serializer;
import com.alibaba.nacos.core.distributed.distro.DistroConfig;
import com.alibaba.nacos.core.distributed.distro.grpc.Checksum;
import com.alibaba.nacos.core.distributed.distro.grpc.DistroServiceGrpc;
import com.alibaba.nacos.core.distributed.distro.grpc.Record;
import com.alibaba.nacos.core.distributed.distro.grpc.Request;
import com.alibaba.nacos.core.distributed.distro.grpc.Response;
import com.alibaba.nacos.core.distributed.distro.grpc.Value;
import com.alibaba.nacos.core.distributed.distro.utils.DistroExecutor;
import com.alibaba.nacos.core.distributed.distro.utils.DistroUtils;
import com.alibaba.nacos.core.utils.Loggers;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public class RemoteServer {

	private final Map<String, DistroClient> clientMap = new ConcurrentHashMap<>(8);

	private final DistroConfig config;

	private Map<String, Map<String, CompletableFuture>> futureMap = new ConcurrentHashMap<>(4);

	public RemoteServer(DistroConfig config) {
		this.config = config;
	}

	public void start() {
		connectRemoteMember();
	}

	public void shutdown() {
		final Response response = Response.newBuilder().build();
		futureMap.forEach((server, futureMap) -> futureMap.forEach((requestId, future) -> future.complete(response)));
		clientMap.forEach((server, client) -> {
			try {
				client.close();
			} catch (Throwable ex) {
				Loggers.DISTRO.error("{} - Client shutdown failed {}", server, ex.toString());
			}
		});
	}

	CompletableFuture<Boolean> syncData(final String group, final Map<String, Record> data, final String targetServer) {
		DistroClient client = findClient(targetServer);
		Map<String, Record> transport = data.entrySet().stream()
				.collect(HashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), HashMap::putAll);
		final Value value = Value.newBuilder()
				.setGroup(group)
				.putAllData(transport)
				.build();
		return client.transportRemote(value).thenApply(response -> {
			final ByteString bytes = response.getData();
			return bytes.byteAt(0) == 0;
		});
	}

	boolean syncCheckSums(final String group, final Map<String, String> checkSum, final String targetServer) throws Exception {
		DistroClient client = findClient(targetServer);
		Checksum checksum = Checksum.newBuilder()
				.setGroup(group)
				.putAllData(checkSum)
				.build();
		return client.transportCheckSum(checksum).thenApply(response -> {
			final ByteString bytes = response.getData();
			return bytes.byteAt(0) == 0;
		}).get(10_000L, TimeUnit.MILLISECONDS);
	}

	Value acquire(final String group, final List<String> keys, final String targetServer) throws Exception {
		DistroClient client = findClient(targetServer);
		Request request = Request.newBuilder()
				.setGroup(group)
				.addAllKeys(keys)
				.build();
		return client.acquire(request).get(10_000L, TimeUnit.MILLISECONDS);
	}

	StreamObserver<Request> acquire(final String group, final List<String> keys, final String targetServer, final
			BiConsumer<Value, Throwable> consumer) throws Exception {
		DistroClient client = findClient(targetServer);
		Request request = Request.newBuilder()
				.setGroup(group)
				.addAllKeys(keys)
				.build();
		return client.acquire(request, consumer);
	}

	public DistroClient findClient(final String server) {
		DistroClient client = clientMap.get(server);
		Objects.requireNonNull(client, "The client for the current node does not exist");
		return client;
	}

	private void connectRemoteMember() {
		List<String> members = new ArrayList<>(config.getMembers());
		members.forEach(member ->{
			final String[] inet = member.split(":");
			final String ip = inet[0];
			final int port = Integer.parseInt(inet[1]);
			DistroExecutor.executeByCommon(() -> {
				if (clientMap.containsKey(member)) {
					return;
				}
				DistroClient client = new DistroClient(ip, port);
				for ( ; ; ) {
					if (client.isReady()) {
						clientMap.put(member, client);
						return;
					}
					ThreadUtils.sleep(1000L);
				}
			});
		});
	}

	void changeRemoteServer() {
		Set<String> waitRemove = config.getMembers();
		waitRemove.removeAll(clientMap.keySet());
		waitRemove.forEach(member -> {
			DistroClient client = clientMap.get(member);
			if (Objects.isNull(client)) {
				return;
			}
			client.close();
		});

		connectRemoteMember();
	}

	public class DistroClient {

		private final String server;
		private final DistroServiceGrpc.DistroServiceStub client;
		private final ManagedChannel channel;

		private StreamObserver<com.alibaba.nacos.core.distributed.distro.grpc.Value> observerOnSync;
		private StreamObserver<Checksum> observerOnCheckSum;
		private StreamObserver<Request> observerOnAcquire;

		public DistroClient(final String ip, final int port) {
			this.server = ip + ":" + port;
			channel = ManagedChannelBuilder.forAddress(ip, port).usePlaintext()
					.build();
			client = DistroServiceGrpc.newStub(channel);

			init();
			futureMap.computeIfAbsent(server, s -> new HashMap<>());
		}

		private void init() {
			observerOnSync = client.onSync(new StreamObserver<Response>() {

				@Override
				public void onNext(Response response) {

				}

				@Override
				public void onError(Throwable throwable) {

				}

				@Override
				public void onCompleted() {
					observerOnSync.onCompleted();
				}
			});

			observerOnCheckSum = client.syncCheckSum(new StreamObserver<Response>() {

				@Override
				public void onNext(Response response) {

				}

				@Override
				public void onError(Throwable throwable) {

				}

				@Override
				public void onCompleted() {
					observerOnCheckSum.onCompleted();
				}
			});

			observerOnAcquire = client.acquire(new StreamObserver<Value>() {

				@Override
				public void onNext(Value response) {
					final String requestId = response.getExtendInfoOrThrow(DistroUtils.REQUEST_ID_KEY);
					CompletableFuture<Value> future = futureMap.get(server).get(requestId);
					Objects.requireNonNull(future, "No corresponding request ID exists");
					future.complete(response);
				}

				@Override
				public void onError(Throwable throwable) {

				}

				@Override
				public void onCompleted() {
					observerOnAcquire.onCompleted();
				}
			});
		}

		public boolean isReady() {
			return Objects.equals(ConnectivityState.READY, channel.getState(true));
		}

		public CompletableFuture<Response> transportRemote(com.alibaba.nacos.core.distributed.distro.grpc.Value value) {
			final String requestId = value.getExtendInfoOrThrow(DistroUtils.REQUEST_ID_KEY);
			return execute(requestId, () -> {
				observerOnSync.onNext(value);
			});
		}

		public CompletableFuture<Response> transportCheckSum(Checksum checksum) {
			final String requestId = checksum.getExtendInfoOrThrow(DistroUtils.REQUEST_ID_KEY);
			return execute(requestId, () -> {
				observerOnCheckSum.onNext(checksum);
			});
		}

		public CompletableFuture<Value> acquire(Request request) {
			final String requestId = request.getExtendInfoOrThrow(DistroUtils.REQUEST_ID_KEY);
			return execute(requestId, () -> {
				observerOnAcquire.onNext(request);
			});
		}

		public StreamObserver<Request> acquire(Request request, BiConsumer<Value, Throwable> consumer) {
			StreamObserver<Request> observer = client.acquire(new StreamObserver<Value>() {
				@Override
				public void onNext(Value value) {
					consumer.accept(value, null);
				}

				@Override
				public void onError(Throwable t) {
					consumer.accept(null, t);
				}

				@Override
				public void onCompleted() {

				}
			});
			observerOnAcquire.onNext(request);
			return observer;
		}

		private <T> CompletableFuture<T> execute(final String requestId, final Runnable func) {
			final CompletableFuture<T> future = new CompletableFuture<>();
			final boolean[] isExist = new boolean[] {false};
			futureMap.get(server).computeIfAbsent(requestId, s -> {
				isExist[0] = true;
				return future;
			});

			if (isExist[0]) {
				future.completeExceptionally(new StatusRuntimeException(Status.ABORTED));
				return future;
			}

			func.run();
			return future;
		}

		public void close() {
			observerOnSync.onCompleted();
			observerOnCheckSum.onCompleted();
			observerOnAcquire.onCompleted();
			channel.shutdown();
		}
	}

}

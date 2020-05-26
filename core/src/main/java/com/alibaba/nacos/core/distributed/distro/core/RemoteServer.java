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
import com.alibaba.nacos.consistency.IdGenerator;
import com.alibaba.nacos.core.distributed.distro.DistroConfig;
import com.alibaba.nacos.core.distributed.distro.grpc.Checksum;
import com.alibaba.nacos.core.distributed.distro.grpc.DistroServiceGrpc;
import com.alibaba.nacos.core.distributed.distro.grpc.Load;
import com.alibaba.nacos.core.distributed.distro.grpc.Merge;
import com.alibaba.nacos.core.distributed.distro.grpc.Query;
import com.alibaba.nacos.core.distributed.distro.grpc.Request;
import com.alibaba.nacos.core.distributed.distro.grpc.Response;
import com.alibaba.nacos.core.distributed.distro.grpc.Value;
import com.alibaba.nacos.core.distributed.distro.grpc.Values;
import com.alibaba.nacos.core.distributed.distro.utils.DistroExecutor;
import com.alibaba.nacos.core.distributed.distro.utils.GrpcUtils;
import com.alibaba.nacos.core.distributed.id.SnakeFlowerIdGenerator;
import com.alibaba.nacos.core.utils.Loggers;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public class RemoteServer {

	private IdGenerator generator = new SnakeFlowerIdGenerator();

	private final Map<String, DistroClient> clientMap = new ConcurrentHashMap<>(8);

	private final DistroConfig config;

	private final DistroServer distroServer;

	public RemoteServer(DistroConfig config, DistroServer server) {
		this.config = config;
		this.distroServer = server;
	}

	public void start() {
		GrpcUtils.registerProtobufSerializer(Value.getDefaultInstance());
		GrpcUtils.registerProtobufSerializer(Request.getDefaultInstance());
		GrpcUtils.registerProtobufSerializer(Checksum.getDefaultInstance());

		GrpcUtils.registerRequest2Response(Value.class.getName(), Response.getDefaultInstance());
		GrpcUtils.registerRequest2Response(Checksum.class.getName(), Response.getDefaultInstance());
		GrpcUtils.registerRequest2Response(Request.class.getName(), Value.getDefaultInstance());
		connectRemoteMember();
	}

	public void shutdown() {
		clientMap.forEach((server, client) -> {
			try {
				client.close();
			} catch (Throwable ex) {
				Loggers.DISTRO.error("{} - Client shutdown failed {}", server, ex.toString());
			}
		});
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
		private final DistroServiceGrpc.DistroServiceStub streamClient;
		private final ManagedChannel channel;

		private StreamObserver<Checksum> observerOnCheckSum;
		private StreamObserver<Query> observerOnQuery;

		public DistroClient(final String ip, final int port) {
			this.server = ip + ":" + port;
			channel = ManagedChannelBuilder.forAddress(ip, port).usePlaintext()
					.build();
			streamClient = DistroServiceGrpc.newStub(channel);
			observerOnCheckSum = streamClient.receive(new StreamObserver<Response>() {
				@Override
				public void onNext(Response value) {

				}

				@Override
				public void onError(Throwable t) {

				}

				@Override
				public void onCompleted() {

				}
			});
			observerOnQuery = streamClient.query(new StreamObserver<Values>() {
				@Override
				public void onNext(Values value) {
					distroServer.onReceiveRemote(value.getGroup(), value.getDataMap());
				}

				@Override
				public void onError(Throwable t) {

				}

				@Override
				public void onCompleted() {

				}
			});
		}

		public CompletableFuture<Boolean> load(Load load, Consumer<Values> consumer) {
			CompletableFuture<Boolean> future = new CompletableFuture<>();
			streamClient.load(load, new StreamObserver<Values>() {
				@Override
				public void onNext(Values value) {
					try {
						consumer.accept(value);
					} catch (Throwable ex) {
						future.completeExceptionally(ex);
					}
				}

				@Override
				public void onError(Throwable t) {
					future.completeExceptionally(t);
				}

				@Override
				public void onCompleted() {
					future.complete(true);
				}
			});
			return future;
		}

		public CompletableFuture<Boolean> sendMergeReq(Merge merge) {
			CompletableFuture<Boolean> future = new CompletableFuture<>();
			streamClient.send(merge, new StreamObserver<Response>() {
				@Override
				public void onNext(Response value) {
					future.complete(value.getSuccess());
				}

				@Override
				public void onError(Throwable t) {
					future.complete(false);
				}

				@Override
				public void onCompleted() {
				}
			});
			return future;
		}

		public void pullByChecksum(Checksum checksum) {
			observerOnCheckSum.onNext(checksum);
		}

		public void query(Query request) {
			observerOnQuery.onNext(request);
		}

		public boolean isReady() {
			return Objects.equals(ConnectivityState.READY, channel.getState(true));
		}

		public void close() {
			observerOnCheckSum.onCompleted();
			observerOnQuery.onCompleted();
			channel.shutdown();
		}
	}

}

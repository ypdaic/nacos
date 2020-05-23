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

import com.alibaba.nacos.consistency.IdGenerator;
import com.alibaba.nacos.core.distributed.distro.grpc.Checksum;
import com.alibaba.nacos.core.distributed.distro.grpc.Distro;
import com.alibaba.nacos.core.distributed.distro.grpc.DistroServiceGrpc;
import com.alibaba.nacos.core.distributed.distro.grpc.Request;
import com.alibaba.nacos.core.distributed.distro.grpc.Response;
import com.alibaba.nacos.core.distributed.distro.grpc.Value;
import com.alibaba.nacos.core.distributed.distro.utils.DistroUtils;
import com.alibaba.nacos.core.distributed.id.SnakeFlowerIdGenerator;
import com.alibaba.nacos.core.utils.Loggers;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public class DistroClient {

	private DistroServiceGrpc.DistroServiceStub client;
	private final ManagedChannel channel;

	private StreamObserver<Value> observerOnSync;
	private StreamObserver<Checksum> observerOnCheckSum;
	private StreamObserver<Request> observerOnAcquire;

	private ConcurrentHashMap<String, CompletableFuture<Response>> futureMap = new ConcurrentHashMap<>(16);

	public DistroClient(final String ip, final int port) {
		channel = ManagedChannelBuilder.forAddress(ip, port).usePlaintext()
				.build();
		client = DistroServiceGrpc.newStub(channel);

		init();
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

			}
		});

		observerOnAcquire = client.acquire(new StreamObserver<Response>() {
			@Override
			public void onNext(Response response) {
				final String requestId = response.getExtendInfoOrThrow(DistroUtils.REQUEST_ID_KEY);
				CompletableFuture<Response> future = futureMap.get(requestId);
				Objects.requireNonNull(future, "No corresponding request ID exists");
				future.complete(response);
			}

			@Override
			public void onError(Throwable throwable) {

			}

			@Override
			public void onCompleted() {

			}
		});
	}

	public boolean isReady() {
		return Objects.equals(ConnectivityState.READY, channel.getState(true));
	}

	public void transportRemote(Value value) {
		observerOnSync.onNext(value);
	}

	public void transportCheckSum(Checksum checksum) {
		observerOnCheckSum.onNext(checksum);
	}

	public CompletableFuture<Response> acquire(Request request) {
		final String requestId = request.getExtendInfoOrThrow(DistroUtils.REQUEST_ID_KEY);
		futureMap.computeIfAbsent(requestId, s -> new CompletableFuture<>());
		observerOnAcquire.onNext(request);
		return futureMap.get(requestId);
	}

	public void close() {
		observerOnSync.onCompleted();
		observerOnCheckSum.onCompleted();
		observerOnAcquire.onCompleted();
		channel.shutdown();
	}
}

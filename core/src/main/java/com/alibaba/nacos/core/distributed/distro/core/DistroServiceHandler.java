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

import com.alibaba.nacos.core.distributed.distro.grpc.Checksum;
import com.alibaba.nacos.core.distributed.distro.grpc.DistroServiceGrpc;
import com.alibaba.nacos.core.distributed.distro.grpc.Load;
import com.alibaba.nacos.core.distributed.distro.grpc.Merge;
import com.alibaba.nacos.core.distributed.distro.grpc.Query;
import com.alibaba.nacos.core.distributed.distro.grpc.Record;
import com.alibaba.nacos.core.distributed.distro.grpc.Response;
import com.alibaba.nacos.core.distributed.distro.grpc.Values;
import com.alibaba.nacos.core.utils.Loggers;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.Map;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public class DistroServiceHandler extends DistroServiceGrpc.DistroServiceImplBase {

	private final DistroServer server;
	private final MultiKvStorage store;

	public DistroServiceHandler(DistroServer server, MultiKvStorage storage) {
		this.server = server;
		this.store = storage;
	}

	@Override
	public void load(Load request, StreamObserver<Values> responseObserver) {
		Map<String, Map<String, Record>> snapshot = store.snapshotRead();
		snapshot.forEach((group, records) -> responseObserver.onNext(Values.newBuilder()
				.setGroup(group)
				.putAllData(records)
				.build()));
		responseObserver.onCompleted();
	}

	@Override
	public void send(Merge request, StreamObserver<Response> responseObserver) {
		final String group = request.getGroup();
		try {
			server.onReceiveRemote(group, request.getDataMap());
			responseObserver.onNext(Response.newBuilder().setGroup(group).setSuccess(true).build());
		} catch (Throwable ex) {
			Loggers.DISTRO.error("An exception occurred to merge the data of the node {} : {}", request.getOrigin(), ex);
			responseObserver.onNext(Response.newBuilder().setGroup(group)
					.setSuccess(false).build());
		}
	}

	@Override
	public StreamObserver<Checksum> receive(StreamObserver<Response> responseObserver) {
		return new StreamObserver<Checksum>() {
			@Override
			public void onNext(Checksum value) {
				final String group = value.getGroup();
				final String remoteServer = value.getOrigin();
				final Map<String, String> checksumMap = value.getDataMap();
				server.onReceiveChecksums(group, checksumMap, remoteServer);

				Response response = Response.newBuilder()
						.setGroup(group)
						.build();

				responseObserver.onNext(response);
			}

			@Override
			public void onError(Throwable t) {

			}

			@Override
			public void onCompleted() {

			}
		};
	}

	@Override
	public StreamObserver<Query> query(StreamObserver<Values> responseObserver) {
		return new StreamObserver<Query>() {
			@Override
			public void onNext(Query value) {
				final String group = value.getGroup();
				final List<String> query = value.getKeysList();
				Map<String, Record> result = store.batchGet(group, query);
				Values response = Values.newBuilder().setGroup(group).putAllData(result).build();
				responseObserver.onNext(response);
			}

			@Override
			public void onError(Throwable t) {
			}

			@Override
			public void onCompleted() {
			}
		};
	}

}

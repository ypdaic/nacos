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

import com.alibaba.nacos.consistency.entity.Log;
import com.alibaba.nacos.core.distributed.distro.grpc.Checksum;
import com.alibaba.nacos.core.distributed.distro.grpc.DistroServiceGrpc;
import com.alibaba.nacos.core.distributed.distro.grpc.Record;
import com.alibaba.nacos.core.distributed.distro.grpc.Request;
import com.alibaba.nacos.core.distributed.distro.grpc.Response;
import com.alibaba.nacos.core.distributed.distro.grpc.Value;
import com.alibaba.nacos.core.distributed.distro.utils.DistroUtils;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.Map;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public class DistroServiceHandler extends DistroServiceGrpc.DistroServiceImplBase {

	private final byte[] success = new byte[] {0};
	private final byte[] failed = new byte[] {1};

	private final DistroServer server;
	private final KvStorage store;

	public DistroServiceHandler(DistroServer server, KvStorage storage) {
		this.server = server;
		this.store = storage;
	}

	@Override
	public StreamObserver<Value> onSync(StreamObserver<Response> responseObserver) {

		return new StreamObserver<Value>() {
			@Override
			public void onNext(Value value) {
				final String group = value.getGroup();
				final Map<String, Record> remoteData = value.getDataMap();
				remoteData.forEach((key, record) -> {
					final Log log = Log.newBuilder()
							.setGroup(group)
							.setKey(key)
							.setData(record.getData())
							.build();
					server.innerApply(group, key, record, log);
				});

				Response response = Response.newBuilder()
						.setGroup(group)
						.setData(ByteString.copyFrom(success))
						.putExtendInfo(DistroUtils.REQUEST_ID_KEY, value.getExtendInfoOrThrow(DistroUtils.REQUEST_ID_KEY))
						.build();

				responseObserver.onNext(response);
			}

			@Override
			public void onError(Throwable t) {
				responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withDescription(t.getMessage())));
			}

			@Override
			public void onCompleted() {
				responseObserver.onCompleted();
			}
		};
	}

	@Override
	public StreamObserver<Checksum> syncCheckSum(
			StreamObserver<Response> responseObserver) {

		return new StreamObserver<Checksum>() {
			@Override
			public void onNext(Checksum value) {
				final String group = value.getGroup();
				final Map<String, String> checksumMap = value.getDataMap();
				final String remoteServer = value.getDataOrThrow(DistroUtils.REMOTE_SERVER_KEY);
				server.onReceiveChecksums(group, checksumMap, remoteServer);

				Response response = Response.newBuilder()
						.setGroup(group)
						.setData(ByteString.copyFrom(success))
						.putExtendInfo(DistroUtils.REQUEST_ID_KEY, value.getExtendInfoOrThrow(DistroUtils.REQUEST_ID_KEY))
						.build();

				responseObserver.onNext(response);
			}

			@Override
			public void onError(Throwable t) {
				responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withDescription(t.getMessage())));
			}

			@Override
			public void onCompleted() {
			}
		};
	}

	@Override
	public StreamObserver<Request> acquire(StreamObserver<Value> responseObserver) {

		return new StreamObserver<Request>() {
			@Override
			public void onNext(Request value) {
				final String group = value.getGroup();
				final List<String> query = value.getKeysList();
				Map<String, Record> result = store.batchGet(group, query);
				Value response = Value.newBuilder()
						.setGroup(group)
						.putAllData(result)
						.putExtendInfo(DistroUtils.REQUEST_ID_KEY, value.getExtendInfoOrThrow(DistroUtils.REQUEST_ID_KEY))
						.build();

				responseObserver.onNext(response);
			}

			@Override
			public void onError(Throwable t) {
				responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withDescription(t.getMessage())));
			}

			@Override
			public void onCompleted() {
				responseObserver.onCompleted();
			}
		};
	}
}

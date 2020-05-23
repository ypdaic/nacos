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
import com.alibaba.nacos.core.distributed.distro.grpc.Request;
import com.alibaba.nacos.core.distributed.distro.grpc.Response;
import com.alibaba.nacos.core.distributed.distro.grpc.Value;
import io.grpc.stub.StreamObserver;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public class DistroServiceHandler extends DistroServiceGrpc.DistroServiceImplBase {

	private final KvStorage storage;

	public DistroServiceHandler(KvStorage storage) {
		this.storage = storage;
	}

	@Override
	public StreamObserver<Value> onSync(StreamObserver<Response> responseObserver) {
		return new StreamObserver<Value>() {
			@Override
			public void onNext(Value value) {

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
	public StreamObserver<Checksum> syncCheckSum(
			StreamObserver<Response> responseObserver) {
		return new StreamObserver<Checksum>() {
			@Override
			public void onNext(Checksum value) {

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
	public StreamObserver<Request> acquire(StreamObserver<Response> responseObserver) {
		return new StreamObserver<Request>() {
			@Override
			public void onNext(Request value) {

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

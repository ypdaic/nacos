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
import com.alibaba.nacos.common.utils.ThreadUtils;
import com.alibaba.nacos.consistency.IdGenerator;
import com.alibaba.nacos.consistency.entity.Log;
import com.alibaba.nacos.consistency.entity.Response;
import com.alibaba.nacos.core.distributed.distro.DsitroConfig;
import com.alibaba.nacos.core.distributed.distro.utils.DistroExecutor;
import com.alibaba.nacos.core.distributed.distro.utils.DistroUtils;
import com.alibaba.nacos.core.distributed.distro.utils.ErrorCode;
import com.alibaba.nacos.core.distributed.id.SnakeFlowerIdGenerator;
import io.grpc.BindableService;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.util.MutableHandlerRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
@SuppressWarnings("all")
public class DistroServer {

	private final int port;

	private final DsitroConfig config;

	private final Map<String, DistroClient> clientMap = new ConcurrentHashMap<>();

	private final AtomicBoolean start = new AtomicBoolean(false);

	private final KvStorage storage;

	private io.grpc.Server server;

	private MutableHandlerRegistry handlerRegistry = new MutableHandlerRegistry();

	private ConcurrentHashMap<BindableService, ServerServiceDefinition> serviceInfo = new ConcurrentHashMap<BindableService,
			ServerServiceDefinition>();

	private IdGenerator generator = new SnakeFlowerIdGenerator();

	public DistroServer(DsitroConfig config) {
		this.config = config;
		this.storage = new KvStorage();
		this.port = Integer.parseInt(config.getSelfMember().split(":")[1]);
		registerService(new DistroServiceHandler(this.storage));
		connectRemoteMember();
	}

	public void start() {
		if (!start.compareAndSet(false, true)) {
			return;
		}
		this.server = NettyServerBuilder.forPort(port)
				.intercept(new ServerInterceptor() {
					@Override
					public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
							ServerCall<ReqT, RespT> serverCall, Metadata metadata,
							ServerCallHandler<ReqT, RespT> next) {
						ServerCall.Listener<ReqT> reqTListener = next.startCall(serverCall, metadata);
						return new ExceptionListener(reqTListener, serverCall);
					}
				})
				.fallbackHandlerRegistry(handlerRegistry).build();
		try {
			server.start();
		} catch (Throwable e) {
			throw new NacosUncheckException(ErrorCode.DISTRO_START_FAILED.getCode(), ErrorCode.DISTRO_START_FAILED.getErrMsg(), e);
		}
	}

	public void registerService(Object ref) {
		BindableService bindableService = (BindableService) ref;
		ServerServiceDefinition serverServiceDefinition = bindableService.bindService();
		serviceInfo.put(bindableService, serverServiceDefinition);
		handlerRegistry.addService(serverServiceDefinition);
	}

	public void onMemberChange(Set<String> newMembers) {

	}

	Response apply(Log log) {
		return Response.newBuilder().build();
	}

	public void shutdown() {
		if (!start.compareAndSet(true, false)) {
			return;
		}
		server.shutdown();
	}

	private void connectRemoteMember() {
		List<String> members = new ArrayList<>(config.getMembers());
		members.forEach(member ->{
			final String[] inet = member.split(":");
			final String ip = inet[0];
			final int port = Integer.parseInt(inet[1]);
			DistroExecutor.executeByCommon(() -> {
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

	private static class ExceptionListener extends ServerCall.Listener {

		private final ServerCall.Listener delegate;
		private final ServerCall call;

		public ExceptionListener(final ServerCall.Listener delegate, final ServerCall call) {
			this.delegate = delegate;
			this.call = call;
		}

		@Override
		public void onMessage(Object message) {
			delegate.onMessage(message);
		}

		@Override
		public void onHalfClose() {
			try {
				this.delegate.onHalfClose();
			} catch (Exception t) {
				StatusRuntimeException exception = DistroUtils.fromThrowable(t);
				call.close(exception.getStatus(), exception.getTrailers());
			}
		}

		@Override
		public void onCancel() {
			delegate.onCancel();
		}

		@Override
		public void onComplete() {
			delegate.onComplete();
		}

		@Override
		public void onReady() {
			delegate.onReady();
		}
	}

}

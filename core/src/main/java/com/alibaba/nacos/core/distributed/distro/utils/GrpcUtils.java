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
package com.alibaba.nacos.core.distributed.distro.utils;

import com.google.protobuf.Message;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.MethodDescriptor;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public final class GrpcUtils {

	public static String FIXED_METHOD_NAME = "_call";

	private static final Map<String, Message> parserClasses = new HashMap<>(8);
	private static final Map<String, Message> responseRecord = new HashMap<>(8);

	private GrpcUtils() {}

	public static void registerProtobufSerializer(final Message message) {
		parserClasses.put(message.getClass().getName(), message);
	}

	public static void registerRequest2Response(final String reqName, final Message resp) {
		responseRecord.put(reqName, resp);
	}

	public static  <Req extends Message, Resp extends Message> void asyncUnaryCall(final Channel channel, Req request, BiConsumer<Resp, Throwable> consumer, final Runnable onComplete) {
		final MethodDescriptor<Message, Message> method = getCallMethod(request);
		ClientCalls.asyncUnaryCall(channel.newCall(method, CallOptions.DEFAULT), request,
				new StreamObserver<Message>() {

					@Override
					public void onNext(final Message value) {
						try {
							consumer.accept((Resp) value, null);
						} catch (ClassCastException ex) {
							consumer.accept(null, ex);
						}
					}

					@Override
					public void onError(final Throwable throwable) {
						consumer.accept(null, throwable);
					}

					@Override
					public void onCompleted() {
						onComplete.run();
					}
				});
	}

	private static MethodDescriptor<Message, Message> getCallMethod(final Object request) {
		final String interest = request.getClass().getName();
		final Message reqIns = Objects
				.requireNonNull(parserClasses.get(interest), "null default instance: "
				+ interest);
		return MethodDescriptor
				.<Message, Message> newBuilder()
				.setType(MethodDescriptor.MethodType.UNARY)
				.setFullMethodName(MethodDescriptor.generateFullMethodName(interest, FIXED_METHOD_NAME)) //
				.setRequestMarshaller(ProtoUtils.marshaller(reqIns)) //
				.setResponseMarshaller(
						ProtoUtils.marshaller(responseRecord.get(interest))) //
				.build();
	}

}

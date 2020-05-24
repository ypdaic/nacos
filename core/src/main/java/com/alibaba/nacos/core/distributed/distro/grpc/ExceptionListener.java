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
package com.alibaba.nacos.core.distributed.distro.grpc;

import com.alibaba.nacos.core.distributed.distro.utils.DistroUtils;
import io.grpc.ServerCall;
import io.grpc.StatusRuntimeException;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public class ExceptionListener extends ServerCall.Listener {

	private final ServerCall.Listener delegate;
	private final ServerCall call;

	public ExceptionListener(final ServerCall.Listener delegate,
			final ServerCall call) {
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
		}
		catch (Exception t) {
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
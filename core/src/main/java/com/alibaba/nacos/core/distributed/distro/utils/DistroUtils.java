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

import com.alibaba.nacos.core.distributed.distro.exception.ExtendedStatusRuntimeException;
import com.google.common.base.Preconditions;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

import static io.grpc.Status.UNKNOWN;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public final class DistroUtils {

	private DistroUtils() {}

	public static final String REQUEST_ID_KEY = "requestId";

	public static ExtendedStatusRuntimeException fromThrowable(Throwable t) {
		Throwable cause = Preconditions.checkNotNull(t);
		// 循环渐进，逐层检查
		while (cause != null) {
			if (cause instanceof StatusException) {
				//StatusException就直接取status属性
				return new ExtendedStatusRuntimeException(((StatusException) cause).getStatus());
			} else if (cause instanceof StatusRuntimeException) {
				//StatusRuntimeException也是直接取status属性
				return new ExtendedStatusRuntimeException(((StatusException) cause).getStatus());
			}
			//不是的话就继续检查cause
			cause = cause.getCause();
		}

		//最后如果还是找不到任何Status，就只能给 UNKNOWN
		return new ExtendedStatusRuntimeException(UNKNOWN.withCause(t));
	}

}

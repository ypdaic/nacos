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

import com.alibaba.nacos.common.executor.ExecutorFactory;
import com.alibaba.nacos.common.executor.NameThreadFactory;
import com.alibaba.nacos.core.distributed.distro.DsitroConfig;
import com.alibaba.nacos.core.distributed.raft.JRaftServer;

import java.util.concurrent.ScheduledExecutorService;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public final class DistroExecutor {

	public static final String GROUP = DistroExecutor.class.getName();

	private static ScheduledExecutorService distroCommonExecutor;

	private static final String OWNER = JRaftServer.class.getName();

	private DistroExecutor() {
	}

	public static void init(DsitroConfig config) {

		distroCommonExecutor = ExecutorFactory.newScheduledExecutorService(GROUP, 8,
				new NameThreadFactory(
						"com.alibaba.nacos.core.protocol.distro-common"));


	}

	public static void executeByCommon(Runnable r) {
		distroCommonExecutor.execute(r);
	}

}

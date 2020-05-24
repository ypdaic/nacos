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
import com.alibaba.nacos.core.distributed.distro.DistroConfig;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public final class DistroExecutor {

	public static final String GROUP = DistroExecutor.class.getName();

	private static ScheduledExecutorService distroCommonExecutor;

	private static ScheduledExecutorService dataSyncExecutor;

	private static ScheduledExecutorService taskDispatchExecutor;

	private static long PARTITION_DATA_TIMED_SYNC_INTERVAL = TimeUnit.SECONDS.toMillis(5);

	private DistroExecutor() {
	}

	public static void init(DistroConfig config) {

		distroCommonExecutor = ExecutorFactory.newScheduledExecutorService(GROUP, 8,
				new NameThreadFactory("com.alibaba.nacos.core.protocol.distro-common"));

		dataSyncExecutor = ExecutorFactory.newScheduledExecutorService(GROUP,
				Runtime.getRuntime().availableProcessors(),
				new NameThreadFactory("com.alibaba.nacos.core.protocol.distro.data-syncer"));

		taskDispatchExecutor = ExecutorFactory.newScheduledExecutorService(GROUP, Runtime.getRuntime().availableProcessors(),
				new NameThreadFactory("com.alibaba.nacos.naming.distro.task.dispatcher"));
	}

	public static void executeByCommon(Runnable r) {
		distroCommonExecutor.execute(r);
	}

	public static void submitDataSync(Runnable runnable, long delay) {
		dataSyncExecutor.schedule(runnable, delay, TimeUnit.MILLISECONDS);
	}

	public static void schedulePartitionDataTimedSync(Runnable runnable) {
		dataSyncExecutor.scheduleWithFixedDelay(runnable, PARTITION_DATA_TIMED_SYNC_INTERVAL,
				PARTITION_DATA_TIMED_SYNC_INTERVAL, TimeUnit.MILLISECONDS);
	}

	public static void submitTaskDispatch(Runnable runnable) {
		taskDispatchExecutor.submit(runnable);
	}

}

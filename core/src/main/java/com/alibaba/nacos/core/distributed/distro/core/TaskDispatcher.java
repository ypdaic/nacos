/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.nacos.core.distributed.distro.core;

import com.alibaba.nacos.common.utils.ConvertUtils;
import com.alibaba.nacos.common.utils.ExceptionUtil;
import com.alibaba.nacos.common.utils.LoggerUtils;
import com.alibaba.nacos.common.utils.Pair;
import com.alibaba.nacos.common.utils.ThreadUtils;
import com.alibaba.nacos.core.distributed.distro.DistroConfig;
import com.alibaba.nacos.core.distributed.distro.DistroSysConstants;
import com.alibaba.nacos.core.distributed.distro.utils.DistroExecutor;
import com.alibaba.nacos.core.utils.ApplicationUtils;
import com.alibaba.nacos.core.utils.Loggers;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Data sync task dispatcher
 *
 * @author nkorange
 * @since 1.0.0
 */
public class TaskDispatcher {

	private List<TaskScheduler> taskSchedulerList = new ArrayList<>();

	private final int cpuCoreCount = Runtime.getRuntime().availableProcessors();

	private final DataSyncer dataSyncer;

	private final DistroConfig config;

	private volatile boolean isShutdown = false;

	public TaskDispatcher(DataSyncer dataSyncer, DistroConfig config) {
		this.dataSyncer = dataSyncer;
		this.config = config;
	}

	public void start() {
		for (int i = 0; i < cpuCoreCount; i++) {
			TaskScheduler taskScheduler = new TaskScheduler(i);
			taskSchedulerList.add(taskScheduler);
			DistroExecutor.submitTaskDispatch(taskScheduler);
		}
	}

	public void shutdown() {
		isShutdown = true;
	}

	public void addTask(final String group, final String key) {
		taskSchedulerList.get(ThreadUtils.shakeUp(key, cpuCoreCount)).addTask(group, key);
	}

	public class TaskScheduler implements Runnable {

		private int index;

		private int dataSize = 0;

		private long lastDispatchTime = 0L;

		private BlockingQueue<Pair<String, String>> queue = new LinkedBlockingQueue<>(
				128 * 1024);

		public TaskScheduler(int index) {
			this.index = index;
		}

		public void addTask(final String group, final String key) {
			try {
				queue.put(Pair.with(group, key));
			}
			catch (InterruptedException ignore) {
			}
		}

		public int getIndex() {
			return index;
		}

		@Override
		public void run() {
			List<String> keys = new ArrayList<>();
			for (; ; ) {
				if (isShutdown) {
					return;
				}
				try {
					Pair<String, String> pair = queue.take();
					final String group = pair.getFirst();
					final String key = pair.getSecond();

					if (config.getMembers().isEmpty() || StringUtils.isBlank(key)) {
						continue;
					}

					LoggerUtils
							.printIfDebugEnabled(Loggers.DISTRO, "{} got key: {}", group,
									key);

					if (dataSize == 0) {
						keys = new ArrayList<>(64);
					}

					keys.add(key);
					dataSize++;

					if (dataSize == ConvertUtils.toInt(config
									.getVal(DistroSysConstants.BATCH_SYNC_KEY_COUNT_KEY),
							DistroSysConstants.DEFAULT_BATCH_SYNC_KEY_COUNT)
							|| (System.currentTimeMillis() - lastDispatchTime)
							> ConvertUtils.toInt(config
									.getVal(DistroSysConstants.TASK_DISPATCH_PERIOD_KEY),
							DistroSysConstants.DEFAULT_TASK_DISPATCH_PERIOD)) {

						for (String member : config.getMembers()) {
							if (Objects
									.equals(ApplicationUtils.getLocalAddress(), member)) {
								continue;
							}
							SyncTask syncTask = SyncTask.builder().group(group).keys(keys)
									.targetServer(member).build();

							LoggerUtils.printIfDebugEnabled(Loggers.DISTRO,
									"add sync task: {}", syncTask);

							dataSyncer.submit(syncTask, 0);
						}
						lastDispatchTime = System.currentTimeMillis();
						dataSize = 0;
					}

				}
				catch (Throwable e) {
					Loggers.DISTRO.error("dispatch sync task failed. {}",
							ExceptionUtil.getStackTrace(e));
				}
			}
		}
	}
}

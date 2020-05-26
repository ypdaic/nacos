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
package com.alibaba.nacos.core.distributed.distro;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public final class DistroSysConstants {

	public static final String TASK_DISPATCH_PERIOD_KEY = "taskDispatchPeriod";

	public static final String BATCH_SYNC_KEY_COUNT_KEY = "batchSyncKeyCount";
	
	public static final String SYNC_RETRY_DELAY_KEY = "syncRetryDelay";
	
	public static final String LOAD_DATA_RETRY_DELAY_MILLIS_KEY = "loadDataRetryDelayMillis";

	public static int DEFAULT_TASK_DISPATCH_PERIOD = 2000;

	public static int DEFAULT_BATCH_SYNC_KEY_COUNT = 1000;

	public static long DEFAULT_SYNC_RETRY_DELAY = 5000L;

	public static long DEFAULT_LOAD_DATA_RETRY_DELAY_MILLIS = 30000;
	
}

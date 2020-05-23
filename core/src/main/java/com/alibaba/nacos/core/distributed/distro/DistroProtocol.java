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

import com.alibaba.nacos.consistency.LogProcessor;
import com.alibaba.nacos.consistency.ap.APProtocol;
import com.alibaba.nacos.consistency.ap.LogProcessor4AP;
import com.alibaba.nacos.consistency.entity.GetRequest;
import com.alibaba.nacos.consistency.entity.Log;
import com.alibaba.nacos.consistency.entity.Response;
import com.alibaba.nacos.core.distributed.AbstractConsistencyProtocol;
import com.alibaba.nacos.core.distributed.distro.core.DistroServer;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public class DistroProtocol
		extends AbstractConsistencyProtocol<DsitroConfig, LogProcessor4AP>
		implements APProtocol<DsitroConfig, LogProcessor4AP> {

	private DistroServer server;

	@Override
	public void init(DsitroConfig config) {
		server = new DistroServer(config);
		server.start();
	}

	@Override
	public void addLogProcessors(Collection<LogProcessor4AP> processors) {
		loadLogProcessor(processors);
	}

	@Override
	public Response getData(GetRequest request) throws Exception {
		final String group = request.getGroup();
		LogProcessor processor = findProcessor(group);
		Objects.requireNonNull(processor, "There is no corresponding processor for " + group);
		return processor.getData(request);
	}

	@Override
	public Response submit(Log data) throws Exception {
		return null;
	}

	@Override
	public CompletableFuture<Response> submitAsync(Log data) {
		return null;
	}

	@Override
	public void memberChange(Set<String> addresses) {
		server.onMemberChange(addresses);
	}

	@Override
	public void shutdown() {
		server.shutdown();
	}
}

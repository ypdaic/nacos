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
package com.alibaba.nacos.naming.consistency.ephemeral.distro;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.core.distributed.ProtocolManager;
import com.alibaba.nacos.naming.cluster.ServerStatus;
import com.alibaba.nacos.naming.consistency.ApplyAction;
import com.alibaba.nacos.naming.consistency.Datum;
import com.alibaba.nacos.naming.consistency.KeyBuilder;
import com.alibaba.nacos.naming.consistency.RecordListener;
import com.alibaba.nacos.naming.consistency.ephemeral.EphemeralConsistencyService;
import com.alibaba.nacos.naming.core.Instances;
import com.alibaba.nacos.naming.misc.GlobalConfig;
import com.alibaba.nacos.naming.misc.GlobalExecutor;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.SwitchDomain;
import com.alibaba.nacos.naming.pojo.Record;
import org.apache.commons.lang3.StringUtils;
import org.javatuples.Pair;
import org.springframework.context.annotation.DependsOn;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A consistency protocol algorithm called <b>Distro</b>
 * <p>
 * Use a distro algorithm to divide data into many blocks. Each Nacos server node takes
 * responsibility for exactly one block of data. Each block of data is generated, removed
 * and synchronized by its responsible server. So every Nacos server only handles writings
 * for a subset of the total service data.
 * <p>
 * At mean time every Nacos server receives data sync of other Nacos server, so every Nacos
 * server will eventually have a complete set of data.
 *
 * @author nkorange
 * @since 1.0.0
 */
@DependsOn("ProtocolManager")
@org.springframework.stereotype.Service("distroConsistencyService")
public class DistroConsistencyServiceImpl implements EphemeralConsistencyService {


	private final ProtocolManager protocolManager;
	private final DataStore dataStore;
	private final SwitchDomain switchDomain;
	private final GlobalConfig globalConfig;

	private boolean initialized = false;
	private volatile Notifier notifier = new Notifier();
	private Map<String, CopyOnWriteArrayList<RecordListener>> listeners = new ConcurrentHashMap<>();

	public DistroConsistencyServiceImpl(ProtocolManager protocolManager, DataStore dataStore, SwitchDomain switchDomain,
			GlobalConfig globalConfig) {
		this.protocolManager = protocolManager;
		this.dataStore = dataStore;
		this.switchDomain = switchDomain;
		this.globalConfig = globalConfig;
	}

	@PostConstruct
	public void init() {
		GlobalExecutor.submitDistroNotifyTask(notifier);
	}

	@Override
	public void put(String key, Record value) throws NacosException {
		onPut(key, value);
	}

	@Override
	public void remove(String key) throws NacosException {
		onRemove(key);
		listeners.remove(key);
	}

	@Override
	public Datum get(String key) throws NacosException {
		return dataStore.get(key);
	}

	public void onPut(String key, Record value) {

		if (KeyBuilder.matchEphemeralInstanceListKey(key)) {
			Datum<Instances> datum = new Datum<>();
			datum.value = (Instances) value;
			datum.key = key;
			datum.timestamp.incrementAndGet();
			dataStore.put(key, datum);
		}

		if (!listeners.containsKey(key)) {
			return;
		}

		notifier.addTask(key, ApplyAction.CHANGE);
	}

	public void onRemove(String key) {

		dataStore.remove(key);

		if (!listeners.containsKey(key)) {
			return;
		}

		notifier.addTask(key, ApplyAction.DELETE);
	}

	@Override
	public void listen(String key, RecordListener listener) throws NacosException {
		if (!listeners.containsKey(key)) {
			listeners.put(key, new CopyOnWriteArrayList<>());
		}

		if (listeners.get(key).contains(listener)) {
			return;
		}

		listeners.get(key).add(listener);
	}

	@Override
	public void unlisten(String key, RecordListener listener) throws NacosException {
		if (!listeners.containsKey(key)) {
			return;
		}
		for (RecordListener recordListener : listeners.get(key)) {
			if (recordListener.equals(listener)) {
				listeners.get(key).remove(listener);
				break;
			}
		}
	}

	@Override
	public boolean isAvailable() {
		return isInitialized() || ServerStatus.UP.name()
				.equals(switchDomain.getOverriddenServerStatus());
	}

	public boolean isInitialized() {
		return initialized || !globalConfig.isDataWarmup();
	}

	public class Notifier implements Runnable {

		private ConcurrentHashMap<String, String> services = new ConcurrentHashMap<>(
				10 * 1024);

		private BlockingQueue<Pair<String, ApplyAction>> tasks = new ArrayBlockingQueue<>(
				1024 * 1024);

		public void addTask(String datumKey, ApplyAction action) {

			if (services.containsKey(datumKey) && action == ApplyAction.CHANGE) {
				return;
			}
			if (action == ApplyAction.CHANGE) {
				services.put(datumKey, StringUtils.EMPTY);
			}
			tasks.offer(Pair.with(datumKey, action));
		}

		public int getTaskSize() {
			return tasks.size();
		}

		@Override
		public void run() {
			Loggers.DISTRO.info("distro notifier started");

			for ( ; ; ) {
				try {
					Pair<String, ApplyAction> pair = tasks.take();
                    handle(pair);
				}
				catch (Throwable e) {
					Loggers.DISTRO
							.error("[NACOS-DISTRO] Error while handling notifying task",
									e);
				}
			}
		}

		private void handle(Pair<String, ApplyAction> pair) {
			try {
				String datumKey = pair.getValue0();
				ApplyAction action = pair.getValue1();

				services.remove(datumKey);

				int count = 0;

				if (!listeners.containsKey(datumKey)) {
					return;
				}

				for (RecordListener listener : listeners.get(datumKey)) {

					count++;

					try {
						if (action == ApplyAction.CHANGE) {
							listener.onChange(datumKey, dataStore.get(datumKey).value);
							continue;
						}

						if (action == ApplyAction.DELETE) {
							listener.onDelete(datumKey);
							continue;
						}
					}
					catch (Throwable e) {
						Loggers.DISTRO
								.error("[NACOS-DISTRO] error while notifying listener of key: {}",
										datumKey, e);
					}
				}

				if (Loggers.DISTRO.isDebugEnabled()) {
					Loggers.DISTRO
							.debug("[NACOS-DISTRO] datum change notified, key: {}, listener count: {}, action: {}",
									datumKey, count, action.name());
				}
			}
			catch (Throwable e) {
				Loggers.DISTRO
						.error("[NACOS-DISTRO] Error while handling notifying task", e);
			}
		}
	}
}

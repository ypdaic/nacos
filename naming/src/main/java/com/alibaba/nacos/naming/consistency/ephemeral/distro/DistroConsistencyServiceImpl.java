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

import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.core.cluster.Member;
import com.alibaba.nacos.core.cluster.ServerMemberManager;
import com.alibaba.nacos.core.utils.ApplicationUtils;
import com.alibaba.nacos.naming.cluster.ServerStatus;
import com.alibaba.nacos.naming.cluster.transport.Serializer;
import com.alibaba.nacos.naming.consistency.ApplyAction;
import com.alibaba.nacos.naming.consistency.Datum;
import com.alibaba.nacos.naming.consistency.KeyBuilder;
import com.alibaba.nacos.naming.consistency.RecordListener;
import com.alibaba.nacos.naming.consistency.ephemeral.EphemeralConsistencyService;
import com.alibaba.nacos.naming.core.DistroMapper;
import com.alibaba.nacos.naming.core.Instances;
import com.alibaba.nacos.naming.core.Service;
import com.alibaba.nacos.naming.misc.GlobalConfig;
import com.alibaba.nacos.naming.misc.GlobalExecutor;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.NamingProxy;
import com.alibaba.nacos.naming.misc.NetUtils;
import com.alibaba.nacos.naming.misc.SwitchDomain;
import com.alibaba.nacos.naming.pojo.Record;
import org.apache.commons.lang3.StringUtils;
import org.javatuples.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
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

	@Autowired
	private DistroMapper distroMapper;

	@Autowired
	private DataStore dataStore;

	@Autowired
	private TaskDispatcher taskDispatcher;

	@Autowired
	private Serializer serializer;

	@Autowired
	private ServerMemberManager memberManager;

	@Autowired
	private SwitchDomain switchDomain;

	@Autowired
	private GlobalConfig globalConfig;

	private boolean initialized = false;

	private volatile Notifier notifier = new Notifier();

	private LoadDataTask loadDataTask = new LoadDataTask();

	private Map<String, CopyOnWriteArrayList<RecordListener>> listeners = new ConcurrentHashMap<>();

	private Map<String, String> syncChecksumTasks = new ConcurrentHashMap<>(16);

	@PostConstruct
	public void init() {
	    // 开启加载远端节点数据任务
		GlobalExecutor.submit(loadDataTask);
		// 开启事件通知任务
		GlobalExecutor.submitDistroNotifyTask(notifier);
	}

    /**
     * 集群模式下，从其他节点获取服务列表数据
     */
	private class LoadDataTask implements Runnable {

		@Override
		public void run() {
			try {
				load();
				if (!initialized) {
					GlobalExecutor
							.submit(this, globalConfig.getLoadDataRetryDelayMillis());
				}
			}
			catch (Exception e) {
				Loggers.DISTRO.error("load data failed.", e);
			}
		}
	}

	public void load() throws Exception {
		if (ApplicationUtils.getStandaloneMode()) {
			initialized = true;
			return;
		}
		// size = 1 means only myself in the list, we need at least one another server alive:
        // 等待其他节点，如果单机，这里就一直循环了
		while (memberManager.getServerList().size() <= 1) {
			Thread.sleep(1000L);
			Loggers.DISTRO.info("waiting server list init...");
		}

		for (Map.Entry<String, Member> entry : memberManager.getServerList().entrySet()) {
			final String address = entry.getValue().getAddress();
			// 如果是节点是自己就跳过
			if (NetUtils.localServer().equals(address)) {
				continue;
			}
			if (Loggers.DISTRO.isDebugEnabled()) {
				Loggers.DISTRO.debug("sync from " + address);
			}
			// try sync data from remote server:
            // 开始同步远端节点数据
			if (syncAllDataFromRemote(address)) {
				initialized = true;
				return;
			}
		}
	}

	@Override
	public void put(String key, Record value) throws NacosException {
		onPut(key, value);
        /**
         * 每添加一个服务实例，经过一段时间都会同步给其他节点
         * 只有客户的注册，心跳，更新，才会触发这里的任务派发
         * 客户端的注册，心跳会随机选择一个节点进行调用
         */
		taskDispatcher.addTask(key);
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

        /**
         * 发送服务变更通知
         */
		notifier.addTask(key, ApplyAction.CHANGE);
	}

	public void onRemove(String key) {

		dataStore.remove(key);

		if (!listeners.containsKey(key)) {
			return;
		}

		notifier.addTask(key, ApplyAction.DELETE);
	}

	public void onReceiveChecksums(Map<String, String> checksumMap, String server) {

		if (syncChecksumTasks.containsKey(server)) {
			// Already in process of this server:
			Loggers.DISTRO.warn("sync checksum task already in process with {}", server);
			return;
		}

		syncChecksumTasks.put(server, "1");

		try {

			List<String> toUpdateKeys = new ArrayList<>();
			List<String> toRemoveKeys = new ArrayList<>();
			for (Map.Entry<String, String> entry : checksumMap.entrySet()) {
				if (distroMapper.responsible(KeyBuilder.getServiceName(entry.getKey()))) {
					// this key should not be sent from remote server:
					Loggers.DISTRO.error("receive responsible key timestamp of " + entry
							.getKey() + " from " + server);
					// abort the procedure:
					return;
				}

				if (!dataStore.contains(entry.getKey())
						|| dataStore.get(entry.getKey()).value == null || !dataStore
						.get(entry.getKey()).value.getChecksum()
						.equals(entry.getValue())) {
					toUpdateKeys.add(entry.getKey());
				}
			}

			for (String key : dataStore.keys()) {

				if (!server.equals(distroMapper.mapSrv(KeyBuilder.getServiceName(key)))) {
					continue;
				}

				if (!checksumMap.containsKey(key)) {
					toRemoveKeys.add(key);
				}
			}

			if (Loggers.DISTRO.isDebugEnabled()) {
				Loggers.DISTRO.info("to remove keys: {}, to update keys: {}, source: {}",
						toRemoveKeys, toUpdateKeys, server);
			}

			for (String key : toRemoveKeys) {
				onRemove(key);
			}

			if (toUpdateKeys.isEmpty()) {
				return;
			}

			try {
				byte[] result = NamingProxy.getData(toUpdateKeys, server);
				processData(result);
			}
			catch (Exception e) {
				Loggers.DISTRO.error("get data from " + server + " failed!", e);
			}
		}
		finally {
			// Remove this 'in process' flag:
			syncChecksumTasks.remove(server);
		}

	}

	public boolean syncAllDataFromRemote(String server) {

		try {
			byte[] data = NamingProxy.getAllData(server);
			processData(data);
			return true;
		}
		catch (Exception e) {
			Loggers.DISTRO.error("sync full data from " + server + " failed!", e);
			return false;
		}
	}

    /**
     * 处理从远端节点获取的数据
     * @param data
     * @throws Exception
     */
	public void processData(byte[] data) throws Exception {
		if (data.length > 0) {
			Map<String, Datum<Instances>> datumMap = serializer
					.deserializeMap(data, Instances.class);

			for (Map.Entry<String, Datum<Instances>> entry : datumMap.entrySet()) {
				dataStore.put(entry.getKey(), entry.getValue());

                /**
                 * 如果本地listeners已经包含了这个key，说明不用同步了，否则进行同步
                 * listeners不包含这个key，说明该服务我本地没有，那么就需要同步该服务
                 * 这个判断是不明确的，因为可能其他服务接收到的心跳信息会转发到我这边，导致已经通过心跳添加了
                 * 心跳转发是通过这个DistroFilter过滤器处理的
                 *
                 */
				if (!listeners.containsKey(entry.getKey())) {
					// pretty sure the service not exist:
					if (switchDomain.isDefaultInstanceEphemeral()) {
						// create empty service
						Loggers.DISTRO.info("creating service {}", entry.getKey());
                        /**
                         * 开始创建一个新的服务
                         */
						Service service = new Service();
						String serviceName = KeyBuilder.getServiceName(entry.getKey());
						String namespaceId = KeyBuilder.getNamespace(entry.getKey());
						service.setName(serviceName);
						service.setNamespaceId(namespaceId);
						service.setGroupName(Constants.DEFAULT_GROUP);
						// now validate the service. if failed, exception will be thrown
						service.setLastModifiedMillis(System.currentTimeMillis());
						service.recalculateChecksum();
                        /**
                         * 这里监听器默认会有一个ServiceManager，由容器启动时ServiceManager的init方法添加
                         * 此时会触发ServiceManager的onChange 方法
                         */
						listeners.get(KeyBuilder.SERVICE_META_KEY_PREFIX).get(0).onChange(
								KeyBuilder.buildServiceMetaKey(namespaceId, serviceName),
								service);
					}
				}
			}

			for (Map.Entry<String, Datum<Instances>> entry : datumMap.entrySet()) {

				if (!listeners.containsKey(entry.getKey())) {
					// Should not happen:
					Loggers.DISTRO.warn("listener of {} not found.", entry.getKey());
					continue;
				}

				try {
					for (RecordListener listener : listeners.get(entry.getKey())) {
						listener.onChange(entry.getKey(), entry.getValue().value);
					}
				}
				catch (Exception e) {
					Loggers.DISTRO
							.error("[NACOS-DISTRO] error while execute listener of key: {}",
									entry.getKey(), e);
					continue;
				}

				// Update data store if listener executed successfully:
				dataStore.put(entry.getKey(), entry.getValue());
			}
		}
	}

    /**
     * 注册listen，相同的key会存在多个listen
     * @param key      key of data
     * @param listener callback of data change
     * @throws NacosException
     */
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

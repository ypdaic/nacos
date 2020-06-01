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
package com.alibaba.nacos.naming.core;

import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.utils.NamingUtils;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.core.cluster.Member;
import com.alibaba.nacos.core.cluster.ServerMemberManager;
import com.alibaba.nacos.naming.consistency.ConsistencyService;
import com.alibaba.nacos.naming.consistency.Datum;
import com.alibaba.nacos.naming.consistency.KeyBuilder;
import com.alibaba.nacos.naming.consistency.RecordListener;
import com.alibaba.nacos.naming.consistency.persistent.raft.RaftPeer;
import com.alibaba.nacos.naming.consistency.persistent.raft.RaftPeerSet;
import com.alibaba.nacos.naming.misc.GlobalExecutor;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.Message;
import com.alibaba.nacos.naming.misc.NetUtils;
import com.alibaba.nacos.naming.misc.ServiceStatusSynchronizer;
import com.alibaba.nacos.naming.misc.SwitchDomain;
import com.alibaba.nacos.naming.misc.Synchronizer;
import com.alibaba.nacos.naming.misc.UtilsAndCommons;
import com.alibaba.nacos.naming.push.PushService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

/**
 * Core manager storing all services in Nacos
 *
 * 服务管理者
 * @author nkorange
 */
@Component
public class ServiceManager implements RecordListener<Service> {

    /**
     * Map<namespace, Map<group::serviceName, Service>>
     */
    private Map<String, Map<String, Service>> serviceMap = new ConcurrentHashMap<>();

    private LinkedBlockingDeque<ServiceKey> toBeUpdatedServicesQueue = new LinkedBlockingDeque<>(1024 * 1024);

    private Synchronizer synchronizer = new ServiceStatusSynchronizer();

    private final Lock lock = new ReentrantLock();

    /**
     * 这里注入的是DelegateConsistencyServiceImpl
     */
    @Resource(name = "consistencyDelegate")
    private ConsistencyService consistencyService;

    @Autowired
    private SwitchDomain switchDomain;

    @Autowired
    private DistroMapper distroMapper;

    @Autowired
    private ServerMemberManager memberManager;

    @Autowired
    private PushService pushService;

    @Autowired
    private RaftPeerSet raftPeerSet;

    @Value("${nacos.naming.empty-service.auto-clean:false}")
    private boolean emptyServiceAutoClean;

    private int maxFinalizeCount = 3;

    private final Object putServiceLock = new Object();

    @Value("${nacos.naming.empty-service.clean.initial-delay-ms:60000}")
    private int cleanEmptyServiceDelay;

    @Value("${nacos.naming.empty-service.clean.period-time-ms:20000}")
    private int cleanEmptyServicePeriod;

    @PostConstruct
    public void init() {

        UtilsAndCommons.SERVICE_SYNCHRONIZATION_EXECUTOR.schedule(new ServiceReporter(), 60000, TimeUnit.MILLISECONDS);

        UtilsAndCommons.SERVICE_UPDATE_EXECUTOR.submit(new UpdatedServiceProcessor());

        if (emptyServiceAutoClean) {

            Loggers.SRV_LOG.info("open empty service auto clean job, initialDelay : {} ms, period : {} ms", cleanEmptyServiceDelay, cleanEmptyServicePeriod);

            // delay 60s, period 20s;

            // This task is not recommended to be performed frequently in order to avoid
            // the possibility that the service cache information may just be deleted
            // and then created due to the heartbeat mechanism

            GlobalExecutor.scheduleServiceAutoClean(new EmptyServiceAutoClean(), cleanEmptyServiceDelay, cleanEmptyServicePeriod);
        }

        try {
            Loggers.SRV_LOG.info("listen for service meta change");
            /**
             * 容器启动时，往consistencyService注册一个监听器，key是com.alibaba.nacos.naming.domains.meta.
             * 监听器则是自己
             */
            consistencyService.listen(KeyBuilder.SERVICE_META_KEY_PREFIX, this);
        } catch (NacosException e) {
            Loggers.SRV_LOG.error("listen for service meta change failed!");
        }
    }

    public Map<String, Service> chooseServiceMap(String namespaceId) {
        return serviceMap.get(namespaceId);
    }

    public void addUpdatedService2Queue(String namespaceId, String serviceName, String serverIP, String checksum) {
        lock.lock();
        try {
            toBeUpdatedServicesQueue.offer(new ServiceKey(namespaceId, serviceName, serverIP, checksum), 5, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            toBeUpdatedServicesQueue.poll();
            toBeUpdatedServicesQueue.add(new ServiceKey(namespaceId, serviceName, serverIP, checksum));
            Loggers.SRV_LOG.error("[DOMAIN-STATUS] Failed to add service to be updatd to queue.", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean interests(String key) {
        return KeyBuilder.matchServiceMetaKey(key) && !KeyBuilder.matchSwitchKey(key);
    }

    @Override
    public boolean matchUnlistenKey(String key) {
        return KeyBuilder.matchServiceMetaKey(key) && !KeyBuilder.matchSwitchKey(key);
    }

    /**
     * 监听SERVICE_META_KEY_PREFIX这个key的事件，由DistroConsistencyServiceImpl加载远端节点数据时调用
     * @param key   target key
     * @param service
     * @throws Exception
     */
    @Override
    public void onChange(String key, Service service) throws Exception {
        try {
            if (service == null) {
                Loggers.SRV_LOG.warn("received empty push from raft, key: {}", key);
                return;
            }

            if (StringUtils.isBlank(service.getNamespaceId())) {
                service.setNamespaceId(Constants.DEFAULT_NAMESPACE_ID);
            }

            Loggers.RAFT.info("[RAFT-NOTIFIER] datum is changed, key: {}, value: {}", key, service);

            /**
             * 获取旧的服务
             */
            Service oldDom = getService(service.getNamespaceId(), service.getName());

            if (oldDom != null) {
                /**
                 * 更新旧的服务，这里旧的服务可能是通过心跳添加的
                 */
                oldDom.update(service);
                // re-listen to handle the situation when the underlying listener is removed:
                /**
                 * 往DistroConsistencyServiceImpl，RaftConsistencyServiceImpl，注册这个Service监听器
                 */
                consistencyService.listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), true), oldDom);
                consistencyService.listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), false), oldDom);
            } else {
                // 如果是一个新服务就进行初始化
                putServiceAndInit(service);
            }
        } catch (Throwable e) {
            Loggers.SRV_LOG.error("[NACOS-SERVICE] error while processing service update", e);
        }
    }

    @Override
    public void onDelete(String key) throws Exception {
        String namespace = KeyBuilder.getNamespace(key);
        String name = KeyBuilder.getServiceName(key);
        Service service = chooseServiceMap(namespace).get(name);
        Loggers.RAFT.info("[RAFT-NOTIFIER] datum is deleted, key: {}", key);

        if (service != null) {
            service.destroy();
            consistencyService.remove(KeyBuilder.buildInstanceListKey(namespace, name, true));

            consistencyService.remove(KeyBuilder.buildInstanceListKey(namespace, name, false));

            consistencyService.unlisten(KeyBuilder.buildServiceMetaKey(namespace, name), service);
            Loggers.SRV_LOG.info("[DEAD-SERVICE] {}", service.toJSON());
        }

        chooseServiceMap(namespace).remove(name);
    }

    private class UpdatedServiceProcessor implements Runnable {
        //get changed service from other server asynchronously
        @Override
        public void run() {
            ServiceKey serviceKey = null;

            try {
                while (true) {
                    try {
                        serviceKey = toBeUpdatedServicesQueue.take();
                    } catch (Exception e) {
                        Loggers.EVT_LOG.error("[UPDATE-DOMAIN] Exception while taking item from LinkedBlockingDeque.");
                    }

                    if (serviceKey == null) {
                        continue;
                    }
                    GlobalExecutor.submitServiceUpdate(new ServiceUpdater(serviceKey));
                }
            } catch (Exception e) {
                Loggers.EVT_LOG.error("[UPDATE-DOMAIN] Exception while update service: {}", serviceKey, e);
            }
        }
    }

    private class ServiceUpdater implements Runnable {

        String namespaceId;
        String serviceName;
        String serverIP;

        public ServiceUpdater(ServiceKey serviceKey) {
            this.namespaceId = serviceKey.getNamespaceId();
            this.serviceName = serviceKey.getServiceName();
            this.serverIP = serviceKey.getServerIP();
        }

        @Override
        public void run() {
            try {
                updatedHealthStatus(namespaceId, serviceName, serverIP);
            } catch (Exception e) {
                Loggers.SRV_LOG.warn("[DOMAIN-UPDATER] Exception while update service: {} from {}, error: {}",
                    serviceName, serverIP, e);
            }
        }
    }

    public RaftPeer getMySelfClusterState() {
        return raftPeerSet.local();
    }

    public void updatedHealthStatus(String namespaceId, String serviceName, String serverIP) {
        Message msg = synchronizer.get(serverIP, UtilsAndCommons.assembleFullServiceName(namespaceId, serviceName));
        JsonNode serviceJson = JacksonUtils.toObj(msg.getData());

        ArrayNode ipList = (ArrayNode) serviceJson.get("ips");
        Map<String, String> ipsMap = new HashMap<>(ipList.size());
        for (int i = 0; i < ipList.size(); i++) {

            String ip = ipList.get(i).asText();
            String[] strings = ip.split("_");
            ipsMap.put(strings[0], strings[1]);
        }

        Service service = getService(namespaceId, serviceName);

        if (service == null) {
            return;
        }

        boolean changed = false;

        List<Instance> instances = service.allIPs();
        for (Instance instance : instances) {

            boolean valid = Boolean.parseBoolean(ipsMap.get(instance.toIPAddr()));
            if (valid != instance.isHealthy()) {
                changed = true;
                instance.setHealthy(valid);
                Loggers.EVT_LOG.info("{} {SYNC} IP-{} : {}:{}@{}",
                    serviceName, (instance.isHealthy() ? "ENABLED" : "DISABLED"),
                    instance.getIp(), instance.getPort(), instance.getClusterName());
            }
        }

        if (changed) {
            pushService.serviceChanged(service);
            if (Loggers.EVT_LOG.isDebugEnabled()){
                StringBuilder stringBuilder = new StringBuilder();
                List<Instance> allIps = service.allIPs();
                for (Instance instance : allIps) {
                    stringBuilder.append(instance.toIPAddr()).append("_").append(instance.isHealthy()).append(",");
                }
                Loggers.EVT_LOG.debug("[HEALTH-STATUS-UPDATED] namespace: {}, service: {}, ips: {}",
                    service.getNamespaceId(), service.getName(), stringBuilder.toString());
            }
        }

    }

    public Set<String> getAllServiceNames(String namespaceId) {
        return serviceMap.get(namespaceId).keySet();
    }

    public Map<String, Set<String>> getAllServiceNames() {

        Map<String, Set<String>> namesMap = new HashMap<>(16);
        for (String namespaceId : serviceMap.keySet()) {
            namesMap.put(namespaceId, serviceMap.get(namespaceId).keySet());
        }
        return namesMap;
    }

    public Set<String> getAllNamespaces() {
        return serviceMap.keySet();
    }

    public List<String> getAllServiceNameList(String namespaceId) {
        if (chooseServiceMap(namespaceId) == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(chooseServiceMap(namespaceId).keySet());
    }

    public Map<String, Set<Service>> getResponsibleServices() {
        Map<String, Set<Service>> result = new HashMap<>(16);
        for (String namespaceId : serviceMap.keySet()) {
            result.put(namespaceId, new HashSet<>());
            for (Map.Entry<String, Service> entry : serviceMap.get(namespaceId).entrySet()) {
                Service service = entry.getValue();
                if (distroMapper.responsible(entry.getKey())) {
                    result.get(namespaceId).add(service);
                }
            }
        }
        return result;
    }

    public int getResponsibleServiceCount() {
        int serviceCount = 0;
        for (String namespaceId : serviceMap.keySet()) {
            for (Map.Entry<String, Service> entry : serviceMap.get(namespaceId).entrySet()) {
                if (distroMapper.responsible(entry.getKey())) {
                    serviceCount++;
                }
            }
        }
        return serviceCount;
    }

    public int getResponsibleInstanceCount() {
        Map<String, Set<Service>> responsibleServices = getResponsibleServices();
        int count = 0;
        for (String namespaceId : responsibleServices.keySet()) {
            for (Service service : responsibleServices.get(namespaceId)) {
                count += service.allIPs().size();
            }
        }

        return count;
    }

    public void easyRemoveService(String namespaceId, String serviceName) throws Exception {

        Service service = getService(namespaceId, serviceName);
        if (service == null) {
            throw new IllegalArgumentException("specified service not exist, serviceName : " + serviceName);
        }

        consistencyService.remove(KeyBuilder.buildServiceMetaKey(namespaceId, serviceName));
    }

    public void addOrReplaceService(Service service) throws NacosException {
        consistencyService.put(KeyBuilder.buildServiceMetaKey(service.getNamespaceId(), service.getName()), service);
    }

    /**
     * 第一次创建时，是一个空的service
     * @param namespaceId
     * @param serviceName
     * @param local
     * @throws NacosException
     */
    public void createEmptyService(String namespaceId, String serviceName, boolean local) throws NacosException {
        createServiceIfAbsent(namespaceId, serviceName, local, null);
    }

    public void createServiceIfAbsent(String namespaceId, String serviceName, boolean local, Cluster cluster) throws NacosException {

        Service service = getService(namespaceId, serviceName);
        /**
         * 不存在服务则创建
         */
        if (service == null) {

            Loggers.SRV_LOG.info("creating empty service {}:{}", namespaceId, serviceName);
            service = new Service();
            service.setName(serviceName);
            service.setNamespaceId(namespaceId);
            service.setGroupName(NamingUtils.getGroupName(serviceName));
            // now validate the service. if failed, exception will be thrown
            service.setLastModifiedMillis(System.currentTimeMillis());
            service.recalculateChecksum();
            if (cluster != null) {
                cluster.setService(service);
                service.getClusterMap().put(cluster.getName(), cluster);
            }
            service.validate();
            /**
             * 服务进行初始化
             */
            putServiceAndInit(service);

            /**
             * 临时节点，客户端默认注册的服务实例是临时的
             * 这里就不走这个逻辑
             */
            if (!local) {
                addOrReplaceService(service);
            }
        }
    }

    /**
     * Register an instance to a service in AP mode.
     * <p>
     * This method creates service or cluster silently if they don't exist.
     *
     * @param namespaceId id of namespace
     * @param serviceName service name
     * @param instance    instance to register
     * @throws Exception any error occurred in the process
     */
    public void registerInstance(String namespaceId, String serviceName, Instance instance) throws NacosException {
        //  存在服务就不用创建，而只是添加实例，不存在就创建一个新的服务
        createEmptyService(namespaceId, serviceName, instance.isEphemeral());

        Service service = getService(namespaceId, serviceName);

        if (service == null) {
            throw new NacosException(NacosException.INVALID_PARAM,
                "service not found, namespace: " + namespaceId + ", service: " + serviceName);
        }
        /**
         * 添加服务实例
         */
        addInstance(namespaceId, serviceName, instance.isEphemeral(), instance);
    }

    public void updateInstance(String namespaceId, String serviceName, Instance instance) throws NacosException {

        Service service = getService(namespaceId, serviceName);

        if (service == null) {
            throw new NacosException(NacosException.INVALID_PARAM,
                "service not found, namespace: " + namespaceId + ", service: " + serviceName);
        }

        if (!service.allIPs().contains(instance)) {
            throw new NacosException(NacosException.INVALID_PARAM, "instance not exist: " + instance);
        }

        addInstance(namespaceId, serviceName, instance.isEphemeral(), instance);
    }

    public void addInstance(String namespaceId, String serviceName, boolean ephemeral, Instance... ips) throws NacosException {

        String key = KeyBuilder.buildInstanceListKey(namespaceId, serviceName, ephemeral);

        Service service = getService(namespaceId, serviceName);

        synchronized (service) {
            // 获取实例列表
            List<Instance> instanceList = addIpAddresses(service, ephemeral, ips);

            Instances instances = new Instances();
            instances.setInstanceList(instanceList);
            // 将服务实例更新到存储介质中
            consistencyService.put(key, instances);
        }
    }

    public void removeInstance(String namespaceId, String serviceName, boolean ephemeral, Instance... ips) throws NacosException {
        Service service = getService(namespaceId, serviceName);

        synchronized (service) {
            removeInstance(namespaceId, serviceName, ephemeral, service, ips);
        }
    }

    public void removeInstance(String namespaceId, String serviceName, boolean ephemeral, Service service, Instance... ips) throws NacosException {

        String key = KeyBuilder.buildInstanceListKey(namespaceId, serviceName, ephemeral);

        List<Instance> instanceList = substractIpAddresses(service, ephemeral, ips);

        Instances instances = new Instances();
        instances.setInstanceList(instanceList);

        consistencyService.put(key, instances);
    }

    public Instance getInstance(String namespaceId, String serviceName, String cluster, String ip, int port) {
        Service service = getService(namespaceId, serviceName);
        if (service == null) {
            return null;
        }

        List<String> clusters = new ArrayList<>();
        clusters.add(cluster);

        List<Instance> ips = service.allIPs(clusters);
        if (ips == null || ips.isEmpty()) {
            return null;
        }

        for (Instance instance : ips) {
            if (instance.getIp().equals(ip) && instance.getPort() == port) {
                return instance;
            }
        }

        return null;
    }

    public List<Instance> updateIpAddresses(Service service, String action, boolean ephemeral, Instance... ips) throws NacosException {

        /**
         * 从 DistroConsistencyServiceImpl或者RaftConsistencyServiceImpl中获取Datum
         */
        Datum datum = consistencyService.get(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), ephemeral));

        /**
         * 获取当前所有的实例信息
         */
        List<Instance> currentIPs = service.allIPs(ephemeral);
        Map<String, Instance> currentInstances = new HashMap<>(currentIPs.size());
        Set<String> currentInstanceIds = Sets.newHashSet();

        for (Instance instance : currentIPs) {
            /**
             * 合并Datum，和集群map中的实例信息，Datum认为是旧的实例信息，集群map认为是新的
             */
            currentInstances.put(instance.toIPAddr(), instance);
            currentInstanceIds.add(instance.getInstanceId());
        }

        Map<String, Instance> instanceMap;
        if (datum != null) {
            instanceMap = setValid(((Instances) datum.value).getInstanceList(), currentInstances);
        } else {
            instanceMap = new HashMap<>(ips.length);
        }

        for (Instance instance : ips) {
            if (!service.getClusterMap().containsKey(instance.getClusterName())) {
                Cluster cluster = new Cluster(instance.getClusterName(), service);
                cluster.init();
                service.getClusterMap().put(instance.getClusterName(), cluster);
                Loggers.SRV_LOG.warn("cluster: {} not found, ip: {}, will create new cluster with default configuration.",
                    instance.getClusterName(), instance.toJSON());
            }

            if (UtilsAndCommons.UPDATE_INSTANCE_ACTION_REMOVE.equals(action)) {
                instanceMap.remove(instance.getDatumKey());
            } else {
                instance.setInstanceId(instance.generateInstanceId(currentInstanceIds));
                instanceMap.put(instance.getDatumKey(), instance);
            }

        }

        if (instanceMap.size() <= 0 && UtilsAndCommons.UPDATE_INSTANCE_ACTION_ADD.equals(action)) {
            throw new IllegalArgumentException("ip list can not be empty, service: " + service.getName() + ", ip list: "
                + JacksonUtils.toJson(instanceMap.values()));
        }

        return new ArrayList<>(instanceMap.values());
    }

    public List<Instance> substractIpAddresses(Service service, boolean ephemeral, Instance... ips) throws NacosException {
        return updateIpAddresses(service, UtilsAndCommons.UPDATE_INSTANCE_ACTION_REMOVE, ephemeral, ips);
    }

    public List<Instance> addIpAddresses(Service service, boolean ephemeral, Instance... ips) throws NacosException {
        return updateIpAddresses(service, UtilsAndCommons.UPDATE_INSTANCE_ACTION_ADD, ephemeral, ips);
    }

    private Map<String, Instance> setValid(List<Instance> oldInstances, Map<String, Instance> map) {

        Map<String, Instance> instanceMap = new HashMap<>(oldInstances.size());
        for (Instance instance : oldInstances) {
            Instance instance1 = map.get(instance.toIPAddr());
            if (instance1 != null) {
                instance.setHealthy(instance1.isHealthy());
                instance.setLastBeat(instance1.getLastBeat());
            }
            instanceMap.put(instance.getDatumKey(), instance);
        }
        return instanceMap;
    }

    public Service getService(String namespaceId, String serviceName) {
        if (serviceMap.get(namespaceId) == null) {
            return null;
        }
        return chooseServiceMap(namespaceId).get(serviceName);
    }

    public boolean containService(String namespaceId, String serviceName) {
        return getService(namespaceId, serviceName) != null;
    }

    /**
     * 添加服务，以nameSpace区分，然后以服务名区分，服务名用group_name@@server_name 进行拼接
     * @param service
     */
    public void putService(Service service) {
        if (!serviceMap.containsKey(service.getNamespaceId())) {
            synchronized (putServiceLock) {
                if (!serviceMap.containsKey(service.getNamespaceId())) {
                    serviceMap.put(service.getNamespaceId(), new ConcurrentHashMap<>(16));
                }
            }
        }
        serviceMap.get(service.getNamespaceId()).put(service.getName(), service);
    }

    /**
     * 添加服务并进行初始化
     * @param service
     * @throws NacosException
     */
    private void putServiceAndInit(Service service) throws NacosException {
        putService(service);
        service.init();

        /**
         * 往DistroConsistencyServiceImpl，RaftConsistencyServiceImpl，注册这个Service监听器
         */
        consistencyService.listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), true), service);
        consistencyService.listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), false), service);
        Loggers.SRV_LOG.info("[NEW-SERVICE] {}", service.toJSON());
    }


    public List<Service> searchServices(String namespaceId, String regex) {
        List<Service> result = new ArrayList<>();
        for (Map.Entry<String, Service> entry : chooseServiceMap(namespaceId).entrySet()) {
            Service service = entry.getValue();
            String key = service.getName() + ":" + ArrayUtils.toString(service.getOwners());
            if (key.matches(regex)) {
                result.add(service);
            }
        }

        return result;
    }

    public int getServiceCount() {
        int serviceCount = 0;
        for (String namespaceId : serviceMap.keySet()) {
            serviceCount += serviceMap.get(namespaceId).size();
        }
        return serviceCount;
    }

    public int getInstanceCount() {
        int total = 0;
        for (String namespaceId : serviceMap.keySet()) {
            for (Service service : serviceMap.get(namespaceId).values()) {
                total += service.allIPs().size();
            }
        }
        return total;
    }

    public Map<String, Service> getServiceMap(String namespaceId) {
        return serviceMap.get(namespaceId);
    }

    public int getPagedService(String namespaceId, int startPage, int pageSize, String param, String containedInstance, List<Service> serviceList, boolean hasIpCount) {

        List<Service> matchList;

        if (chooseServiceMap(namespaceId) == null) {
            return 0;
        }

        if (StringUtils.isNotBlank(param)) {
            StringJoiner regex = new StringJoiner(Constants.SERVICE_INFO_SPLITER);
            for (String s : param.split(Constants.SERVICE_INFO_SPLITER)) {
                regex.add(StringUtils.isBlank(s) ? Constants.ANY_PATTERN : Constants.ANY_PATTERN + s + Constants.ANY_PATTERN);
            }
            matchList = searchServices(namespaceId, regex.toString());
        } else {
            matchList = new ArrayList<>(chooseServiceMap(namespaceId).values());
        }

        if (!CollectionUtils.isEmpty(matchList) && hasIpCount) {
            matchList = matchList.stream().filter(s -> !CollectionUtils.isEmpty(s.allIPs())).collect(Collectors.toList());
        }

        if (StringUtils.isNotBlank(containedInstance)) {

            boolean contained;
            for (int i = 0; i < matchList.size(); i++) {
                Service service = matchList.get(i);
                contained = false;
                List<Instance> instances = service.allIPs();
                for (Instance instance : instances) {
                    if (containedInstance.contains(":")) {
                        if (StringUtils.equals(instance.getIp() + ":" + instance.getPort(), containedInstance)) {
                            contained = true;
                            break;
                        }
                    } else {
                        if (StringUtils.equals(instance.getIp(), containedInstance)) {
                            contained = true;
                            break;
                        }
                    }
                }
                if (!contained) {
                    matchList.remove(i);
                    i--;
                }
            }
        }

        if (pageSize >= matchList.size()) {
            serviceList.addAll(matchList);
            return matchList.size();
        }

        for (int i = 0; i < matchList.size(); i++) {
            if (i < startPage * pageSize) {
                continue;
            }

            serviceList.add(matchList.get(i));

            if (serviceList.size() >= pageSize) {
                break;
            }
        }

        return matchList.size();
    }

    public static class ServiceChecksum {

        public String namespaceId;
        public Map<String, String> serviceName2Checksum = new HashMap<String, String>();

        public ServiceChecksum() {
            this.namespaceId = Constants.DEFAULT_NAMESPACE_ID;
        }

        public ServiceChecksum(String namespaceId) {
            this.namespaceId = namespaceId;
        }

        public void addItem(String serviceName, String checksum) {
            if (StringUtils.isEmpty(serviceName) || StringUtils.isEmpty(checksum)) {
                Loggers.SRV_LOG.warn("[DOMAIN-CHECKSUM] serviceName or checksum is empty,serviceName: {}, checksum: {}",
                    serviceName, checksum);
                return;
            }
            serviceName2Checksum.put(serviceName, checksum);
        }
    }


    private class EmptyServiceAutoClean implements Runnable {

        @Override
        public void run() {

            // Parallel flow opening threshold

            int parallelSize = 100;

            serviceMap.forEach((namespace, stringServiceMap) -> {
                Stream<Map.Entry<String, Service>> stream = null;
                if (stringServiceMap.size() > parallelSize) {
                    stream = stringServiceMap.entrySet().parallelStream();
                } else {
                    stream = stringServiceMap.entrySet().stream();
                }
                stream
                        .filter(entry -> {
                            final String serviceName = entry.getKey();
                            return distroMapper.responsible(serviceName);
                        })
                        .forEach(entry -> stringServiceMap.computeIfPresent(entry.getKey(), (serviceName, service) -> {
                    if (service.isEmpty()) {

                        // To avoid violent Service removal, the number of times the Service
                        // experiences Empty is determined by finalizeCnt, and if the specified
                        // value is reached, it is removed

                        if (service.getFinalizeCount() > maxFinalizeCount) {
                            Loggers.SRV_LOG.warn("namespace : {}, [{}] services are automatically cleaned",
                                    namespace, serviceName);
                            try {
                                easyRemoveService(namespace, serviceName);
                            } catch (Exception e) {
                                Loggers.SRV_LOG.error("namespace : {}, [{}] services are automatically clean has " +
                                        "error : {}", namespace, serviceName, e);
                            }
                        }

                        service.setFinalizeCount(service.getFinalizeCount() + 1);

                        Loggers.SRV_LOG.debug("namespace : {}, [{}] The number of times the current service experiences " +
                                "an empty instance is : {}", namespace, serviceName, service.getFinalizeCount());
                    } else {
                        service.setFinalizeCount(0);
                    }
                    return service;
                }));
            });
        }
    }

    private class ServiceReporter implements Runnable {

        @Override
        public void run() {
            try {

                Map<String, Set<String>> allServiceNames = getAllServiceNames();

                if (allServiceNames.size() <= 0) {
                    //ignore
                    return;
                }

                for (String namespaceId : allServiceNames.keySet()) {

                    ServiceChecksum checksum = new ServiceChecksum(namespaceId);

                    for (String serviceName : allServiceNames.get(namespaceId)) {
                        if (!distroMapper.responsible(serviceName)) {
                            continue;
                        }

                        Service service = getService(namespaceId, serviceName);

                        if (service == null || service.isEmpty()) {
                            continue;
                        }

                        service.recalculateChecksum();

                        checksum.addItem(serviceName, service.getChecksum());
                    }

                    Message msg = new Message();

                    msg.setData(JacksonUtils.toJson(checksum));

                    Collection<Member> sameSiteServers = memberManager.allMembers();

                    if (sameSiteServers == null || sameSiteServers.size() <= 0) {
                        return;
                    }

                    for (Member server : sameSiteServers) {
                        if (server.getAddress().equals(NetUtils.localServer())) {
                            continue;
                        }
                        synchronizer.send(server.getAddress(), msg);
                    }
                }
            } catch (Exception e) {
                Loggers.SRV_LOG.error("[DOMAIN-STATUS] Exception while sending service status", e);
            } finally {
                UtilsAndCommons.SERVICE_SYNCHRONIZATION_EXECUTOR.schedule(this, switchDomain.getServiceStatusSynchronizationPeriodMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    private static class ServiceKey {
        private String namespaceId;
        private String serviceName;
        private String serverIP;
        private String checksum;

        public String getChecksum() {
            return checksum;
        }

        public String getServerIP() {
            return serverIP;
        }

        public String getServiceName() {
            return serviceName;
        }

        public String getNamespaceId() {
            return namespaceId;
        }

        public ServiceKey(String namespaceId, String serviceName, String serverIP, String checksum) {
            this.namespaceId = namespaceId;
            this.serviceName = serviceName;
            this.serverIP = serverIP;
            this.checksum = checksum;
        }

        @Override
        public String toString() {
            return JacksonUtils.toJson(this);
        }
    }
}

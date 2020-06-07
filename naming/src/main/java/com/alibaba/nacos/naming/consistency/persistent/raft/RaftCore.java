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
package com.alibaba.nacos.naming.consistency.persistent.raft;

import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.core.utils.ApplicationUtils;
import com.alibaba.nacos.naming.consistency.ApplyAction;
import com.alibaba.nacos.naming.consistency.Datum;
import com.alibaba.nacos.naming.consistency.KeyBuilder;
import com.alibaba.nacos.naming.consistency.RecordListener;
import com.alibaba.nacos.naming.core.Instances;
import com.alibaba.nacos.naming.core.Service;
import com.alibaba.nacos.naming.misc.*;
import com.alibaba.nacos.naming.monitor.MetricsMonitor;
import com.alibaba.nacos.naming.pojo.Record;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.javatuples.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPOutputStream;

/**
 * @author nacos
 */
@DependsOn("ProtocolManager")
@Component
public class RaftCore {

    public static final String API_VOTE = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/vote";

    public static final String API_BEAT = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/beat";

    public static final String API_PUB = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/datum";

    public static final String API_DEL = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/datum";

    public static final String API_GET = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/datum";

    public static final String API_ON_PUB = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/datum/commit";

    public static final String API_ON_DEL = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/datum/commit";

    public static final String API_GET_PEER = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/peer";

    private ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);

            t.setDaemon(true);
            t.setName("com.alibaba.nacos.naming.raft.notifier");

            return t;
        }
    });

    public static final Lock OPERATE_LOCK = new ReentrantLock();

    public static final int PUBLISH_TERM_INCREASE_COUNT = 100;

    private volatile Map<String, List<RecordListener>> listeners = new ConcurrentHashMap<>();

    private volatile ConcurrentMap<String, Datum> datums = new ConcurrentHashMap<>();

    @Autowired
    private RaftPeerSet peers;

    @Autowired
    private SwitchDomain switchDomain;

    @Autowired
    private GlobalConfig globalConfig;

    @Autowired
    private RaftProxy raftProxy;

    @Autowired
    private RaftStore raftStore;

    public volatile Notifier notifier = new Notifier();

    private boolean initialized = false;

    @PostConstruct
    public void init() throws Exception {

        Loggers.RAFT.info("initializing Raft sub-system");

        executor.submit(notifier);

        long start = System.currentTimeMillis();

        /**
         * 启动加载持久化文件数据
         */
        raftStore.loadDatums(notifier, datums);

        setTerm(NumberUtils.toLong(raftStore.loadMeta().getProperty("term"), 0L));

        Loggers.RAFT.info("cache loaded, datum count: {}, current term: {}", datums.size(), peers.getTerm());

        while (true) {
            if (notifier.tasks.size() <= 0) {
                break;
            }
            Thread.sleep(1000L);
        }

        initialized = true;

        Loggers.RAFT.info("finish to load data from disk, cost: {} ms.", (System.currentTimeMillis() - start));

        /**
         * 注册maser 选举定时任务，500ms 执行一次，15秒内发送一次master选举，会被心跳线程重置，不能再触发选举
         */
        GlobalExecutor.registerMasterElection(new MasterElection());
        /**
         * 注册心跳定时任务，500ms 执行一次，5s内发送一次心跳
         */
        GlobalExecutor.registerHeartbeat(new HeartBeat());

        Loggers.RAFT.info("timer started: leader timeout ms: {}, heart-beat timeout ms: {}",
            GlobalExecutor.LEADER_TIMEOUT_MS, GlobalExecutor.HEARTBEAT_INTERVAL_MS);
    }

    public Map<String, List<RecordListener>> getListeners() {
        return listeners;
    }

    public void signalPublish(String key, Record value) throws Exception {

        /**
         * 本机节点不是leader，需要通过leader进行服务实例添加
         */
        if (!isLeader()) {
            ObjectNode params = JacksonUtils.createEmptyJsonNode();
            params.put("key", key);
            params.replace("value", JacksonUtils.transferToJsonNode(value));
            Map<String, String> parameters = new HashMap<>(1);
            parameters.put("key", key);

            final RaftPeer leader = getLeader();

            raftProxy.proxyPostLarge(leader.ip, API_PUB, params.toString(), parameters);
            return;
        }

        /**
         * 本机节点是leader
         */
        try {
            OPERATE_LOCK.lock();
            long start = System.currentTimeMillis();
            final Datum datum = new Datum();
            datum.key = key;
            datum.value = value;
            if (getDatum(key) == null) {
                datum.timestamp.set(1L);
            } else {
                datum.timestamp.set(getDatum(key).timestamp.incrementAndGet());
            }

            ObjectNode json = JacksonUtils.createEmptyJsonNode();
            json.replace("datum", JacksonUtils.transferToJsonNode(datum));
            json.replace("source", JacksonUtils.transferToJsonNode(peers.local()));

            // 更新本地数据并发送变更事件
            onPublish(datum, peers.local());

            final String content = json.toString();

            final CountDownLatch latch = new CountDownLatch(peers.majorityCount());
            // 遍历所有节点包括本机节点
            for (final String server : peers.allServersIncludeMyself()) {
                if (isLeader(server)) {
                    latch.countDown();
                    continue;
                }
                // 给follower节点发送提交请求，/raft/datum/commit
                final String url = buildURL(server, API_ON_PUB);
                HttpClient.asyncHttpPostLarge(url, Arrays.asList("key=" + key), content, new AsyncCompletionHandler<Integer>() {
                    @Override
                    public Integer onCompleted(Response response) throws Exception {
                        if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                            Loggers.RAFT.warn("[RAFT] failed to publish data to peer, datumId={}, peer={}, http code={}",
                                datum.key, server, response.getStatusCode());
                            return 1;
                        }
                        // follower 响应ok ,放行
                        latch.countDown();
                        return 0;
                    }

                    @Override
                    public STATE onContentWriteCompleted() {
                        return STATE.CONTINUE;
                    }
                });

            }

            /**
             * 等待半数follower成功响应，否则失败，但是leader已经提交了
             */
            if (!latch.await(UtilsAndCommons.RAFT_PUBLISH_TIMEOUT, TimeUnit.MILLISECONDS)) {
                // only majority servers return success can we consider this update success
                Loggers.RAFT.error("data publish failed, caused failed to notify majority, key={}", key);
                throw new IllegalStateException("data publish failed, caused failed to notify majority, key=" + key);
            }

            long end = System.currentTimeMillis();
            Loggers.RAFT.info("signalPublish cost {} ms, key: {}", (end - start), key);
        } finally {
            OPERATE_LOCK.unlock();
        }
    }

    public void signalDelete(final String key) throws Exception {

        OPERATE_LOCK.lock();
        try {

            if (!isLeader()) {
                Map<String, String> params = new HashMap<>(1);
                params.put("key", URLEncoder.encode(key, "UTF-8"));
                raftProxy.proxy(getLeader().ip, API_DEL, params, HttpMethod.DELETE);
                return;
            }

            // construct datum:
            Datum datum = new Datum();
            datum.key = key;
            ObjectNode json = JacksonUtils.createEmptyJsonNode();
            json.replace("datum", JacksonUtils.transferToJsonNode(datum));
            json.replace("source", JacksonUtils.transferToJsonNode(peers.local()));

            onDelete(datum.key, peers.local());

            for (final String server : peers.allServersWithoutMySelf()) {
                String url = buildURL(server, API_ON_DEL);
                HttpClient.asyncHttpDeleteLarge(url, null, json.toString()
                    , new AsyncCompletionHandler<Integer>() {
                        @Override
                        public Integer onCompleted(Response response) throws Exception {
                            if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                                Loggers.RAFT.warn("[RAFT] failed to delete data from peer, datumId={}, peer={}, http code={}", key, server, response.getStatusCode());
                                return 1;
                            }

                            RaftPeer local = peers.local();

                            local.resetLeaderDue();

                            return 0;
                        }
                    });
            }
        } finally {
            OPERATE_LOCK.unlock();
        }
    }

    /**
     * leader响应follow转发的请求或自己本地接受的
     * @param datum
     * @param source
     * @throws Exception
     */
    public void onPublish(Datum datum, RaftPeer source) throws Exception {
        RaftPeer local = peers.local();
        if (datum.value == null) {
            Loggers.RAFT.warn("received empty datum");
            throw new IllegalStateException("received empty datum");
        }

        /**
         * 本机节点不是leader抛异常
         */
        if (!peers.isLeader(source.ip)) {
            Loggers.RAFT.warn("peer {} tried to publish data but wasn't leader, leader: {}",
                JacksonUtils.toJson(source), JacksonUtils.toJson(getLeader()));
            throw new IllegalStateException("peer(" + source.ip + ") tried to publish " +
                "data but wasn't leader");
        }

        if (source.term.get() < local.term.get()) {
            Loggers.RAFT.warn("out of date publish, pub-term: {}, cur-term: {}",
                JacksonUtils.toJson(source), JacksonUtils.toJson(local));
            throw new IllegalStateException("out of date publish, pub-term:"
                + source.term.get() + ", cur-term: " + local.term.get());
        }

        /**
         * 重置leader选举等待时间
         */
        local.resetLeaderDue();

        /**
         * 是持久数据进行写盘
         */
        // if data should be persisted, usually this is true:
        if (KeyBuilder.matchPersistentKey(datum.key)) {
            raftStore.write(datum);
        }

        // 更新内存数据
        datums.put(datum.key, datum);

        // 如果是leader，term 加 100
        if (isLeader()) {
            local.term.addAndGet(PUBLISH_TERM_INCREASE_COUNT);
        } else {
            // follower + 100 大于远端leader+100
            if (local.term.get() + PUBLISH_TERM_INCREASE_COUNT > source.term.get()) {
                //set leader term:
                // 设置本地leader,follower跟随远端leader
                getLeader().term.set(source.term.get());
                local.term.set(getLeader().term.get());
                // follower 加100
            } else {
                local.term.addAndGet(PUBLISH_TERM_INCREASE_COUNT);
            }
        }
        /**
         * 更新term
         */
        raftStore.updateTerm(local.term.get());

        /**
         * 发送变更通知
         */
        notifier.addTask(datum.key, ApplyAction.CHANGE);

        Loggers.RAFT.info("data added/updated, key={}, term={}", datum.key, local.term);
    }

    public void onDelete(String datumKey, RaftPeer source) throws Exception {

        RaftPeer local = peers.local();

        if (!peers.isLeader(source.ip)) {
            Loggers.RAFT.warn("peer {} tried to publish data but wasn't leader, leader: {}",
                JacksonUtils.toJson(source), JacksonUtils.toJson(getLeader()));
            throw new IllegalStateException("peer(" + source.ip + ") tried to publish data but wasn't leader");
        }

        if (source.term.get() < local.term.get()) {
            Loggers.RAFT.warn("out of date publish, pub-term: {}, cur-term: {}",
                JacksonUtils.toJson(source), JacksonUtils.toJson(local));
            throw new IllegalStateException("out of date publish, pub-term:"
                + source.term + ", cur-term: " + local.term);
        }

        local.resetLeaderDue();

        // do apply
        String key = datumKey;
        deleteDatum(key);

        if (KeyBuilder.matchServiceMetaKey(key)) {

            if (local.term.get() + PUBLISH_TERM_INCREASE_COUNT > source.term.get()) {
                //set leader term:
                getLeader().term.set(source.term.get());
                local.term.set(getLeader().term.get());
            } else {
                local.term.addAndGet(PUBLISH_TERM_INCREASE_COUNT);
            }

            raftStore.updateTerm(local.term.get());
        }

        Loggers.RAFT.info("data removed, key={}, term={}", datumKey, local.term);

    }

    /**
     * master 选举，一次投票即可产生master，如果没产生就进行下一次投票
     * 每500ms 执行一次，只有当leaderDueMs <= 0时才进行投票
     * 一旦leader选出来，心跳线程
     */
    public class MasterElection implements Runnable {
        @Override
        public void run() {
            try {

                if (!peers.isReady()) {
                    return;
                }

                RaftPeer local = peers.local();
                // 每个机器leaderDueMs初始都是不同的，递减500
                local.leaderDueMs -= GlobalExecutor.TICK_PERIOD_MS;
//                System.out.println("等待时间：" + local.leaderDueMs);
                // leaderDueMs小于等于0 才开始进行投票
                if (local.leaderDueMs > 0) {
                    return;
                }

                //重置master,心跳等待时间
                // reset timeout
                local.resetLeaderDue();
                local.resetHeartbeatDue();

                sendVote();
            } catch (Exception e) {
                Loggers.RAFT.warn("[RAFT] error while master election {}", e);
            }

        }

        /**
         * 发送投票信息
         */
        public void sendVote() {

            /**
             * 获取本机信息，ip 非127.0.0.1，而是外网ip
             */
            RaftPeer local = peers.get(NetUtils.localServer());
            Loggers.RAFT.info("leader timeout, start voting,leader: {}, term: {}",
                JacksonUtils.toJson(getLeader()), local.term);

            peers.reset();

            // term 进行自增
            local.term.incrementAndGet();
            // 设置发送投票信息的ip
            local.voteFor = local.ip;
            // 当前节点状态
            local.state = RaftPeer.State.CANDIDATE;

            Map<String, String> params = new HashMap<>(1);
            params.put("vote", JacksonUtils.toJson(local));
            // 向其他非本机节点发送投票信息
            for (final String server : peers.allServersWithoutMySelf()) {
                // 投票地址http://127.0.0.1:8849/nacos/v1/ns/raft/vote
                final String url = buildURL(server, API_VOTE);
                try {
                    /**
                     * 异步发送投票请求
                     */
                    HttpClient.asyncHttpPost(url, null, params, new AsyncCompletionHandler<Integer>() {
                        @Override
                        public Integer onCompleted(Response response) throws Exception {
                            if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                                Loggers.RAFT.error("NACOS-RAFT vote failed: {}, url: {}", response.getResponseBody(), url);
                                return 1;
                            }

                            /**
                             * 这里返回的是接受请求的节点，这个节点要么是follow，要么不是
                             */
                            RaftPeer peer = JacksonUtils.toObj(response.getResponseBody(), RaftPeer.class);
                            Loggers.RAFT.info("received approve from peer: {}", JacksonUtils.toJson(peer));

                            /**
                             * 决定leader
                             */
                            peers.decideLeader(peer);

                            return 0;
                        }
                    });
                } catch (Exception e) {
                    Loggers.RAFT.warn("error while sending vote to server: {}", server);
                }
            }
        }
    }

    public synchronized RaftPeer receivedVote(RaftPeer remote) {
        /**
         * 如果本地集群信息中不包含投票服务器则抛异常
         */
        if (!peers.contains(remote)) {
            throw new IllegalStateException("can not find peer: " + remote.ip);
        }

        /**
         * 找出本地节点信息
         */
        RaftPeer local = peers.get(NetUtils.localServer());
        /**
         * 如果投票节点的term小于本机
         */
        if (remote.term.get() <= local.term.get()) {
            String msg = "received illegitimate vote" +
                ", voter-term:" + remote.term + ", votee-term:" + local.term;

            Loggers.RAFT.info(msg);
            if (StringUtils.isEmpty(local.voteFor)) {
                // 设置voteFor为本机
                local.voteFor = local.ip;
            }

            /**
             * 返回本机信息，并给自己投票
             */
            return local;
        }

        /**
         * 一旦选出leader，立马重置选举等待时间，避免再次发生选举
         */
        local.resetLeaderDue();

        /**
         * 将本机设置为follower
         */
        local.state = RaftPeer.State.FOLLOWER;
        // 给远端节点投票
        local.voteFor = remote.ip;
        // 设置term为远端节点信息
        local.term.set(remote.term.get());
        System.out.println("投票结果，本机为follower: " + local);
        Loggers.RAFT.info("vote {} as leader, term: {}", remote.ip, remote.term);
        // 返回更新后的本机节点信息
        return local;
    }

    /**
     * raft协议心跳
     */
    public class HeartBeat implements Runnable {
        @Override
        public void run() {
            try {

                if (!peers.isReady()) {
                    return;
                }

                // 获取本机节点
                RaftPeer local = peers.local();
                local.heartbeatDueMs -= GlobalExecutor.TICK_PERIOD_MS;
                if (local.heartbeatDueMs > 0) {
                    return;
                }

                local.resetHeartbeatDue();

                sendBeat();
            } catch (Exception e) {
                Loggers.RAFT.warn("[RAFT] error while sending beat {}", e);
            }

        }

        /**
         * 发送心跳
         * @throws IOException
         * @throws InterruptedException
         */
        public void sendBeat() throws IOException, InterruptedException {
            // 获取本机节点
            RaftPeer local = peers.local();
            // 本机是leader，且不是单机模式才发送心跳
            if (local.state != RaftPeer.State.LEADER && !ApplicationUtils.getStandaloneMode()) {
                return;
            }

            if (Loggers.RAFT.isDebugEnabled()) {
                Loggers.RAFT.debug("[RAFT] send beat with {} keys.", datums.size());
            }

            local.resetLeaderDue();

            // build data
            ObjectNode packet = JacksonUtils.createEmptyJsonNode();
            packet.replace("peer", JacksonUtils.transferToJsonNode(local));

            ArrayNode array = JacksonUtils.createEmptyArrayNode();

            if (switchDomain.isSendBeatOnly()) {
                Loggers.RAFT.info("[SEND-BEAT-ONLY] {}", String.valueOf(switchDomain.isSendBeatOnly()));
            }

            /**
             * 收集所有的datums
             */
            if (!switchDomain.isSendBeatOnly()) {
                for (Datum datum : datums.values()) {

                    ObjectNode element = JacksonUtils.createEmptyJsonNode();

                    /**
                     * 封装服务key，实例key 信息
                     */
                    if (KeyBuilder.matchServiceMetaKey(datum.key)) {
                        element.put("key", KeyBuilder.briefServiceMetaKey(datum.key));
                    } else if (KeyBuilder.matchInstanceListKey(datum.key)) {
                        element.put("key", KeyBuilder.briefInstanceListkey(datum.key));
                    }
                    element.put("timestamp", datum.timestamp.get());

                    array.add(element);
                }
            }

            packet.replace("datums", array);
            // broadcast
            Map<String, String> params = new HashMap<String, String>(1);
            params.put("beat", JacksonUtils.toJson(packet));

            String content = JacksonUtils.toJson(params);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            GZIPOutputStream gzip = new GZIPOutputStream(out);
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
            gzip.close();

            byte[] compressedBytes = out.toByteArray();
            String compressedContent = new String(compressedBytes, StandardCharsets.UTF_8);

            if (Loggers.RAFT.isDebugEnabled()) {
                Loggers.RAFT.debug("raw beat data size: {}, size of compressed data: {}",
                    content.length(), compressedContent.length());
            }

            /**
             * 向非本机服务发送心跳
             */
            for (final String server : peers.allServersWithoutMySelf()) {
                try {
                    final String url = buildURL(server, API_BEAT);
                    if (Loggers.RAFT.isDebugEnabled()) {
                        Loggers.RAFT.debug("send beat to server " + server);
                    }
                    HttpClient.asyncHttpPostLarge(url, null, compressedBytes, new AsyncCompletionHandler<Integer>() {
                        @Override
                        public Integer onCompleted(Response response) throws Exception {
                            if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                                Loggers.RAFT.error("NACOS-RAFT beat failed: {}, peer: {}",
                                    response.getResponseBody(), server);
                                MetricsMonitor.getLeaderSendBeatFailedException().increment();
                                return 1;
                            }

                            peers.update(JacksonUtils.toObj(response.getResponseBody(), RaftPeer.class));
                            if (Loggers.RAFT.isDebugEnabled()) {
                                Loggers.RAFT.debug("receive beat response from: {}", url);
                            }
                            return 0;
                        }

                        @Override
                        public void onThrowable(Throwable t) {
                            Loggers.RAFT.error("NACOS-RAFT error while sending heart-beat to peer: {} {}", server, t);
                            MetricsMonitor.getLeaderSendBeatFailedException().increment();
                        }
                    });
                } catch (Exception e) {
                    Loggers.RAFT.error("error while sending heart-beat to peer: {} {}", server, e);
                    MetricsMonitor.getLeaderSendBeatFailedException().increment();
                }
            }

        }
    }

    /**
     * 处理心跳信息
     * @param beat
     * @return
     * @throws Exception
     */
    public RaftPeer receivedBeat(JsonNode beat) throws Exception {
        // 获取本机节点信息
        final RaftPeer local = peers.local();
        final RaftPeer remote = new RaftPeer();
        // 获取发送心跳节点信息
        JsonNode peer = beat.get("peer");
        remote.ip = peer.get("ip").asText();
        remote.state = RaftPeer.State.valueOf(peer.get("state").asText());
        remote.term.set(peer.get("term").asLong());
        remote.heartbeatDueMs = peer.get("heartbeatDueMs").asLong();
        remote.leaderDueMs = peer.get("leaderDueMs").asLong();
        remote.voteFor = peer.get("voteFor").asText();

        // 只有leader才发送心跳
        if (remote.state != RaftPeer.State.LEADER) {
            Loggers.RAFT.info("[RAFT] invalid state from master, state: {}, remote peer: {}",
                remote.state, JacksonUtils.toJson(remote));
            throw new IllegalArgumentException("invalid state from master, state: " + remote.state);
        }

        // 非leader的term大于leader就报错
        if (local.term.get() > remote.term.get()) {
            Loggers.RAFT.info("[RAFT] out of date beat, beat-from-term: {}, beat-to-term: {}, remote peer: {}, and leaderDueMs: {}"
                , remote.term.get(), local.term.get(), JacksonUtils.toJson(remote), local.leaderDueMs);
            throw new IllegalArgumentException("out of date beat, beat-from-term: " + remote.term.get()
                + ", beat-to-term: " + local.term.get());
        }
        /**
         * 本机不是follower，设置本机是follow
         */
        if (local.state != RaftPeer.State.FOLLOWER) {

            Loggers.RAFT.info("[RAFT] make remote as leader, remote peer: {}", JacksonUtils.toJson(remote));
            // mk follower
            local.state = RaftPeer.State.FOLLOWER;
            local.voteFor = remote.ip;
        }

        final JsonNode beatDatums = beat.get("datums");
        // 重置leader选举等待时间，让其不可触发leader选举，当leader心跳挂了，才触发下次leader选举
        local.resetLeaderDue();
        local.resetHeartbeatDue();

        peers.makeLeader(remote);

        if (!switchDomain.isSendBeatOnly()) {

            Map<String, Integer> receivedKeysMap = new HashMap<>(datums.size());

            /**
             * 收集所有旧key
             */
            for (Map.Entry<String, Datum> entry : datums.entrySet()) {
                receivedKeysMap.put(entry.getKey(), 0);
            }

            // now check datums
            List<String> batch = new ArrayList<>();

            int processedCount = 0;
            if (Loggers.RAFT.isDebugEnabled()) {
                Loggers.RAFT.debug("[RAFT] received beat with {} keys, RaftCore.datums' size is {}, remote server: {}, term: {}, local term: {}",
                    beatDatums.size(), datums.size(), remote.ip, remote.term, local.term);
            }
            for (Object object : beatDatums) {
                processedCount = processedCount + 1;

                JsonNode entry = (JsonNode) object;
                String key = entry.get("key").asText();
                final String datumKey;

                if (KeyBuilder.matchServiceMetaKey(key)) {
                    datumKey = KeyBuilder.detailServiceMetaKey(key);
                } else if (KeyBuilder.matchInstanceListKey(key)) {
                    datumKey = KeyBuilder.detailInstanceListkey(key);
                } else {
                    // ignore corrupted key:
                    continue;
                }

                long timestamp = entry.get("timestamp").asLong();

                receivedKeysMap.put(datumKey, 1);

                try {
                    if (datums.containsKey(datumKey) && datums.get(datumKey).timestamp.get() >= timestamp && processedCount < beatDatums.size()) {
                        continue;
                    }

                    /**
                     * 寻找本机timestamp大于leader的key
                     */
                    if (!(datums.containsKey(datumKey) && datums.get(datumKey).timestamp.get() >= timestamp)) {
                        batch.add(datumKey);
                    }

                    /**
                     * 一次处理50个key
                     */
                    if (batch.size() < 50 && processedCount < beatDatums.size()) {
                        continue;
                    }

                    String keys = StringUtils.join(batch, ",");

                    if (batch.size() <= 0) {
                        continue;
                    }

                    Loggers.RAFT.info("get datums from leader: {}, batch size is {}, processedCount is {}, datums' size is {}, RaftCore.datums' size is {}"
                        , getLeader().ip, batch.size(), processedCount, beatDatums.size(), datums.size());

                    /**
                     * 从leader更新 keys对应的datums
                     */
                    // update datum entry
                    String url = buildURL(remote.ip, API_GET) + "?keys=" + URLEncoder.encode(keys, "UTF-8");
                    HttpClient.asyncHttpGet(url, null, null, new AsyncCompletionHandler<Integer>() {
                        @Override
                        public Integer onCompleted(Response response) throws Exception {
                            if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                                return 1;
                            }

                            List<JsonNode> datumList = JacksonUtils.toObj(response.getResponseBody(), new TypeReference<List<JsonNode>>() {});

                            for (JsonNode datumJson : datumList) {
                                OPERATE_LOCK.lock();
                                Datum newDatum = null;
                                try {
                                    /**
                                     * 获取旧的datum
                                     */
                                    Datum oldDatum = getDatum(datumJson.get("key").asText());
                                    // leader的时间戳还是小于follow的不进行更新
                                    if (oldDatum != null && datumJson.get("timestamp").asLong() <= oldDatum.timestamp.get()) {
                                        Loggers.RAFT.info("[NACOS-RAFT] timestamp is smaller than that of mine, key: {}, remote: {}, local: {}",
                                            datumJson.get("key").asText(), datumJson.get("timestamp").asLong(), oldDatum.timestamp);
                                        continue;
                                    }

                                    /**
                                     * 构建新的Datum
                                     */
                                    if (KeyBuilder.matchServiceMetaKey(datumJson.get("key").asText())) {
                                        Datum<Service> serviceDatum = new Datum<>();
                                        serviceDatum.key = datumJson.get("key").asText();
                                        serviceDatum.timestamp.set(datumJson.get("timestamp").asLong());
                                        serviceDatum.value = JacksonUtils.toObj(datumJson.get("value").toString(), Service.class);
                                        newDatum = serviceDatum;
                                    }

                                    if (KeyBuilder.matchInstanceListKey(datumJson.get("key").asText())) {
                                        Datum<Instances> instancesDatum = new Datum<>();
                                        instancesDatum.key = datumJson.get("key").asText();
                                        instancesDatum.timestamp.set(datumJson.get("timestamp").asLong());
                                        instancesDatum.value = JacksonUtils.toObj(datumJson.get("value").toString(), Instances.class);
                                        newDatum = instancesDatum;
                                    }

                                    if (newDatum == null || newDatum.value == null) {
                                        Loggers.RAFT.error("receive null datum: {}", datumJson);
                                        continue;
                                    }

                                    /**
                                     * 将newDatum写入磁盘
                                     */
                                    raftStore.write(newDatum);
                                    // 更新内存数据
                                    datums.put(newDatum.key, newDatum);
                                    // 发送变更通知
                                    notifier.addTask(newDatum.key, ApplyAction.CHANGE);

                                    local.resetLeaderDue();

                                    if (local.term.get() + 100 > remote.term.get()) {
                                        getLeader().term.set(remote.term.get());
                                        local.term.set(getLeader().term.get());
                                    } else {
                                        local.term.addAndGet(100);
                                    }

                                    raftStore.updateTerm(local.term.get());

                                    Loggers.RAFT.info("data updated, key: {}, timestamp: {}, from {}, local term: {}",
                                        newDatum.key, newDatum.timestamp, JacksonUtils.toJson(remote), local.term);

                                } catch (Throwable e) {
                                    Loggers.RAFT.error("[RAFT-BEAT] failed to sync datum from leader, datum: {}", newDatum, e);
                                } finally {
                                    OPERATE_LOCK.unlock();
                                }
                            }
                            TimeUnit.MILLISECONDS.sleep(200);
                            return 0;
                        }
                    });

                    batch.clear();

                } catch (Exception e) {
                    Loggers.RAFT.error("[NACOS-RAFT] failed to handle beat entry, key: {}", datumKey);
                }

            }

            List<String> deadKeys = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : receivedKeysMap.entrySet()) {
                if (entry.getValue() == 0) {
                    deadKeys.add(entry.getKey());
                }
            }

            for (String deadKey : deadKeys) {
                try {
                    deleteDatum(deadKey);
                } catch (Exception e) {
                    Loggers.RAFT.error("[NACOS-RAFT] failed to remove entry, key={} {}", deadKey, e);
                }
            }

        }

        return local;
    }

    public void listen(String key, RecordListener listener) {

        List<RecordListener> listenerList = listeners.get(key);
        if (listenerList != null && listenerList.contains(listener)) {
            return;
        }

        if (listenerList == null) {
            listenerList = new CopyOnWriteArrayList<>();
            listeners.put(key, listenerList);
        }

        Loggers.RAFT.info("add listener: {}", key);

        listenerList.add(listener);

        // if data present, notify immediately
        for (Datum datum : datums.values()) {
            if (!listener.interests(datum.key)) {
                continue;
            }

            try {
                listener.onChange(datum.key, datum.value);
            } catch (Exception e) {
                Loggers.RAFT.error("NACOS-RAFT failed to notify listener", e);
            }
        }
    }

    public void unlisten(String key, RecordListener listener) {

        if (!listeners.containsKey(key)) {
            return;
        }

        for (RecordListener dl : listeners.get(key)) {
            // TODO maybe use equal:
            if (dl == listener) {
                listeners.get(key).remove(listener);
                break;
            }
        }
    }

    public void unlistenAll(String key) {
        listeners.remove(key);
    }

    public void setTerm(long term) {
        peers.setTerm(term);
    }

    public boolean isLeader(String ip) {
        return peers.isLeader(ip);
    }

    public boolean isLeader() {
        return peers.isLeader(NetUtils.localServer());
    }

    public static String buildURL(String ip, String api) {
        if (!ip.contains(UtilsAndCommons.IP_PORT_SPLITER)) {
            ip = ip + UtilsAndCommons.IP_PORT_SPLITER + ApplicationUtils.getPort();
        }
        return "http://" + ip + ApplicationUtils.getContextPath() + api;
    }

    public Datum<?> getDatum(String key) {
        return datums.get(key);
    }

    public RaftPeer getLeader() {
        return peers.getLeader();
    }

    public List<RaftPeer> getPeers() {
        return new ArrayList<>(peers.allPeers());
    }

    public RaftPeerSet getPeerSet() {
        return peers;
    }

    public void setPeerSet(RaftPeerSet peerSet) {
        peers = peerSet;
    }

    public int datumSize() {
        return datums.size();
    }

    public void addDatum(Datum datum) {
        datums.put(datum.key, datum);
        notifier.addTask(datum.key, ApplyAction.CHANGE);
    }

    public void loadDatum(String key) {
        try {
            Datum datum = raftStore.load(key);
            if (datum == null) {
                return;
            }
            datums.put(key, datum);
        } catch (Exception e) {
            Loggers.RAFT.error("load datum failed: " + key, e);
        }

    }

    private void deleteDatum(String key) {
        Datum deleted;
        try {
            deleted = datums.remove(URLDecoder.decode(key, "UTF-8"));
            if (deleted != null) {
                raftStore.delete(deleted);
                Loggers.RAFT.info("datum deleted, key: {}", key);
            }
            notifier.addTask(URLDecoder.decode(key, "UTF-8"), ApplyAction.DELETE);
        } catch (UnsupportedEncodingException e) {
            Loggers.RAFT.warn("datum key decode failed: {}", key);
        }
    }

    public boolean isInitialized() {
        return initialized || !globalConfig.isDataWarmup();
    }

    public int getNotifyTaskCount() {
        return notifier.getTaskSize();
    }

    public class Notifier implements Runnable {

        private ConcurrentHashMap<String, String> services = new ConcurrentHashMap<>(10 * 1024);

        private BlockingQueue<Pair> tasks = new LinkedBlockingQueue<>(1024 * 1024);

        public void addTask(String datumKey, ApplyAction action) {

            if (services.containsKey(datumKey) && action == ApplyAction.CHANGE) {
                return;
            }
            if (action == ApplyAction.CHANGE) {
                services.put(datumKey, StringUtils.EMPTY);
            }

            Loggers.RAFT.info("add task {}", datumKey);

            tasks.add(Pair.with(datumKey, action));
        }

        public int getTaskSize() {
            return tasks.size();
        }

        @Override
        public void run() {
            Loggers.RAFT.info("raft notifier started");

            while (true) {
                try {

                    Pair pair = tasks.take();

                    if (pair == null) {
                        continue;
                    }

                    String datumKey = (String) pair.getValue0();
                    ApplyAction action = (ApplyAction) pair.getValue1();

                    services.remove(datumKey);

                    Loggers.RAFT.info("remove task {}", datumKey);

                    int count = 0;

                    if (listeners.containsKey(KeyBuilder.SERVICE_META_KEY_PREFIX)) {

                        if (KeyBuilder.matchServiceMetaKey(datumKey) && !KeyBuilder.matchSwitchKey(datumKey)) {

                            for (RecordListener listener : listeners.get(KeyBuilder.SERVICE_META_KEY_PREFIX)) {
                                try {
                                    if (action == ApplyAction.CHANGE) {
                                        listener.onChange(datumKey, getDatum(datumKey).value);
                                    }

                                    if (action == ApplyAction.DELETE) {
                                        listener.onDelete(datumKey);
                                    }
                                } catch (Throwable e) {
                                    Loggers.RAFT.error("[NACOS-RAFT] error while notifying listener of key: {}", datumKey, e);
                                }
                            }
                        }
                    }

                    if (!listeners.containsKey(datumKey)) {
                        continue;
                    }

                    for (RecordListener listener : listeners.get(datumKey)) {

                        count++;

                        try {
                            if (action == ApplyAction.CHANGE) {
                                listener.onChange(datumKey, getDatum(datumKey).value);
                                continue;
                            }

                            if (action == ApplyAction.DELETE) {
                                listener.onDelete(datumKey);
                                continue;
                            }
                        } catch (Throwable e) {
                            Loggers.RAFT.error("[NACOS-RAFT] error while notifying listener of key: {}", datumKey, e);
                        }
                    }

                    if (Loggers.RAFT.isDebugEnabled()) {
                        Loggers.RAFT.debug("[NACOS-RAFT] datum change notified, key: {}, listener count: {}", datumKey, count);
                    }
                } catch (Throwable e) {
                    Loggers.RAFT.error("[NACOS-RAFT] Error while handling notifying task", e);
                }
            }
        }
    }
}

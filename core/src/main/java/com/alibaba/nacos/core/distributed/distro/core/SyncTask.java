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

import java.util.List;

/**
 * @author nkorange
 * @since 1.0.0
 */
public class SyncTask {

    private final String group;

    private final List<String> keys;

    private final int retryCount;

    private final long lastExecuteTime;

    private final String targetServer;

    public SyncTask(String group, List<String> keys, int retryCount, long lastExecuteTime,
            String targetServer) {
        this.group = group;
        this.keys = keys;
        this.retryCount = retryCount;
        this.lastExecuteTime = lastExecuteTime;
        this.targetServer = targetServer;
    }

    public String getGroup() {
        return group;
    }

    public List<String> getKeys() {
        return keys;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public long getLastExecuteTime() {
        return lastExecuteTime;
    }

    public String getTargetServer() {
        return targetServer;
    }

    @Override
    public String toString() {
        return "SyncTask{" + "group='" + group + '\'' + ", keys=" + keys + ", retryCount="
                + retryCount + ", lastExecuteTime=" + lastExecuteTime + ", targetServer='"
                + targetServer + '\'' + '}';
    }

    public static SyncTaskBuilder builder() {
        return new SyncTaskBuilder();
    }

    public static final class SyncTaskBuilder {
        private String group;
        private List<String> keys;
        private int retryCount;
        private long lastExecuteTime;
        private String targetServer;

        private SyncTaskBuilder() {
        }

        public SyncTaskBuilder group(String group) {
            this.group = group;
            return this;
        }

        public SyncTaskBuilder keys(List<String> keys) {
            this.keys = keys;
            return this;
        }

        public SyncTaskBuilder retryCount(int retryCount) {
            this.retryCount = retryCount;
            return this;
        }

        public SyncTaskBuilder lastExecuteTime(long lastExecuteTime) {
            this.lastExecuteTime = lastExecuteTime;
            return this;
        }

        public SyncTaskBuilder targetServer(String targetServer) {
            this.targetServer = targetServer;
            return this;
        }

        public SyncTask build() {
            return new SyncTask(group, keys, retryCount, lastExecuteTime, targetServer);
        }
    }
}

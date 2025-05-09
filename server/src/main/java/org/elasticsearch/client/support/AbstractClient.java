/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.client.support;

import java.util.concurrent.CompletableFuture;

import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.health.TransportClusterHealth;
import org.elasticsearch.action.admin.cluster.node.stats.NodesStatsRequest;
import org.elasticsearch.action.admin.cluster.node.stats.NodesStatsResponse;
import org.elasticsearch.action.admin.cluster.node.stats.TransportNodesStats;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.action.admin.cluster.state.TransportClusterState;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshResponse;
import org.elasticsearch.action.admin.indices.refresh.TransportRefresh;
import org.elasticsearch.action.admin.indices.settings.put.TransportUpdateSettings;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsRequest;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse;
import org.elasticsearch.action.admin.indices.stats.TransportIndicesStats;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.AdminClient;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.ClusterAdminClient;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.client.IndicesAdminClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportResponse;

public abstract class AbstractClient implements Client {

    private final ThreadPool threadPool;
    private final Admin admin;
    protected final Settings settings;

    protected AbstractClient(Settings settings, ThreadPool threadPool) {
        this.settings = settings;
        this.threadPool = threadPool;
        this.admin = new Admin(this);
    }

    @Override
    public final Settings settings() {
        return this.settings;
    }

    @Override
    public final ThreadPool threadPool() {
        return this.threadPool;
    }

    @Override
    public final AdminClient admin() {
        return admin;
    }

    static class Admin implements AdminClient {

        private final ClusterAdmin clusterAdmin;
        private final IndicesAdmin indicesAdmin;

        Admin(ElasticsearchClient client) {
            this.clusterAdmin = new ClusterAdmin(client);
            this.indicesAdmin = new IndicesAdmin(client);
        }

        @Override
        public ClusterAdminClient cluster() {
            return clusterAdmin;
        }

        @Override
        public IndicesAdminClient indices() {
            return indicesAdmin;
        }
    }

    static class ClusterAdmin implements ClusterAdminClient {

        private final ElasticsearchClient client;

        ClusterAdmin(ElasticsearchClient client) {
            this.client = client;
        }

        @Override
        public <Req extends TransportRequest, Resp extends TransportResponse> CompletableFuture<Resp> execute(ActionType<Resp> action, Req request) {
            return client.execute(action, request);
        }

        @Override
        public ThreadPool threadPool() {
            return client.threadPool();
        }

        @Override
        public CompletableFuture<ClusterHealthResponse> health(final ClusterHealthRequest request) {
            return execute(TransportClusterHealth.ACTION, request);
        }

        @Override
        public CompletableFuture<ClusterStateResponse> state(final ClusterStateRequest request) {
            return execute(TransportClusterState.ACTION, request);
        }

        @Override
        public CompletableFuture<NodesStatsResponse> nodesStats(final NodesStatsRequest request) {
            return execute(TransportNodesStats.ACTION, request);
        }
    }

    static class IndicesAdmin implements IndicesAdminClient {

        private final ElasticsearchClient client;

        IndicesAdmin(ElasticsearchClient client) {
            this.client = client;
        }

        @Override
        public <Req extends TransportRequest, Resp extends TransportResponse> CompletableFuture<Resp> execute(ActionType<Resp> action, Req request) {
            return client.execute(action, request);
        }

        @Override
        public ThreadPool threadPool() {
            return client.threadPool();
        }

        @Override
        public CompletableFuture<RefreshResponse> refresh(final RefreshRequest request) {
            return execute(TransportRefresh.ACTION, request);
        }

        @Override
        public CompletableFuture<IndicesStatsResponse> stats(final IndicesStatsRequest request) {
            return execute(TransportIndicesStats.ACTION, request);
        }

        @Override
        public CompletableFuture<AcknowledgedResponse> updateSettings(UpdateSettingsRequest request) {
            return execute(TransportUpdateSettings.ACTION, request);
        }
    }
}

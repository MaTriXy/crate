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

package org.elasticsearch.action.support;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRunnable;
import org.elasticsearch.common.util.concurrent.RejectableRunnable;
import org.elasticsearch.threadpool.ThreadPool;

/**
 * An action listener that wraps another action listener and threading its execution.
 */
public final class ThreadedActionListener<Response> implements ActionListener<Response> {

    private final Logger logger;
    private final ThreadPool threadPool;
    private final String executor;
    private final ActionListener<Response> listener;
    private final boolean forceExecution;

    public ThreadedActionListener(Logger logger,
                                  ThreadPool threadPool,
                                  String executor,
                                  ActionListener<Response> listener,
                                  boolean forceExecution) {
        this.logger = logger;
        this.threadPool = threadPool;
        this.executor = executor;
        this.listener = listener;
        this.forceExecution = forceExecution;
    }

    @Override
    public void onResponse(final Response response) {
        threadPool.executor(executor).execute(new ActionRunnable<>(listener) {
            @Override
            public boolean isForceExecution() {
                return forceExecution;
            }

            @Override
            public void doRun() {
                listener.onResponse(response);
            }
        });
    }

    @Override
    public void onFailure(final Exception e) {
        threadPool.executor(executor).execute(new RejectableRunnable() {
            @Override
            public boolean isForceExecution() {
                return forceExecution;
            }

            @Override
            public void doRun() throws Exception {
                listener.onFailure(e);
            }

            @Override
            public void onFailure(Exception e) {
                logger.warn(() -> new ParameterizedMessage("failed to execute failure callback on [{}]", listener), e);
            }
        });
    }
}

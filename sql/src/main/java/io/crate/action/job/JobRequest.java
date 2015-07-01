/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.action.job;

import io.crate.operation.NodeOperation;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.transport.TransportRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;

public class JobRequest extends TransportRequest {

    private UUID jobId;
    private Collection<? extends NodeOperation> nodeOperations;

    protected JobRequest() {
    }

    public JobRequest(UUID jobId, Collection<? extends NodeOperation> nodeOperations) {
        this.jobId = jobId;
        this.nodeOperations = nodeOperations;
    }

    public UUID jobId() {
        return jobId;
    }

    public Collection<? extends NodeOperation> nodeOperations() {
        return nodeOperations;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);

        jobId = new UUID(in.readLong(), in.readLong());

        int numNodeOperations = in.readVInt();
        ArrayList<NodeOperation> nodeOperations = new ArrayList<>(numNodeOperations);
        for (int i = 0; i < numNodeOperations; i++) {
            nodeOperations.add(new NodeOperation(in));
        }
        this.nodeOperations = nodeOperations;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);

        out.writeLong(jobId.getMostSignificantBits());
        out.writeLong(jobId.getLeastSignificantBits());

        out.writeVInt(nodeOperations.size());
        for (NodeOperation nodeOperation : nodeOperations) {
            nodeOperation.writeTo(out);
        }
    }
}

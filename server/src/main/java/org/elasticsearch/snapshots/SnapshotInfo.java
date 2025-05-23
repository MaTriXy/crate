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

package org.elasticsearch.snapshots;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.elasticsearch.Version;
import org.elasticsearch.action.ShardOperationFailedException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ParseField;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.rest.RestStatus;
import org.jetbrains.annotations.Nullable;

import io.crate.server.xcontent.XContentParserUtils;

/**
 * Information about a snapshot
 */
public final class SnapshotInfo implements Comparable<SnapshotInfo>, Writeable {

    public static final String CONTEXT_MODE_PARAM = "context_mode";
    public static final String CONTEXT_MODE_SNAPSHOT = "SNAPSHOT";
    private static final String SNAPSHOT = "snapshot";
    private static final String UUID = "uuid";
    private static final String INDICES = "indices";
    private static final String STATE = "state";
    private static final String REASON = "reason";
    private static final String START_TIME = "start_time";
    private static final String START_TIME_IN_MILLIS = "start_time_in_millis";
    private static final String END_TIME = "end_time";
    private static final String END_TIME_IN_MILLIS = "end_time_in_millis";
    private static final String FAILURES = "failures";
    private static final String SHARDS = "shards";
    private static final String TOTAL = "total";
    private static final String SUCCESSFUL = "successful";
    private static final String VERSION_ID = "version_id";
    private static final String NAME = "name";
    private static final String TOTAL_SHARDS = "total_shards";
    private static final String SUCCESSFUL_SHARDS = "successful_shards";
    private static final String INCLUDE_GLOBAL_STATE = "include_global_state";

    private static final Comparator<SnapshotInfo> COMPARATOR =
        Comparator.comparing(SnapshotInfo::startTime).thenComparing(SnapshotInfo::snapshotId);

    public static final class SnapshotInfoBuilder {
        private String snapshotName = null;
        private String snapshotUUID = null;
        private String state = null;
        private String reason = null;
        private List<String> indices = null;
        private long startTime = 0L;
        private long endTime = 0L;
        private ShardStatsBuilder shardStatsBuilder = null;
        private Boolean includeGlobalState = null;
        private int version = -1;
        private List<SnapshotShardFailure> shardFailures = null;

        private void setSnapshotName(String snapshotName) {
            this.snapshotName = snapshotName;
        }

        private void setSnapshotUUID(String snapshotUUID) {
            this.snapshotUUID = snapshotUUID;
        }

        private void setState(String state) {
            this.state = state;
        }

        private void setReason(String reason) {
            this.reason = reason;
        }

        private void setIndices(List<String> indices) {
            this.indices = indices;
        }

        private void setStartTime(long startTime) {
            this.startTime = startTime;
        }

        private void setEndTime(long endTime) {
            this.endTime = endTime;
        }

        private void setShardStatsBuilder(ShardStatsBuilder shardStatsBuilder) {
            this.shardStatsBuilder = shardStatsBuilder;
        }

        private void setIncludeGlobalState(Boolean includeGlobalState) {
            this.includeGlobalState = includeGlobalState;
        }

        private void setVersion(int version) {
            this.version = version;
        }

        private void setShardFailures(List<SnapshotShardFailure> shardFailures) {
            this.shardFailures = shardFailures;
        }

        public SnapshotInfo build() {
            SnapshotId snapshotId = new SnapshotId(snapshotName, snapshotUUID);

            if (indices == null) {
                indices = Collections.emptyList();
            }

            SnapshotState snapshotState = state == null ? null : SnapshotState.valueOf(state);
            Version version = this.version == -1 ? Version.CURRENT : Version.fromId(this.version);

            int totalShards = shardStatsBuilder == null ? 0 : shardStatsBuilder.getTotalShards();
            int successfulShards = shardStatsBuilder == null ? 0 : shardStatsBuilder.getSuccessfulShards();

            if (shardFailures == null) {
                shardFailures = new ArrayList<>();
            }

            return new SnapshotInfo(snapshotId, indices, snapshotState, reason, version, startTime, endTime,
                    totalShards, successfulShards, shardFailures, includeGlobalState);
        }
    }

    private static final class ShardStatsBuilder {
        private int totalShards;
        private int successfulShards;

        private void setTotalShards(int totalShards) {
            this.totalShards = totalShards;
        }

        int getTotalShards() {
            return totalShards;
        }

        private void setSuccessfulShards(int successfulShards) {
            this.successfulShards = successfulShards;
        }

        int getSuccessfulShards() {
            return successfulShards;
        }
    }

    public static final ObjectParser<SnapshotInfoBuilder, Void> SNAPSHOT_INFO_PARSER =
            new ObjectParser<>(SnapshotInfoBuilder.class.getName(), true, SnapshotInfoBuilder::new);

    private static final ObjectParser<ShardStatsBuilder, Void> SHARD_STATS_PARSER =
        new ObjectParser<>(ShardStatsBuilder.class.getName(), true, ShardStatsBuilder::new);

    static {
        SNAPSHOT_INFO_PARSER.declareString(SnapshotInfoBuilder::setSnapshotName, new ParseField(SNAPSHOT));
        SNAPSHOT_INFO_PARSER.declareString(SnapshotInfoBuilder::setSnapshotUUID, new ParseField(UUID));
        SNAPSHOT_INFO_PARSER.declareString(SnapshotInfoBuilder::setState, new ParseField(STATE));
        SNAPSHOT_INFO_PARSER.declareString(SnapshotInfoBuilder::setReason, new ParseField(REASON));
        SNAPSHOT_INFO_PARSER.declareStringArray(SnapshotInfoBuilder::setIndices, new ParseField(INDICES));
        SNAPSHOT_INFO_PARSER.declareLong(SnapshotInfoBuilder::setStartTime, new ParseField(START_TIME_IN_MILLIS));
        SNAPSHOT_INFO_PARSER.declareLong(SnapshotInfoBuilder::setEndTime, new ParseField(END_TIME_IN_MILLIS));
        SNAPSHOT_INFO_PARSER.declareObject(SnapshotInfoBuilder::setShardStatsBuilder, SHARD_STATS_PARSER, new ParseField(SHARDS));
        SNAPSHOT_INFO_PARSER.declareBoolean(SnapshotInfoBuilder::setIncludeGlobalState, new ParseField(INCLUDE_GLOBAL_STATE));
        SNAPSHOT_INFO_PARSER.declareInt(SnapshotInfoBuilder::setVersion, new ParseField(VERSION_ID));
        SNAPSHOT_INFO_PARSER.declareObjectArray(SnapshotInfoBuilder::setShardFailures, SnapshotShardFailure.SNAPSHOT_SHARD_FAILURE_PARSER,
            new ParseField(FAILURES));

        SHARD_STATS_PARSER.declareInt(ShardStatsBuilder::setTotalShards, new ParseField(TOTAL));
        SHARD_STATS_PARSER.declareInt(ShardStatsBuilder::setSuccessfulShards, new ParseField(SUCCESSFUL));
    }

    private final SnapshotId snapshotId;

    @Nullable
    private final SnapshotState state;

    @Nullable
    private final String reason;

    private final List<String> indices;

    private final long startTime;

    private final long endTime;

    private final int totalShards;

    private final int successfulShards;

    @Nullable
    private Boolean includeGlobalState;

    @Nullable
    private final Version version;

    private final List<SnapshotShardFailure> shardFailures;

    public SnapshotInfo(SnapshotId snapshotId, List<String> indices, SnapshotState state) {
        this(snapshotId, indices, state, null);

    }

    public SnapshotInfo(SnapshotId snapshotId, List<String> indices, SnapshotState state, Version version) {
        this(snapshotId, indices, state, null, version, 0L, 0L, 0, 0, Collections.emptyList(), null);
    }

    public SnapshotInfo(SnapshotId snapshotId, List<String> indices, long startTime, Boolean includeGlobalState) {
        this(snapshotId, indices, SnapshotState.IN_PROGRESS, null, Version.CURRENT, startTime, 0L, 0, 0,
            Collections.emptyList(), includeGlobalState);
    }

    public SnapshotInfo(SnapshotId snapshotId, List<String> indices, long startTime, String reason, long endTime,
                        int totalShards, List<SnapshotShardFailure> shardFailures, Boolean includeGlobalState) {
        this(snapshotId, indices, snapshotState(reason, shardFailures), reason, Version.CURRENT,
             startTime, endTime, totalShards, totalShards - shardFailures.size(), shardFailures, includeGlobalState);
    }

    SnapshotInfo(SnapshotId snapshotId, List<String> indices, SnapshotState state, String reason, Version version,
                 long startTime, long endTime, int totalShards, int successfulShards, List<SnapshotShardFailure> shardFailures,
                 Boolean includeGlobalState) {
        this.snapshotId = Objects.requireNonNull(snapshotId);
        this.indices = Collections.unmodifiableList(Objects.requireNonNull(indices));
        this.state = state;
        this.reason = reason;
        this.version = version;
        this.startTime = startTime;
        this.endTime = endTime;
        this.totalShards = totalShards;
        this.successfulShards = successfulShards;
        this.shardFailures = Objects.requireNonNull(shardFailures);
        this.includeGlobalState = includeGlobalState;
    }

    /**
     * Constructs snapshot information from stream input
     */
    public SnapshotInfo(final StreamInput in) throws IOException {
        snapshotId = new SnapshotId(in);
        indices = Collections.unmodifiableList(in.readStringList());
        state = in.readBoolean() ? SnapshotState.fromValue(in.readByte()) : null;
        reason = in.readOptionalString();
        startTime = in.readVLong();
        endTime = in.readVLong();
        totalShards = in.readVInt();
        successfulShards = in.readVInt();
        shardFailures = Collections.unmodifiableList(in.readList(SnapshotShardFailure::new));
        version = in.readBoolean() ? Version.readVersion(in) : null;
        includeGlobalState = in.readOptionalBoolean();
    }

    /**
     * Gets a new {@link SnapshotInfo} instance from the given {@link SnapshotInfo} with
     * all information stripped out except the snapshot id, state, and indices.
     */
    public SnapshotInfo basic() {
        return new SnapshotInfo(snapshotId, indices, state);
    }

    /**
     * Returns snapshot id
     *
     * @return snapshot id
     */
    public SnapshotId snapshotId() {
        return snapshotId;
    }

    /**
     * Returns snapshot state; {@code null} if the state is unknown.
     *
     * @return snapshot state
     */
    @Nullable
    public SnapshotState state() {
        return state;
    }

    /**
     * Returns snapshot failure reason; {@code null} if the snapshot succeeded.
     *
     * @return snapshot failure reason
     */
    @Nullable
    public String reason() {
        return reason;
    }

    /**
     * Returns indices that were included in this snapshot.
     *
     * @return list of indices
     */
    public List<String> indices() {
        return indices;
    }

    /**
     * Returns time when snapshot started; a value of {@code 0L} will be returned if
     * {@link #state()} returns {@code null}.
     *
     * @return snapshot start time
     */
    public long startTime() {
        return startTime;
    }

    /**
     * Returns time when snapshot ended; a value of {@code 0L} will be returned if the
     * snapshot is still running or if {@link #state()} returns {@code null}.
     *
     * @return snapshot end time
     */
    public long endTime() {
        return endTime;
    }

    /**
     * Returns total number of shards that were snapshotted; a value of {@code 0} will
     * be returned if {@link #state()} returns {@code null}.
     *
     * @return number of shards
     */
    public int totalShards() {
        return totalShards;
    }

    /**
     * Number of failed shards; a value of {@code 0} will be returned if there were no
     * failed shards, or if {@link #state()} returns {@code null}.
     *
     * @return number of failed shards
     */
    public int failedShards() {
        return totalShards - successfulShards;
    }

    /**
     * Returns total number of shards that were successfully snapshotted; a value of
     * {@code 0} will be returned if {@link #state()} returns {@code null}.
     *
     * @return number of successful shards
     */
    public int successfulShards() {
        return successfulShards;
    }

    public Boolean includeGlobalState() {
        return includeGlobalState;
    }

    /**
     * Returns shard failures; an empty list will be returned if there were no shard
     * failures, or if {@link #state()} returns {@code null}.
     *
     * @return shard failures
     */
    public List<SnapshotShardFailure> shardFailures() {
        return shardFailures;
    }

    /**
     * Returns the version of elasticsearch that the snapshot was created with.  Will only
     * return {@code null} if {@link #state()} returns {@code null} or {@link SnapshotState#INCOMPATIBLE}.
     *
     * @return version of elasticsearch that the snapshot was created with
     */
    @Nullable
    public Version version() {
        return version;
    }

    /**
     * Compares two snapshots by their start time; if the start times are the same, then
     * compares the two snapshots by their snapshot ids.
     */
    @Override
    public int compareTo(final SnapshotInfo o) {
        return COMPARATOR.compare(this, o);
    }

    @Override
    public String toString() {
        return "SnapshotInfo{" +
            "snapshotId=" + snapshotId +
            ", state=" + state +
            ", reason='" + reason + '\'' +
            ", indices=" + indices +
            ", startTime=" + startTime +
            ", endTime=" + endTime +
            ", totalShards=" + totalShards +
            ", successfulShards=" + successfulShards +
            ", includeGlobalState=" + includeGlobalState +
            ", version=" + version +
            ", shardFailures=" + shardFailures +
            '}';
    }

    /**
     * Returns snapshot REST status
     */
    public RestStatus status() {
        if (state == SnapshotState.FAILED) {
            return RestStatus.INTERNAL_SERVER_ERROR;
        }
        if (shardFailures.size() == 0) {
            return RestStatus.OK;
        }
        return RestStatus.status(successfulShards, totalShards,
                                 shardFailures.toArray(new ShardOperationFailedException[shardFailures.size()]));
    }

    /**
     * This method creates a SnapshotInfo from external x-content.  It does not
     * handle x-content written with the internal version.
     */
    public static SnapshotInfo fromXContent(final XContentParser parser) throws IOException {
        return SNAPSHOT_INFO_PARSER.parse(parser, null).build();
    }

    /**
     * This method creates a SnapshotInfo from internal x-content.  It does not
     * handle x-content written with the external version as external x-content
     * is only for display purposes and does not need to be parsed.
     */
    public static SnapshotInfo fromXContentInternal(final XContentParser parser) throws IOException {
        String name = null;
        String uuid = null;
        Version version = Version.CURRENT;
        SnapshotState state = SnapshotState.IN_PROGRESS;
        String reason = null;
        List<String> indices = Collections.emptyList();
        long startTime = 0;
        long endTime = 0;
        int totalShards = 0;
        int successfulShards = 0;
        Boolean includeGlobalState = null;
        List<SnapshotShardFailure> shardFailures = Collections.emptyList();
        if (parser.currentToken() == null) { // fresh parser? move to the first token
            parser.nextToken();
        }
        if (parser.currentToken() == XContentParser.Token.START_OBJECT) {  // on a start object move to next token
            parser.nextToken();
        }
        XContentParser.Token token;
        XContentParserUtils.ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.nextToken(), parser);
        String currentFieldName = parser.currentName();
        if (SNAPSHOT.equals(currentFieldName)) {
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                    token = parser.nextToken();
                    if (token.isValue()) {
                        if (NAME.equals(currentFieldName)) {
                            name = parser.text();
                        } else if (UUID.equals(currentFieldName)) {
                            uuid = parser.text();
                        } else if (STATE.equals(currentFieldName)) {
                            state = SnapshotState.valueOf(parser.text());
                        } else if (REASON.equals(currentFieldName)) {
                            reason = parser.text();
                        } else if (START_TIME.equals(currentFieldName)) {
                            startTime = parser.longValue();
                        } else if (END_TIME.equals(currentFieldName)) {
                            endTime = parser.longValue();
                        } else if (TOTAL_SHARDS.equals(currentFieldName)) {
                            totalShards = parser.intValue();
                        } else if (SUCCESSFUL_SHARDS.equals(currentFieldName)) {
                            successfulShards = parser.intValue();
                        } else if (VERSION_ID.equals(currentFieldName)) {
                            version = Version.fromId(parser.intValue());
                        } else if (INCLUDE_GLOBAL_STATE.equals(currentFieldName)) {
                            includeGlobalState = parser.booleanValue();
                        }
                    } else if (token == XContentParser.Token.START_ARRAY) {
                        if (INDICES.equals(currentFieldName)) {
                            ArrayList<String> indicesArray = new ArrayList<>();
                            while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                                indicesArray.add(parser.text());
                            }
                            indices = Collections.unmodifiableList(indicesArray);
                        } else if (FAILURES.equals(currentFieldName)) {
                            ArrayList<SnapshotShardFailure> shardFailureArrayList = new ArrayList<>();
                            while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                                shardFailureArrayList.add(SnapshotShardFailure.fromXContent(parser));
                            }
                            shardFailures = Collections.unmodifiableList(shardFailureArrayList);
                        } else {
                            // It was probably created by newer version - ignoring
                            parser.skipChildren();
                        }
                    } else if (token == XContentParser.Token.START_OBJECT) {
                        // It was probably created by newer version - ignoring
                        parser.skipChildren();
                    }
                }
            }
        }
        if (uuid == null) {
            // the old format where there wasn't a UUID
            uuid = name;
        }
        return new SnapshotInfo(new SnapshotId(name, uuid),
                                indices,
                                state,
                                reason,
                                version,
                                startTime,
                                endTime,
                                totalShards,
                                successfulShards,
                                shardFailures,
                                includeGlobalState);
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        snapshotId.writeTo(out);
        out.writeStringCollection(indices);
        if (state != null) {
            out.writeBoolean(true);
            out.writeByte(state.value());
        } else {
            out.writeBoolean(false);
        }
        out.writeOptionalString(reason);
        out.writeVLong(startTime);
        out.writeVLong(endTime);
        out.writeVInt(totalShards);
        out.writeVInt(successfulShards);
        out.writeList(shardFailures);
        if (version != null) {
            out.writeBoolean(true);
            Version.writeVersion(version, out);
        } else {
            out.writeBoolean(false);
        }
        out.writeOptionalBoolean(includeGlobalState);
    }

    private static SnapshotState snapshotState(final String reason, final List<SnapshotShardFailure> shardFailures) {
        if (reason == null) {
            if (shardFailures.isEmpty()) {
                return SnapshotState.SUCCESS;
            } else {
                return SnapshotState.PARTIAL;
            }
        } else {
            return SnapshotState.FAILED;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SnapshotInfo that = (SnapshotInfo) o;
        return startTime == that.startTime &&
            endTime == that.endTime &&
            totalShards == that.totalShards &&
            successfulShards == that.successfulShards &&
            Objects.equals(snapshotId, that.snapshotId) &&
            state == that.state &&
            Objects.equals(reason, that.reason) &&
            Objects.equals(indices, that.indices) &&
            Objects.equals(includeGlobalState, that.includeGlobalState) &&
            Objects.equals(version, that.version) &&
            Objects.equals(shardFailures, that.shardFailures);
    }

    @Override
    public int hashCode() {

        return Objects.hash(snapshotId, state, reason, indices, startTime, endTime,
                totalShards, successfulShards, includeGlobalState, version, shardFailures);
    }
}

/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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

package io.crate.types;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.function.Function;

import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import io.crate.Streamer;
import io.crate.execution.dml.IntIndexer;
import io.crate.execution.dml.ValueIndexer;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.Reference;
import io.crate.metadata.RelationName;
import io.crate.statistics.ColumnStatsSupport;

public class ShortType extends DataType<Short> implements Streamer<Short>, FixedWidthType {

    public static final ShortType INSTANCE = new ShortType();
    public static final int ID = 8;
    public static final int PRECISION = 16;
    private static final int SHORT_SIZE = (int) RamUsageEstimator.shallowSizeOfInstance(Short.class);
    private static final StorageSupport<Number> STORAGE = new StorageSupport<>(true, true, new IntEqQuery()) {

        @Override
        public ValueIndexer<Number> valueIndexer(RelationName table,
                                                 Reference ref,
                                                 Function<ColumnIdent, Reference> getRef) {
            return new IntIndexer(ref);
        }

        @Override
        public Short decode(int input) {
            return (short) input;
        }
    };

    private ShortType() {
    }

    @Override
    public int id() {
        return ID;
    }

    @Override
    public Precedence precedence() {
        return Precedence.SHORT;
    }

    @Override
    public Integer numericPrecision() {
        return PRECISION;
    }

    @Override
    public String getName() {
        return "smallint";
    }

    @Override
    public Streamer<Short> streamer() {
        return this;
    }

    @Override
    public Short implicitCast(Object value) throws IllegalArgumentException, ClassCastException {
        if (value == null) {
            return null;
        } else if (value instanceof Short s) {
            return s;
        } else if (value instanceof String str) {
            return Short.valueOf(str);
        } else if (value instanceof BigDecimal bigDecimal) {
            try {
                return bigDecimal.shortValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("short value out of range: " + value);
            }
        } else if (value instanceof Number number) {
            int intVal = number.intValue();
            if (intVal < Short.MIN_VALUE || Short.MAX_VALUE < intVal) {
                throw new IllegalArgumentException("short value out of range: " + intVal);
            }
            return ((Number) value).shortValue();
        } else {
            throw new ClassCastException("Can't cast '" + value + "' to " + getName());
        }
    }

    @Override
    public Short sanitizeValue(Object value) {
        if (value == null) {
            return null;
        } else if (value instanceof Short s) {
            return s;
        } else {
            return ((Number) value).shortValue();
        }
    }

    @Override
    public int compare(Short val1, Short val2) {
        return Short.compare(val1, val2);
    }

    @Override
    public Short readValueFrom(StreamInput in) throws IOException {
        return in.readBoolean() ? null : in.readShort();
    }

    @Override
    public void writeValueTo(StreamOutput out, Short v) throws IOException {
        out.writeBoolean(v == null);
        if (v != null) {
            out.writeShort(v);
        }
    }

    @Override
    public int fixedSize() {
        return SHORT_SIZE;
    }

    @Override
    public StorageSupport<Number> storageSupport() {
        return STORAGE;
    }

    @Override
    public ColumnStatsSupport<Short> columnStatsSupport() {
        return ColumnStatsSupport.singleValued(Short.class, ShortType.this);
    }

    @Override
    public long valueBytes(Short value) {
        return SHORT_SIZE;
    }
}


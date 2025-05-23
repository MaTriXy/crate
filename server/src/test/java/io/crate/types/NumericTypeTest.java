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

import static io.crate.expression.symbol.Literal.BOOLEAN_FALSE;
import static io.crate.expression.symbol.Literal.BOOLEAN_TRUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.junit.Test;

import io.crate.metadata.CoordinatorTxnCtx;
import io.crate.metadata.settings.SessionSettings;
import io.crate.testing.SQLExecutor;

public class NumericTypeTest extends DataTypeTestCase<BigDecimal> {

    private static final SessionSettings SESSION_SETTINGS = CoordinatorTxnCtx.systemTransactionContext().sessionSettings();

    @Test
    public void test_scale_must_be_lt_precision() throws Exception {
        assertThatThrownBy(() -> new NumericType(2, 4))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessage("Scale of numeric must be less or equal the precision. NUMERIC(2, 4) is unsupported.");
    }

    @Test
    public void test_scale_cant_be_negative() throws Exception {
        assertThatThrownBy(() -> new NumericType(2, -4))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessage("Scale of NUMERIC must not be negative");
    }

    @Test
    public void test_precision_is_required_if_scale_is_set() throws Exception {
        assertThatThrownBy(() -> new NumericType(null, 4))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessage("If scale is set for NUMERIC, precision must be set too");
    }

    @Test
    public void test_implicit_cast_text_to_unscaled_numeric() {
        assertThat(NumericType.INSTANCE.implicitCast("12839")).isEqualTo(BigDecimal.valueOf(12839));
        assertThat(NumericType.INSTANCE.implicitCast("-12839")).isEqualTo(BigDecimal.valueOf(-12839));
        assertThat(NumericType.INSTANCE.implicitCast("+2147483647111")).isEqualTo(BigDecimal.valueOf(2147483647111L));
        assertThat(NumericType.INSTANCE.implicitCast("+214748364711119475")).isEqualTo(new BigDecimal("214748364711119475"));
    }

    @Test
    public void test_implicit_cast_floating_point_to_unscaled_numeric() {
        assertThat(NumericType.INSTANCE.implicitCast(10.0d)).isEqualTo(BigDecimal.valueOf(10.0));
        assertThat(NumericType.INSTANCE.implicitCast(1.023f)).isEqualTo(BigDecimal.valueOf(1.023));
    }

    @Test
    public void test_implicit_cast_decimal_types_to_unscaled_numeric() {
        assertThat(NumericType.INSTANCE.implicitCast(1)).isEqualTo(BigDecimal.valueOf(1));
        assertThat(NumericType.INSTANCE.implicitCast(2L)).isEqualTo(BigDecimal.valueOf(2));
        assertThat(NumericType.INSTANCE.implicitCast((short) 3)).isEqualTo(BigDecimal.valueOf(3));
        assertThat(NumericType.INSTANCE.implicitCast((byte) 4)).isEqualTo(BigDecimal.valueOf(4));
    }

    @Test
    public void test_implicit_cast_text_types_to_numeric_with_precision() {
        assertThat(new NumericType(5, 0).implicitCast("12345")).isEqualTo(BigDecimal.valueOf(12345));
        assertThat(new NumericType(6, null).implicitCast("12345")).isEqualTo(BigDecimal.valueOf(12345));
    }

    @Test
    public void test_implicit_cast_text_types_to_numeric_with_precision_and_scale() {
        assertThat(new NumericType(16, 0).implicitCast("12345")).isEqualTo(BigDecimal.valueOf(12345));
        assertThat(new NumericType(16, 2).implicitCast("12345").toString()).isEqualTo("12345.00");
        assertThat(new NumericType(10, 4).implicitCast("12345").toString()).isEqualTo("12345.0000");
    }

    @Test
    public void test_implicit_cast_decimal_types_to_numeric_with_precision() {
        assertThat(new NumericType(5, null).implicitCast(12345)).isEqualTo(BigDecimal.valueOf(12345));
        assertThat(new NumericType(6, null).implicitCast(12345)).isEqualTo(BigDecimal.valueOf(12345));
    }

    @Test
    public void test_implicit_cast_decimal_types_to_numeric_with_precision_and_scale() {
        assertThat(new NumericType(16, 0).implicitCast(12345)).isEqualTo(BigDecimal.valueOf(12345));
        assertThat(new NumericType(16, 2).implicitCast(12345).toString()).isEqualTo("12345.00");
        assertThat(new NumericType(10, 4).implicitCast(12345).toString()).isEqualTo("12345.0000");
    }

    @Test
    public void test_implicit_cast_floating_point_to_numeric_with_precision() {
        assertThat(new NumericType(2, 0).implicitCast(10.1234d)).isEqualTo(BigDecimal.valueOf(10));
        assertThat(new NumericType(3, 0).implicitCast(10.1234d)).isEqualTo(BigDecimal.valueOf(10));
        assertThat(new NumericType(3, 0).implicitCast(10.9234d)).isEqualTo(BigDecimal.valueOf(11));
    }

    @Test
    public void test_implicit_cast_floating_point_to_numeric_with_precision_and_scale() {
        assertThat(new NumericType(6, 0).implicitCast(10.1235d)).isEqualTo(BigDecimal.valueOf(10));
        assertThat(new NumericType(6, 2).implicitCast(10.1235d)).isEqualTo(BigDecimal.valueOf(10.12));
        assertThat(new NumericType(6, 3).implicitCast(10.1235d)).isEqualTo(BigDecimal.valueOf(10.124));
        assertThat(new NumericType(6, 3).implicitCast(123.4567d)).isEqualTo(BigDecimal.valueOf(123.457));
    }

    @Test
    public void test_implicit_cast_to_itself() {
        assertThat(NumericType.INSTANCE.implicitCast(BigDecimal.valueOf(1))).isEqualTo(BigDecimal.valueOf(1));
    }

    @Test
    public void test_implicit_cast_null_value() {
        assertThat(NumericType.INSTANCE.implicitCast(null)).isNull();
    }

    public void test_sanitize_numeric_value() {
        assertThat(NumericType.INSTANCE.sanitizeValue(BigDecimal.valueOf(1))).isEqualTo(BigDecimal.valueOf(1));
    }

    @Test
    public void test_cast_boolean_to_smallint_throws_exception() {
        assertThatThrownBy(() -> NumericType.INSTANCE.implicitCast(true))
            .isExactlyInstanceOf(ClassCastException.class)
            .hasMessage("Cannot cast 'true' to numeric");
    }

    @Test
    public void test_cast_array_to_numeric_throws_exception() {
        assertThatThrownBy(() -> NumericType.INSTANCE.implicitCast(List.of()))
            .isExactlyInstanceOf(ClassCastException.class)
            .hasMessage("Cannot cast '[]' to numeric");
    }

    @Test
    public void test_cast_row_to_numeric_throws_exception() {
        assertThatThrownBy(() -> NumericType.INSTANCE.implicitCast(RowType.EMPTY))
            .isExactlyInstanceOf(ClassCastException.class)
            .hasMessage("Cannot cast 'record' to numeric");
    }

    @Test
    public void test_cast_object_to_smallint_throws_exception() {
        assertThatThrownBy(() -> NumericType.INSTANCE.implicitCast(Map.of()))
            .isExactlyInstanceOf(ClassCastException.class)
            .hasMessage("Cannot cast '{}' to numeric");
    }

    @Test
    public void test_numeric_null_value_streaming() throws IOException {
        BytesStreamOutput out = new BytesStreamOutput();
        NumericType.INSTANCE.writeValueTo(out, null);

        StreamInput in = out.bytes().streamInput();

        assertThat(NumericType.INSTANCE.readValueFrom(in)).isNull();
    }

    @Test
    public void test_numeric_value_streaming() throws IOException {
        BigDecimal expected = BigDecimal.TEN;

        BytesStreamOutput out = new BytesStreamOutput();
        NumericType.INSTANCE.writeValueTo(out, expected);

        StreamInput in = out.bytes().streamInput();
        BigDecimal actual = NumericType.INSTANCE.readValueFrom(in);

        assertThat(expected).isEqualTo(actual);
    }

    @Test
    public void test_numeric_value_streaming_does_not_loose_scale() throws IOException {
        BigDecimal expected = BigDecimal.valueOf(1234, 2);

        BytesStreamOutput out = new BytesStreamOutput();
        NumericType.INSTANCE.writeValueTo(out, expected);

        StreamInput in = out.bytes().streamInput();
        BigDecimal actual = NumericType.INSTANCE.readValueFrom(in);

        assertThat(expected).isEqualTo(actual);
    }

    @Test
    public void test_unscaled_numeric_serialization_round_trip() throws IOException {
        var out = new BytesStreamOutput();
        DataTypes.toStream(NumericType.INSTANCE, out);

        var in = out.bytes().streamInput();
        NumericType actual = (NumericType) DataTypes.fromStream(in);

        assertThat(actual.numericPrecision()).isNull();
        assertThat(actual.scale()).isNull();
    }

    @Test
    public void test_numeric_with_precision_and_scale_serialization_round_trip() throws IOException {
        var out = new BytesStreamOutput();
        var expected = new NumericType(3, 1);
        DataTypes.toStream(expected, out);

        var in = out.bytes().streamInput();
        NumericType actual = (NumericType) DataTypes.fromStream(in);

        assertThat(actual.numericPrecision()).isEqualTo(3);
        assertThat(actual.scale()).isEqualTo(1);
    }

    @Override
    protected DataDef<BigDecimal> getDataDef() {
        var random = random();
        int precision = random.nextInt(2, 39);
        int scale = random.nextInt(0, precision - 1);
        return DataDef.fromType(new NumericType(precision, scale));
    }

    @Test
    public void test_equals_with_different_precision() throws Exception {
        SQLExecutor e = SQLExecutor.of(clusterService);
        assertThat(e.asSymbol("1.11::numeric(3, 1) = 1.11::numeric(3, 2)")).isEqualTo(BOOLEAN_FALSE);
        assertThat(e.asSymbol("1.11::numeric(3, 1) = 1.10::numeric(3, 2)")).isEqualTo(BOOLEAN_TRUE);
        assertThat(e.asSymbol("1.11::numeric(3, 1) = 1.11::numeric")).isEqualTo(BOOLEAN_FALSE);
        assertThat(e.asSymbol("1.1::numeric(5,1) = 1.11::numeric(4,2)")).isEqualTo(BOOLEAN_FALSE);
    }

    @Test
    public void test_comparisons_operators_with_different_precision_and_negative_values() {
        SQLExecutor e = SQLExecutor.of(clusterService);
        assertThat(e.asSymbol("1.11::numeric(3, 1) >= 1.10::numeric(3, 2)")).isEqualTo(BOOLEAN_TRUE);
        assertThat(e.asSymbol("-2.1::numeric(5, 1) > -3.1::numeric(4, 2)")).isEqualTo(BOOLEAN_TRUE);
    }

    @Test
    public void test_cast_number_if_precision_is_lost_throws_exception() {
        assertThatThrownBy(() -> new NumericType(6, 3).explicitCast(1234.567d, SESSION_SETTINGS))
            .isExactlyInstanceOf(ClassCastException.class)
            .hasMessage("Cannot cast '1234.567' to numeric(6,3) as it looses precision");
    }
}

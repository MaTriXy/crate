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

package io.crate.planner.optimizer.rule;

import static io.crate.planner.optimizer.matcher.Pattern.typeOf;
import static io.crate.planner.optimizer.matcher.Patterns.source;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.crate.analyze.relations.QuerySplitter;
import io.crate.expression.operator.AndOperator;
import io.crate.expression.symbol.SelectSymbol;
import io.crate.expression.symbol.Symbol;
import io.crate.planner.operators.CorrelatedJoin;
import io.crate.planner.operators.Filter;
import io.crate.planner.operators.LogicalPlan;
import io.crate.planner.optimizer.Rule;
import io.crate.planner.optimizer.matcher.Capture;
import io.crate.planner.optimizer.matcher.Captures;
import io.crate.planner.optimizer.matcher.Pattern;

public final class MoveFilterBeneathCorrelatedJoin implements Rule<Filter> {

    private final Capture<CorrelatedJoin> joinCapture;
    private final Pattern<Filter> pattern;

    public MoveFilterBeneathCorrelatedJoin() {
        this.joinCapture = new Capture<>();
        this.pattern = typeOf(Filter.class)
            .with(source(), typeOf(CorrelatedJoin.class).capturedAs(joinCapture));
    }

    @Override
    public Pattern<Filter> pattern() {
        return pattern;
    }

    @Override
    public LogicalPlan apply(Filter filter,
                             Captures captures,
                             Rule.Context ruleContext) {
        var join = captures.get(joinCapture);
        var splitQuery = QuerySplitter.split(filter.query());
        assert join.sources().size() == 1 : "CorrelatedJoin operator must have 1 children, the input plan";
        var inputPlan = join.sources().get(0);
        var inputQuery = splitQuery.remove(new HashSet<>(inputPlan.relationNames()));
        if (inputQuery == null) {
            return null;
        }
        // Only push down filters which are not part of the correlated sub-queries
        var correlatedSubQueries = extractCorrelatedSubQueries(inputQuery);
        if (correlatedSubQueries.remainder().isEmpty()) {
            return null;
        }

        var newInputPlan = new Filter(inputPlan, AndOperator.join(correlatedSubQueries.remainder()));
        var newJoin = join.replaceSources(List.of(newInputPlan));
        return new Filter(newJoin, AndOperator.join(splitQuery.values()));
    }

    /**
     * Splits the given Symbol tree into a list of correlated sub-queries and a list of remaining symbols.
     */
    public static CorrelatedSubQueries extractCorrelatedSubQueries(@Nullable Symbol from) {
        if (from == null) {
            return new CorrelatedSubQueries(List.of(), List.of());
        }
        var values = QuerySplitter.split(from).values();
        var remainder = new ArrayList<Symbol>(values.size());
        var correlatedSubQueries = new ArrayList<Symbol>(values.size());
        for (var symbol : values) {
            if (symbol.any(s -> s instanceof SelectSymbol x && x.isCorrelated())) {
                correlatedSubQueries.add(symbol);
            } else {
                remainder.add(symbol);
            }
        }
        return new CorrelatedSubQueries(correlatedSubQueries, remainder);
    }

    public record CorrelatedSubQueries(List<Symbol> correlatedSubQueries, List<Symbol> remainder) {
    }
}

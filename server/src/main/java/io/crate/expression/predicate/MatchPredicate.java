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

package io.crate.expression.predicate;

import static io.crate.geo.LatLonShapeUtils.newLatLonShapeQuery;
import static io.crate.types.GeoShapeType.Names.TREE_BKD;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.spatial.prefix.PrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.RecursivePrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.tree.GeohashPrefixTree;
import org.apache.lucene.spatial.prefix.tree.PackedQuadPrefixTree;
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree;
import org.apache.lucene.spatial.prefix.tree.SpatialPrefixTree;
import org.apache.lucene.spatial.query.SpatialArgs;
import org.apache.lucene.spatial.query.SpatialOperation;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.geo.GeoUtils;
import org.elasticsearch.common.geo.ShapeRelation;
import org.elasticsearch.common.geo.builders.ShapeBuilder;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.index.query.MultiMatchQueryType;
import org.elasticsearch.index.search.MatchQuery;
import org.elasticsearch.index.search.MultiMatchQuery;
import org.jetbrains.annotations.Nullable;
import org.locationtech.spatial4j.shape.Shape;

import io.crate.analyze.MatchOptionsAnalysis;
import io.crate.data.Input;
import io.crate.execution.dml.GeoShapeIndexer;
import io.crate.expression.symbol.Function;
import io.crate.expression.symbol.Literal;
import io.crate.expression.symbol.Symbol;
import io.crate.geo.GeoJSONUtils;
import io.crate.lucene.FunctionToQuery;
import io.crate.lucene.LuceneQueryBuilder;
import io.crate.lucene.LuceneQueryBuilder.Context;
import io.crate.lucene.match.OptionParser;
import io.crate.lucene.match.ParsedOptions;
import io.crate.metadata.FunctionImplementation;
import io.crate.metadata.FunctionType;
import io.crate.metadata.Functions;
import io.crate.metadata.GeneratedReference;
import io.crate.metadata.GeoReference;
import io.crate.metadata.Reference;
import io.crate.metadata.Scalar;
import io.crate.metadata.functions.BoundSignature;
import io.crate.metadata.functions.Signature;
import io.crate.types.DataType;
import io.crate.types.DataTypes;

/**
 * The match predicate is only used to generate lucene queries from.
 */
public class MatchPredicate implements FunctionImplementation, FunctionToQuery {

    public static final String NAME = "match";

    /**
     * the match predicate is registered as a regular function
     * though it is called differently by the parser. We mangle
     * the arguments and options for the match predicate into
     * function arguments.
     * <p>
     * 1. list of column idents and boost values - object mapping column name to boost value (Double) (values nullable)
     * 2. query string - string
     * 3. match_type - string (nullable)
     * 4. match_type options - object mapping option name to value (Object) (nullable)
     */
    public static final Signature TEXT_MATCH = Signature.builder(NAME, FunctionType.SCALAR)
        .argumentTypes(DataTypes.UNTYPED_OBJECT.getTypeSignature(),
            DataTypes.STRING.getTypeSignature(),
            DataTypes.STRING.getTypeSignature(),
            DataTypes.UNTYPED_OBJECT.getTypeSignature())
        .returnType(DataTypes.BOOLEAN.getTypeSignature())
        .features(Scalar.Feature.DETERMINISTIC, Scalar.Feature.NOTNULL)
        .build();

    public static final Signature GEO_MATCH = Signature.builder(NAME, FunctionType.SCALAR)
        .argumentTypes(DataTypes.UNTYPED_OBJECT.getTypeSignature(),
            DataTypes.GEO_SHAPE.getTypeSignature(),
            DataTypes.STRING.getTypeSignature(),
            DataTypes.UNTYPED_OBJECT.getTypeSignature())
        .returnType(DataTypes.BOOLEAN.getTypeSignature())
        .features(Scalar.Feature.DETERMINISTIC, Scalar.Feature.NOTNULL)
        .build();


    public static void register(Functions.Builder builder) {
        builder.add(GEO_MATCH, MatchPredicate::new);
        builder.add(TEXT_MATCH, MatchPredicate::new);
    }

    public static final Set<DataType<?>> SUPPORTED_TYPES = Set.of(DataTypes.STRING, DataTypes.GEO_SHAPE);

    private static final Map<DataType<?>, String> DATA_TYPE_TO_DEFAULT_MATCH_TYPE = Map.of(
        DataTypes.STRING,
        MultiMatchQueryType.BEST_FIELDS.toString().toLowerCase(Locale.ENGLISH),
        DataTypes.GEO_SHAPE,
        "intersects"
    );

    private static final Set<String> SUPPORTED_GEO_MATCH_TYPES = Set.of("intersects", "disjoint", "within");

    private final Signature signature;
    private final BoundSignature boundSignature;

    private MatchPredicate(Signature signature, BoundSignature boundSignature) {
        this.signature = signature;
        this.boundSignature = boundSignature;
    }

    @Override
    public Signature signature() {
        return signature;
    }

    @Override
    public BoundSignature boundSignature() {
        return boundSignature;
    }

    private static String defaultMatchType(DataType<?> dataType) {
        String matchType = DATA_TYPE_TO_DEFAULT_MATCH_TYPE.get(dataType);
        if (matchType == null) {
            throw new IllegalArgumentException("No default matchType found for dataType: " + dataType);
        }
        return matchType;
    }

    public static String getMatchType(@Nullable String matchType, DataType<?> columnType) {
        if (matchType == null) {
            return defaultMatchType(columnType);
        }
        if (columnType.equals(DataTypes.STRING)) {
            try {
                MultiMatchQueryType.parse(matchType);
                return matchType;
            } catch (ElasticsearchParseException e) {
                throw new IllegalArgumentException(String.format(
                    Locale.ENGLISH,
                    "invalid MATCH type '%s' for type '%s'",
                    matchType,
                    columnType), e);
            }
        } else if (columnType.equals(DataTypes.GEO_SHAPE)) {
            if (!SUPPORTED_GEO_MATCH_TYPES.contains(matchType)) {
                throw new IllegalArgumentException(String.format(
                    Locale.ENGLISH,
                    "invalid MATCH type '%s' for type '%s', valid types are: [%s]",
                    matchType,
                    columnType,
                    String.join(",", SUPPORTED_GEO_MATCH_TYPES)));
            }
            return matchType;
        }
        throw new IllegalArgumentException("No match type for dataType: " + columnType);
    }

    @Override
    public Query toQuery(Function function, Context context) {
        List<Symbol> arguments = function.arguments();
        assert arguments.size() == 4 : "invalid number of arguments";
        assert Symbol.isLiteral(arguments.get(0), DataTypes.UNTYPED_OBJECT) :
            "fields must be literal";
        assert Symbol.isLiteral(arguments.get(2), DataTypes.STRING) :
            "matchType must be literal";

        Symbol queryTerm = arguments.get(1);
        if (!(queryTerm instanceof Input<?> input)) {
            throw new IllegalArgumentException("queryTerm must be a literal");
        }
        Object queryTermVal = input.value();
        if (queryTermVal == null) {
            throw new IllegalArgumentException("cannot use NULL as query term in match predicate");
        }
        if (queryTerm.valueType().equals(DataTypes.GEO_SHAPE)) {
            return geoMatch(context, arguments, queryTermVal);
        }
        return stringMatch(context, arguments, queryTermVal);
    }

    @SuppressWarnings("unchecked")
    private Query geoMatch(LuceneQueryBuilder.Context context, List<Symbol> arguments, Object queryTerm) {
        Map<String, Object> fields = (Map<String, Object>) ((Literal<?>) arguments.get(0)).value();
        String fieldName = fields.keySet().iterator().next();

        Reference ref = context.getRef(fieldName);
        while (ref instanceof GeneratedReference genRef) {
            ref = genRef.reference();
        }
        if (ref == null || !(ref instanceof GeoReference geoRef)) {
            return Queries.newUnmappedFieldQuery(fieldName);
        }

        String matchType = (String) ((Input<?>) arguments.get(2)).value();
        ShapeRelation relation = ShapeRelation.getRelationByName(matchType);
        assert relation != null : "invalid matchType: " + matchType;

        Map<String, Object> geoJSONMap = (Map<String, Object>) queryTerm;
        String tree = geoRef.geoTree();

        if (TREE_BKD.equals(tree)) {
            return bkdTreeQuery(fieldName, relation, geoJSONMap);
        }
        return prefixTreeQuery(fieldName, geoRef, relation, geoJSONMap);
    }

    private static Query bkdTreeQuery(String fieldName, ShapeRelation relation, Map<String, Object> geoJSONMap) {
        Object shape = GeoJSONUtils.map2LuceneShape(geoJSONMap);
        return new ConstantScoreQuery(newLatLonShapeQuery(fieldName, relation.getLuceneRelation(), shape));
    }

    private static Query prefixTreeQuery(String fieldName,
                                         GeoReference geoReference,
                                         ShapeRelation relation,
                                         Map<String, Object> geoJSONMap) {
        Shape shape = GeoJSONUtils.map2Shape(geoJSONMap);

        PrefixTreeStrategy prefixTreeStrategy = defaultStrategy(geoReference);
        if (relation == ShapeRelation.DISJOINT) {
            /**
             * See {@link org.elasticsearch.index.query.GeoShapeQueryParser}:
             */
            // this strategy doesn't support disjoint anymore: but it did before, including creating lucene fieldcache (!)
            // in this case, execute disjoint as exists && !intersects

            BooleanQuery.Builder bool = new BooleanQuery.Builder();

            Query exists = new TermRangeQuery(fieldName, null, null, true, true);

            Query intersects = prefixTreeStrategy.makeQuery(getArgs(shape, ShapeRelation.INTERSECTS));
            bool.add(exists, BooleanClause.Occur.MUST);
            bool.add(intersects, BooleanClause.Occur.MUST_NOT);
            return new ConstantScoreQuery(bool.build());
        }

        SpatialArgs spatialArgs = getArgs(shape, relation);
        return prefixTreeStrategy.makeQuery(spatialArgs);
    }

    public static double defaultDistanceErrorPct(int treeLevels, double precisionInMeters) {
        return treeLevels == 0 && precisionInMeters < 0
            ? GeoShapeIndexer.Defaults.LEGACY_DISTANCE_ERROR_PCT
            : GeoShapeIndexer.Defaults.DISTANCE_ERROR_PCT;
    }


    public static PrefixTreeStrategy defaultStrategy(GeoReference geoReference) {
        Integer treeLevels = geoReference.treeLevels();
        if (treeLevels == null) {
            treeLevels = 0;
        }
        String precision = geoReference.precision();
        double precisionInMeters = precision == null ? -1 : DistanceUnit.parse(precision, DistanceUnit.DEFAULT, DistanceUnit.DEFAULT);
        Double distanceErrorPct = geoReference.distanceErrorPct();
        String geoTree = geoReference.geoTree();
        SpatialPrefixTree prefixTree = switch (geoTree) {
            case "geohash" -> new GeohashPrefixTree(
                ShapeBuilder.SPATIAL_CONTEXT,
                getLevels(treeLevels, precisionInMeters, GeoShapeIndexer.Defaults.GEOHASH_LEVELS, true)
            );

            case "legacyquadtree" -> new QuadPrefixTree(
                ShapeBuilder.SPATIAL_CONTEXT,
                getLevels(treeLevels, precisionInMeters, GeoShapeIndexer.Defaults.QUADTREE_LEVELS, false)
            );

            case "quadtree" -> new PackedQuadPrefixTree(
                ShapeBuilder.SPATIAL_CONTEXT,
                getLevels(treeLevels, precisionInMeters, GeoShapeIndexer.Defaults.QUADTREE_LEVELS, false)
            );

            default -> throw new IllegalArgumentException("Unknown prefix tree type: " + geoTree);
        };
        var recursiveStrategy = new RecursivePrefixTreeStrategy(prefixTree, geoReference.storageIdent());
        recursiveStrategy.setDistErrPct(
            distanceErrorPct == null ? defaultDistanceErrorPct(treeLevels, precisionInMeters) : distanceErrorPct);
        recursiveStrategy.setPruneLeafyBranches(false);
        return recursiveStrategy;
    }

    private static int getLevels(int treeLevels, double precisionInMeters, int defaultLevels, boolean geoHash) {
        if (treeLevels > 0 || precisionInMeters >= 0) {
            return Math.max(treeLevels, precisionInMeters >= 0 ? (geoHash ? GeoUtils.geoHashLevelsForPrecision(precisionInMeters)
                : GeoUtils.quadTreeLevelsForPrecision(precisionInMeters)) : 0);
        }
        return defaultLevels;
    }

    private static SpatialArgs getArgs(Shape shape, ShapeRelation relation) {
        switch (relation) {
            case INTERSECTS:
                return new SpatialArgs(SpatialOperation.Intersects, shape);
            case DISJOINT:
                return new SpatialArgs(SpatialOperation.IsDisjointTo, shape);
            case WITHIN:
                return new SpatialArgs(SpatialOperation.IsWithin, shape);
            default:
                throw invalidMatchType(relation.getRelationName());
        }
    }

    private static AssertionError invalidMatchType(String matchType) {
        throw new AssertionError(String.format(Locale.ENGLISH,
            "Invalid match type: %s. Analyzer should have made sure that it is valid", matchType));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Query stringMatch(LuceneQueryBuilder.Context context, List<Symbol> arguments, Object queryTerm) {

        Map<String, Object> fields = (Map<String, Object>) ((Literal<?>) arguments.get(0)).value();
        String queryString = (String) queryTerm;
        String matchType = (String) ((Literal<?>) arguments.get(2)).value();
        Map<String, Object> options = (Map<String, Object>) ((Literal<?>) arguments.get(3)).value();

        MatchOptionsAnalysis.validate(options);

        if (fields.size() == 1) {
            return singleMatchQuery(
                context,
                fields.entrySet().iterator().next(),
                queryString,
                matchType,
                options
            );
        } else {
            fields.replaceAll((s, o) -> {
                if (o instanceof Number number) {
                    return number.floatValue();
                }
                return null;
            });
            return multiMatch(
                context,
                matchType,
                (Map) fields,
                queryString,
                options
            );
        }
    }

    private static Query singleMatchQuery(LuceneQueryBuilder.Context context,
                                          Map.Entry<String, Object> entry,
                                          String queryString,
                                          String matchType,
                                          Map<String, Object> options) {
        MultiMatchQueryType type = getType(matchType);
        ParsedOptions parsedOptions = OptionParser.parse(type, options);

        MatchQuery matchQuery = new MatchQuery(context, parsedOptions);
        MatchQuery.Type matchQueryType = type.matchQueryType();
        String fieldName = entry.getKey();
        Query query = matchQuery.parse(matchQueryType, fieldName, queryString);
        Object boost = entry.getValue();
        if (boost instanceof Number number) {
            return new BoostQuery(query, number.floatValue());
        }
        return query;
    }

    private static Query multiMatch(LuceneQueryBuilder.Context context,
                                    @Nullable String matchType,
                                    Map<String, Float> fieldNames,
                                    String queryString,
                                    Map<String, Object> options) {
        MultiMatchQueryType type = getType(matchType);
        ParsedOptions parsedOptions = OptionParser.parse(type, options);
        MultiMatchQuery multiMatchQuery = new MultiMatchQuery(context, parsedOptions);
        return multiMatchQuery.parse(type, fieldNames, queryString, parsedOptions.minimumShouldMatch());
    }

    private static MultiMatchQueryType getType(@Nullable String matchType) {
        if (matchType == null) {
            return MultiMatchQueryType.BEST_FIELDS;
        }
        return MultiMatchQueryType.parse(matchType);
    }
}

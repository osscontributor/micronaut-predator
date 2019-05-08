package io.micronaut.data.model.query.encoder;

import io.micronaut.core.annotation.Internal;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.model.Association;
import io.micronaut.data.model.PersistentEntity;
import io.micronaut.data.model.PersistentProperty;
import io.micronaut.data.model.query.AssociationQuery;
import io.micronaut.data.model.query.Query;
import io.micronaut.data.model.query.QueryParameter;
import io.micronaut.data.model.query.Sort;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds JPA 1.0 String-based queries from the Query model.
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@Internal
public class JpaQueryEncoder implements QueryEncoder {
    private static final String DISTINCT_CLAUSE = "DISTINCT ";
    private static final String SELECT_CLAUSE = "SELECT ";
    private static final String AS_CLAUSE = " AS ";
    private static final String FROM_CLAUSE = " FROM ";
    private static final String ORDER_BY_CLAUSE = " ORDER BY ";
    private static final String WHERE_CLAUSE = " WHERE ";
    private static final char COMMA = ',';
    private static final char CLOSE_BRACKET = ')';
    private static final char OPEN_BRACKET = '(';
    private static final char SPACE = ' ';
    private static final char QUESTIONMARK = '?';
    private static final char DOT = '.';
    private static final String NOT_CLAUSE = " NOT";
    private static final String LOGICAL_AND = " AND ";
    private static final String UPDATE_CLAUSE = "UPDATE ";
    private static final String DELETE_CLAUSE = "DELETE ";
    private static final String LOGICAL_OR = " OR ";
    private static final Map<Class, QueryHandler> queryHandlers = new HashMap<>();
    private static final String PARAMETER_NAME_PREFIX = "p";
    private static final String PARAMETER_PREFIX = ":p";


    @Nonnull
    @Override
    public EncodedQuery encodeQuery(@Nonnull Query query) {
        QueryState queryState = new QueryState(query.getPersistentEntity(), true);
        queryState.query.append(SELECT_CLAUSE);

        buildSelectClause(query, queryState);
        Query.Junction criteria = query.getCriteria();

        Map<String, String> parameters = null;
        if (!criteria.isEmpty()) {
            parameters = buildWhereClause(criteria, queryState);
        }

        appendOrder(query, queryState);
        return EncodedQuery.of(queryState.query.toString(), parameters);
    }

    @Nonnull
    @Override
    public EncodedQuery encodeUpdate(@Nonnull Query query, List<String> propertiesToUpdate) {
        if (propertiesToUpdate.isEmpty()) {
            throw new IllegalArgumentException("No properties specified to update");
        }
        PersistentEntity entity = query.getPersistentEntity();
        QueryState queryState = new QueryState(entity, false);
        queryState.query.append(UPDATE_CLAUSE)
                .append(entity.getName())
                .append(SPACE)
                .append(queryState.logicalName);
        buildUpdateStatement(queryState, propertiesToUpdate);
        buildWhereClause(query.getCriteria(), queryState);
        return EncodedQuery.of(queryState.query.toString(), queryState.parameters);
    }

    @Nonnull
    @Override
    public EncodedQuery encodeDelete(@Nonnull Query query) {
        PersistentEntity entity = query.getPersistentEntity();
        QueryState queryState = new QueryState(entity, false);
        queryState.query.append(DELETE_CLAUSE).append(entity.getName()).append(SPACE).append(queryState.logicalName);
        buildWhereClause(query.getCriteria(), queryState);
        return EncodedQuery.of(queryState.query.toString(), queryState.parameters);
    }

    private void buildSelectClause(Query query, QueryState queryState) {
        String logicalName = queryState.logicalName;
        PersistentEntity entity = queryState.entity;
        StringBuilder queryString = queryState.query;
        buildSelect(queryString, query.getProjections(), logicalName, entity);

        queryString.append(FROM_CLAUSE)
                .append(entity.getName())
                .append(AS_CLAUSE )
                .append(logicalName);
    }

    private static void buildSelect(StringBuilder queryString, List<Query.Projection> projectionList, String logicalName, PersistentEntity entity) {
        if (projectionList.isEmpty()) {
            queryString.append(logicalName);
        }
        else {
            for (Iterator i = projectionList.iterator(); i.hasNext();) {
                Query.Projection projection = (Query.Projection) i.next();
                if (projection instanceof Query.CountProjection) {
                    queryString.append("COUNT(")
                            .append(logicalName)
                            .append(CLOSE_BRACKET);
                } else if(projection instanceof Query.DistinctProjection) {
                    queryString.append("DISTINCT(")
                            .append(logicalName)
                            .append(CLOSE_BRACKET);
                } else if (projection instanceof Query.IdProjection) {
                    queryString.append(logicalName)
                            .append(DOT)
                            .append(entity.getIdentity().getName());
                }
                else if (projection instanceof Query.PropertyProjection) {
                    Query.PropertyProjection pp = (Query.PropertyProjection) projection;
                    if (projection instanceof Query.AvgProjection) {
                        queryString.append("AVG(")
                                .append(logicalName)
                                .append(DOT)
                                .append(pp.getPropertyName())
                                .append(CLOSE_BRACKET);
                    } else if(projection instanceof Query.DistinctPropertyProjection) {
                        queryString.append("DISTINCT(")
                                .append(logicalName)
                                .append(DOT)
                                .append(pp.getPropertyName())
                                .append(CLOSE_BRACKET);
                    } else if (projection instanceof Query.SumProjection) {
                        queryString.append("SUM(")
                                .append(logicalName)
                                .append(DOT)
                                .append(pp.getPropertyName())
                                .append(CLOSE_BRACKET);
                    }
                    else if (projection instanceof Query.MinProjection) {
                        queryString.append("MIN(")
                                .append(logicalName)
                                .append(DOT)
                                .append(pp.getPropertyName())
                                .append(CLOSE_BRACKET);
                    }
                    else if (projection instanceof Query.MaxProjection) {
                        queryString.append("MAX(")
                                .append(logicalName)
                                .append(DOT)
                                .append(pp.getPropertyName())
                                .append(CLOSE_BRACKET);
                    }
                    else if (projection instanceof Query.CountDistinctProjection) {
                        queryString.append("COUNT(DISTINCT ")
                                .append(logicalName)
                                .append(DOT)
                                .append(pp.getPropertyName())
                                .append(CLOSE_BRACKET);
                    }
                    else {
                        queryString.append(logicalName)
                                .append(DOT)
                                .append(pp.getPropertyName());
                    }
                }

                if (i.hasNext()) {
                    queryString.append(COMMA);
                }
            }
        }
    }

    private static void appendCriteriaForOperator(QueryState queryState,
                                                  final String name,
                                                  Object value,
                                                  String operator) {
        String parameterName = newParameter(queryState.position);
        queryState.whereClause.append(queryState.logicalName)
                            .append(DOT)
                            .append(name)
                            .append(operator)
                            .append(':')
                            .append(parameterName);
        if (value instanceof QueryParameter) {
            queryState.parameters.put(parameterName, ((QueryParameter) value).getName());
        }
    }

    private static String newParameter(AtomicInteger position) {
        return "p" + position.incrementAndGet();
    }

    static {

        queryHandlers.put(AssociationQuery.class, (queryState, criterion) -> {

            if (!queryState.allowJoins) {
                throw new IllegalArgumentException("Joins cannot be used in a DELETE or UPDATE operation");
            }
            AssociationQuery aq = (AssociationQuery) criterion;
            final Association association = aq.getAssociation();
            Query.Junction associationCriteria = aq.getCriteria();
            List<Query.Criterion> associationCriteriaList = associationCriteria.getCriteria();

            handleAssociationCriteria(
                    queryState, association, associationCriteria, associationCriteriaList
            );
        });

        queryHandlers.put(Query.Negation.class, (queryState, criterion) -> {

            queryState.whereClause.append(NOT_CLAUSE)
                                  .append(OPEN_BRACKET);

            final Query.Negation negation = (Query.Negation)criterion;
            buildWhereClauseForCriterion(
                    queryState,
                    negation,
                    negation.getCriteria()
            );
            queryState.whereClause.append(CLOSE_BRACKET);
        });

        queryHandlers.put(Query.Conjunction.class, (queryState, criterion) -> {
            queryState.whereClause.append(OPEN_BRACKET);

            final Query.Conjunction conjunction = (Query.Conjunction)criterion;
            buildWhereClauseForCriterion(queryState, conjunction, conjunction.getCriteria());
            queryState.whereClause.append(CLOSE_BRACKET);
        });

        queryHandlers.put(Query.Disjunction.class, (queryState, criterion) -> {
            queryState.whereClause.append(OPEN_BRACKET);

            final Query.Disjunction disjunction = (Query.Disjunction)criterion;
            buildWhereClauseForCriterion(queryState, disjunction, disjunction.getCriteria());
            queryState.whereClause.append(CLOSE_BRACKET);
        });

        queryHandlers.put(Query.Equals.class, (queryState, criterion) -> {
            Query.Equals eq = (Query.Equals) criterion;
            final String name = eq.getProperty();
            validateProperty(queryState.entity, name, Query.Equals.class);
            appendCriteriaForOperator(
                    queryState,
                    name,
                    eq.getValue(),
                    " = "
            );


        });

        queryHandlers.put(Query.EqualsProperty.class, (queryState, criterion) -> {
            final PersistentEntity entity = queryState.entity;
            Query.EqualsProperty eq = (Query.EqualsProperty) criterion;
            final String propertyName = eq.getProperty();
            String otherProperty = eq.getOtherProperty();

            validateProperty(entity, propertyName, Query.EqualsProperty.class);
            validateProperty(entity, otherProperty, Query.EqualsProperty.class);
            appendPropertyComparison(queryState.whereClause, queryState.logicalName, propertyName, otherProperty, "=");
        });

        queryHandlers.put(Query.NotEqualsProperty.class, (queryState, criterion) -> {
            final PersistentEntity entity = queryState.entity;
            Query.PropertyComparisonCriterion eq = (Query.PropertyComparisonCriterion) criterion;
            final String propertyName = eq.getProperty();
            String otherProperty = eq.getOtherProperty();

            validateProperty(entity, propertyName, Query.NotEqualsProperty.class);
            validateProperty(entity, otherProperty, Query.NotEqualsProperty.class);
            appendPropertyComparison(queryState.whereClause, queryState.logicalName, propertyName, otherProperty, "!=");
        });

        queryHandlers.put(Query.GreaterThanProperty.class, (queryState, criterion) -> {
            PersistentEntity entity = queryState.entity;
            Query.PropertyComparisonCriterion eq = (Query.PropertyComparisonCriterion) criterion;
            final String propertyName = eq.getProperty();
            String otherProperty = eq.getOtherProperty();

            validateProperty(entity, propertyName, Query.GreaterThanProperty.class);
            validateProperty(entity, otherProperty, Query.GreaterThanProperty.class);
            appendPropertyComparison(queryState.whereClause, queryState.logicalName, propertyName, otherProperty, ">");
        });

        queryHandlers.put(Query.GreaterThanEqualsProperty.class, (queryState, criterion) -> {
            PersistentEntity entity = queryState.entity;
            Query.PropertyComparisonCriterion eq = (Query.PropertyComparisonCriterion) criterion;
            final String propertyName = eq.getProperty();
            String otherProperty = eq.getOtherProperty();

            validateProperty(entity, propertyName, Query.GreaterThanEqualsProperty.class);
            validateProperty(entity, otherProperty, Query.GreaterThanEqualsProperty.class);
            appendPropertyComparison(queryState.whereClause, queryState.logicalName, propertyName, otherProperty, ">=");
        });

        queryHandlers.put(Query.LessThanProperty.class, (queryState, criterion) -> {
            PersistentEntity entity = queryState.entity;
            Query.PropertyComparisonCriterion eq = (Query.PropertyComparisonCriterion) criterion;
            final String propertyName = eq.getProperty();
            String otherProperty = eq.getOtherProperty();

            validateProperty(entity, propertyName, Query.LessThanProperty.class);
            validateProperty(entity, otherProperty, Query.LessThanProperty.class);
            appendPropertyComparison(queryState.whereClause, queryState.logicalName, propertyName, otherProperty, "<");
        });

        queryHandlers.put(Query.LessThanEqualsProperty.class, (queryState, criterion) -> {
            PersistentEntity entity = queryState.entity;
            Query.PropertyComparisonCriterion eq = (Query.PropertyComparisonCriterion) criterion;
            final String propertyName = eq.getProperty();
            String otherProperty = eq.getOtherProperty();

            validateProperty(entity, propertyName, Query.LessThanEqualsProperty.class);
            validateProperty(entity, otherProperty, Query.LessThanEqualsProperty.class);
            appendPropertyComparison(queryState.whereClause, queryState.logicalName, propertyName, otherProperty, "<=");
        });

        queryHandlers.put(Query.IsNull.class, (queryState, criterion) -> {
            Query.IsNull isNull = (Query.IsNull) criterion;
            final String name = isNull.getProperty();
            validateProperty(queryState.entity, name, Query.IsNull.class);
            queryState.whereClause.append(queryState.logicalName)
                    .append(DOT)
                    .append(name)
                    .append(" IS NULL ");
        });

        queryHandlers.put(Query.IsNotNull.class, (queryState, criterion) -> {
            Query.IsNotNull isNotNull = (Query.IsNotNull) criterion;
            final String name = isNotNull.getProperty();
            validateProperty(queryState.entity, name, Query.IsNotNull.class);
            queryState.whereClause.append(queryState.logicalName)
                    .append(DOT)
                    .append(name)
                    .append(" IS NOT NULL ");
        });

        queryHandlers.put(Query.IsEmpty.class, (queryState, criterion) -> {
            Query.IsEmpty isEmpty = (Query.IsEmpty) criterion;
            final String name = isEmpty.getProperty();
            validateProperty(queryState.entity, name, Query.IsEmpty.class);
            queryState.whereClause.append(queryState.logicalName)
                    .append(DOT)
                    .append(name)
                    .append(" IS EMPTY ");
        });

        queryHandlers.put(Query.IsNotEmpty.class, (queryState, criterion) -> {
            Query.IsNotEmpty isNotEmpty = (Query.IsNotEmpty) criterion;
            final String name = isNotEmpty.getProperty();
            validateProperty(queryState.entity, name, Query.IsNotEmpty.class);
            queryState.whereClause.append(queryState.logicalName)
                    .append(DOT)
                    .append(name)
                    .append(" IS NOT EMPTY ");
        });

        queryHandlers.put(Query.IdEquals.class, (queryState, criterion) -> {
            PersistentProperty prop = queryState.entity.getIdentity();
            if (prop == null) {
                throw new IllegalStateException("No id found for name entity: " + queryState.entity.getIdentity());
            }
            appendCriteriaForOperator(
                    queryState,
                    prop.getName(),
                    ((Query.IdEquals) criterion).getValue(),
                    " = "
            );
        });

        queryHandlers.put(Query.NotEquals.class, (queryState, criterion) -> {
            Query.NotEquals eq = (Query.NotEquals) criterion;
            final String name = eq.getProperty();
            PersistentProperty prop = validateProperty(queryState.entity, name, Query.NotEquals.class);
            appendCriteriaForOperator(
                    queryState,prop.getName(), eq.getValue(), " != "
            );
        });

        queryHandlers.put(Query.GreaterThan.class, (queryState, criterion) -> {
            Query.GreaterThan eq = (Query.GreaterThan) criterion;
            final String name = eq.getProperty();
            PersistentProperty prop = validateProperty(queryState.entity, name, Query.GreaterThan.class);
            appendCriteriaForOperator(
                    queryState,prop.getName(), eq.getValue(), " > "
            );
        });

        queryHandlers.put(Query.LessThanEquals.class, (queryState, criterion) -> {
            Query.LessThanEquals eq = (Query.LessThanEquals) criterion;
            final String name = eq.getProperty();
            PersistentProperty prop = validateProperty(queryState.entity, name, Query.LessThanEquals.class);
            appendCriteriaForOperator(
                    queryState,prop.getName(), eq.getValue(), " <= "
            );
        });

        queryHandlers.put(Query.GreaterThanEquals.class, (queryState, criterion) -> {
            Query.GreaterThanEquals eq = (Query.GreaterThanEquals) criterion;
            final String name = eq.getProperty();
            PersistentProperty prop = validateProperty(queryState.entity, name, Query.GreaterThanEquals.class);
            appendCriteriaForOperator(
                    queryState,prop.getName(), eq.getValue(), " >= "
            );
        });

        queryHandlers.put(Query.Between.class, (queryState, criterion) -> {
            Query.Between between = (Query.Between) criterion;
            final String name = between.getProperty();
            PersistentProperty prop = validateProperty(queryState.entity, name, Query.Between.class);
            final String qualifiedName = queryState.logicalName + DOT + name;
            String fromParam = newParameter(queryState.position);
            String toParam = newParameter(queryState.position);
            queryState.whereClause.append(OPEN_BRACKET)
                    .append(qualifiedName)
                    .append(" >= ")
                    .append(':')
                    .append(fromParam);
            queryState.whereClause.append(" AND ")
                    .append(qualifiedName)
                    .append(" <= ")
                    .append(':')
                    .append(toParam)
                    .append(CLOSE_BRACKET);

            queryState.parameters.put(fromParam, between.getFrom().getName());
            queryState.parameters.put(toParam, between.getTo().getName());
        });

        queryHandlers.put(Query.LessThan.class, (queryState, criterion) -> {
            Query.LessThan eq = (Query.LessThan) criterion;
            final String name = eq.getProperty();
            PersistentProperty prop = validateProperty(queryState.entity, name, Query.LessThan.class);
            appendCriteriaForOperator(
                    queryState,prop.getName(), eq.getValue(), " < "
            );
        });

        queryHandlers.put(Query.Like.class, (queryState, criterion) -> {
            Query.Like eq = (Query.Like) criterion;
            final String name = eq.getProperty();
            PersistentProperty prop = validateProperty(queryState.entity, name, Query.Like.class);
            appendCriteriaForOperator(
                    queryState,prop.getName(), eq.getValue(), " like "
            );
        });

        queryHandlers.put(Query.ILike.class, (queryState, criterion) -> {
            Query.ILike eq = (Query.ILike) criterion;
            final String name = eq.getProperty();
            PersistentProperty prop = validateProperty(queryState.entity, name, Query.ILike.class);
            String parameterName = newParameter(queryState.position);
            queryState.whereClause.append("lower(")
                    .append(queryState.logicalName)
                    .append(DOT)
                    .append(prop.getName())
                    .append(")")
                    .append(" like lower(")
                    .append(':')
                    .append(parameterName)
                    .append(")");
            Object value = eq.getValue();
            if (value instanceof QueryParameter) {
                queryState.parameters.put(parameterName, ((QueryParameter) value).getName());
            }
        });

        queryHandlers.put(Query.In.class, (queryState, criterion) -> {
            Query.In inQuery = (Query.In) criterion;
            final String name = inQuery.getProperty();
            PersistentProperty prop = validateProperty(queryState.entity, name, Query.In.class);
            queryState.whereClause.append(queryState.logicalName)
                    .append(DOT)
                    .append(name)
                    .append(" IN (");
            Query subquery = inQuery.getSubquery();
            if(subquery != null) {
                buildSubQuery(queryState, subquery);
            }
            else {
                String parameterName = newParameter(queryState.position);
                queryState.whereClause.append(':').append(parameterName);
                Object value = inQuery.getValue();
                if (value instanceof QueryParameter) {
                    queryState.parameters.put(parameterName, ((QueryParameter) value).getName());
                }
            }
            queryState.whereClause.append(CLOSE_BRACKET);

        });

        queryHandlers.put(Query.NotIn.class, (queryState, criterion) -> {
            String comparisonExpression = " NOT IN (";
            handleSubQuery(queryState, (Query.SubqueryCriterion) criterion, comparisonExpression);
        });

        queryHandlers.put(Query.EqualsAll.class, (queryState, criterion) -> {
            String comparisonExpression = " = ALL (";
            handleSubQuery(queryState, (Query.SubqueryCriterion) criterion, comparisonExpression);
        });

        queryHandlers.put(Query.NotEqualsAll.class, (queryState, criterion) -> {
            String comparisonExpression = " != ALL (";
            handleSubQuery(queryState, (Query.SubqueryCriterion) criterion, comparisonExpression);
        });

        queryHandlers.put(Query.GreaterThanAll.class, (queryState, criterion) -> {
            String comparisonExpression = " > ALL (";
            handleSubQuery(queryState, (Query.SubqueryCriterion) criterion, comparisonExpression);
        });

        queryHandlers.put(Query.GreaterThanSome.class, (queryState, criterion) -> {
            String comparisonExpression = " > SOME (";
            handleSubQuery(queryState, (Query.SubqueryCriterion) criterion, comparisonExpression);
        });

        queryHandlers.put(Query.GreaterThanEqualsAll.class, (queryState, criterion) -> {
            String comparisonExpression = " >= ALL (";
            handleSubQuery(queryState, (Query.SubqueryCriterion) criterion, comparisonExpression);
        });

        queryHandlers.put(Query.GreaterThanEqualsSome.class, (queryState, criterion) -> {
            String comparisonExpression = " >= SOME (";
            handleSubQuery(queryState, (Query.SubqueryCriterion) criterion, comparisonExpression);
        });

        queryHandlers.put(Query.LessThanAll.class, (queryState, criterion) -> {
            String comparisonExpression = " < ALL (";
            handleSubQuery(queryState, (Query.SubqueryCriterion) criterion, comparisonExpression);
        });

        queryHandlers.put(Query.LessThanSome.class, (queryState, criterion) -> {
            String comparisonExpression = " < SOME (";
            handleSubQuery(queryState, (Query.SubqueryCriterion) criterion, comparisonExpression);
        });

        queryHandlers.put(Query.LessThanEqualsAll.class, (queryState, criterion) -> {
            String comparisonExpression = " <= ALL (";
            handleSubQuery(queryState, (Query.SubqueryCriterion) criterion, comparisonExpression);
        });

        queryHandlers.put(Query.LessThanEqualsSome.class, (queryState, criterion) -> {
            String comparisonExpression = " <= SOME (";
            handleSubQuery(queryState, (Query.SubqueryCriterion) criterion, comparisonExpression);
        });

    }

    private static void handleSubQuery(QueryState queryState, Query.SubqueryCriterion subqueryCriterion, String comparisonExpression) {
        final String name = subqueryCriterion.getProperty();
        validateProperty(queryState.entity, name, Query.In.class);
        Query subquery = subqueryCriterion.getValue();
        queryState.whereClause.append(queryState.logicalName)
                .append(DOT)
                .append(name)
                .append(comparisonExpression);
        buildSubQuery(queryState, subquery);
        queryState.whereClause.append(CLOSE_BRACKET);
    }

    private static void buildSubQuery(QueryState queryState, Query subquery) {
        PersistentEntity associatedEntity = subquery.getPersistentEntity();
        String associatedEntityName = associatedEntity.getName();
        String associatedEntityLogicalName = associatedEntity.getDecapitalizedName() + queryState.position.incrementAndGet();
        queryState.whereClause.append("SELECT ");
        buildSelect(queryState.whereClause, subquery.getProjections(), associatedEntityLogicalName, associatedEntity);
        queryState.whereClause.append(" FROM ")
                .append(associatedEntityName)
                .append(' ')
                .append(associatedEntityLogicalName)
                .append(" WHERE ");
        List<Query.Criterion> criteria = subquery.getCriteria().getCriteria();
        for (Query.Criterion subCriteria : criteria) {
            QueryHandler queryHandler = queryHandlers.get(subCriteria.getClass());
            if (queryHandler != null) {
                queryHandler.handle(
                        queryState,
                        subCriteria
                );
            }
        }
    }

    private static void handleAssociationCriteria(
            QueryState queryState,
            Association association,
            Query.Junction associationCriteria,
            List<Query.Criterion> associationCriteriaList) {
        if (association == null) {
            return;
        }
        String currentName = queryState.logicalName;
        PersistentEntity currentEntity = queryState.entity;
        final PersistentEntity associatedEntity = association.getAssociatedEntity();
        if (association.getKind() == Relation.Kind.ONE_TO_ONE) {
            final String associationName = association.getName();
            try {
                queryState.entity = associatedEntity;
                queryState.logicalName = currentName + DOT + associationName;
                buildWhereClauseForCriterion(
                        queryState,
                        associationCriteria,
                        associationCriteriaList
                );
            } finally {
                queryState.logicalName = currentName;
                queryState.entity = currentEntity;
            }
        }

        final String associationName = association.getName();
        // TODO: Allow customization of join strategy!
        String joinType = " JOIN ";
        queryState.query.append(joinType)
                .append(queryState.logicalName)
                .append(DOT)
                .append(associationName)
                .append(SPACE)
                .append(associationName);

        try {
            queryState.entity = associatedEntity;
            queryState.logicalName = associationName;
            buildWhereClauseForCriterion(
                    queryState,
                    associationCriteria,
                    associationCriteriaList
            );
        } finally {
            queryState.logicalName = currentName;
            queryState.entity = currentEntity;
        }

    }

    private void buildUpdateStatement(
            QueryState queryState,
            List<String> propertiesToUpdate) {
        StringBuilder queryString = queryState.query;
        Map<String, String> parameters = queryState.parameters;
        queryString.append(SPACE).append("SET");

        // keys need to be sorted before query is built

        Iterator<String> iterator = propertiesToUpdate.iterator();
        while (iterator.hasNext()) {
            String propertyName = iterator.next();
            PersistentProperty prop = queryState.entity.getPropertyByName(propertyName);
            if (prop == null) {
                continue;
            }

            queryString.append(SPACE).append(queryState.logicalName).append(DOT).append(propertyName).append('=');
            String param = newParameter(queryState.position);
            queryString.append(':').append(param);
            parameters.put(param, prop.getName());
            if (iterator.hasNext()) {
                queryString.append(COMMA);
            }
        }
    }

    private static void appendPropertyComparison(StringBuilder q, String logicalName, String propertyName, String otherProperty, String operator) {
        q.append(logicalName)
                .append(DOT)
                .append(propertyName)
                .append(operator)
                .append(logicalName)
                .append(DOT)
                .append(otherProperty);
    }

    private static PersistentProperty validateProperty(PersistentEntity entity, String name, Class criterionType) {
        PersistentProperty identity = entity.getIdentity();
        if (identity != null && identity.getName().equals(name)) {
            return identity;
        }
        PersistentProperty[] compositeIdentity = entity.getCompositeIdentity();
        if(compositeIdentity != null) {
            for (PersistentProperty property : compositeIdentity) {
                if(property.getName().equals(name)) {
                    return property;
                }
            }
        }
        PersistentProperty prop = entity.getPropertyByPath(name).orElse(null);
        if (prop == null) {
            throw new IllegalArgumentException("Cannot use [" +
                    criterionType.getSimpleName() + "] criterion on non-existent property: " + name);
        }
        return prop;
    }

    private Map<String, String> buildWhereClause(
            Query.Junction criteria,
            QueryState queryState) {
        if (!criteria.isEmpty()) {

            final List<Query.Criterion> criterionList = criteria.getCriteria();
            StringBuilder whereClause = queryState.whereClause;
            whereClause.append(WHERE_CLAUSE);
            if (criteria instanceof Query.Negation) {
                whereClause.append(NOT_CLAUSE);
            }
            whereClause.append(OPEN_BRACKET);
            buildWhereClauseForCriterion(queryState, criteria, criterionList);
            queryState.query.append(whereClause.toString());
            queryState.query.append(CLOSE_BRACKET);
        }
        return queryState.parameters;
    }

    private void appendOrder(Query query, QueryState queryState) {
        List<Sort.Order> orders = query.getOrderBy();
        if (!orders.isEmpty()) {

            StringBuilder buff = queryState.query;
            buff.append( ORDER_BY_CLAUSE);
            Iterator<Sort.Order> i = orders.iterator();
            while (i.hasNext()) {
                Sort.Order order = i.next();
                buff.append(queryState.logicalName)
                        .append(DOT)
                        .append(order.getProperty())
                        .append(SPACE)
                        .append(order.getDirection().toString());
                if (i.hasNext()) {
                    buff.append(",");
                }
            }
        }
    }

    private static void buildWhereClauseForCriterion(
            final QueryState queryState,
            Query.Junction criteria,
            final List<Query.Criterion> criterionList) {
        for (Iterator<Query.Criterion> iterator = criterionList.iterator(); iterator.hasNext();) {
            Query.Criterion criterion = iterator.next();

            final String operator = criteria instanceof Query.Conjunction ? LOGICAL_AND : LOGICAL_OR;
            QueryHandler qh = queryHandlers.get(criterion.getClass());
            if (qh != null) {

                qh.handle(queryState, criterion);
            }
            else if (criterion instanceof AssociationQuery) {

                if (!queryState.allowJoins) {
                    throw new IllegalArgumentException("Joins cannot be used in a DELETE or UPDATE operation");
                }
                AssociationQuery ac = (AssociationQuery) criterion;
                Association association = ac.getAssociation();
                Query.Junction junction = ac.getCriteria();
                handleAssociationCriteria(
                        queryState,
                        association,
                        junction,
                        junction.getCriteria()
                );
            }
            else {
                throw new IllegalArgumentException("Queries of type " + criterion.getClass().getSimpleName() + " are not supported by this implementation");
            }

            if (iterator.hasNext()) {
                queryState.whereClause.append(operator);
            }
        }

    }

    private interface QueryHandler {
        void handle(QueryState queryState, Query.Criterion criterion);
    }

    private class QueryState {
        final AtomicInteger position = new AtomicInteger(0);
        final Map<String, String> parameters  = new LinkedHashMap<>();
        final StringBuilder query = new StringBuilder();
        final StringBuilder whereClause = new StringBuilder();
        final boolean allowJoins;
        String logicalName;
        PersistentEntity entity;

        QueryState(PersistentEntity entity, boolean allowJoins) {
            this.allowJoins = allowJoins;
            this.entity = entity;
            this.logicalName = entity.getDecapitalizedName();
        }
    }
}

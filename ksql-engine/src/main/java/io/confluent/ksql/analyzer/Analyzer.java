/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.analyzer;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import io.confluent.ksql.analyzer.Analysis.AliasedDataSource;
import io.confluent.ksql.analyzer.Analysis.Into;
import io.confluent.ksql.analyzer.Analysis.JoinInfo;
import io.confluent.ksql.execution.ddl.commands.KsqlTopic;
import io.confluent.ksql.execution.expression.tree.ComparisonExpression;
import io.confluent.ksql.execution.expression.tree.Expression;
import io.confluent.ksql.execution.expression.tree.FunctionCall;
import io.confluent.ksql.execution.expression.tree.QualifiedColumnReferenceExp;
import io.confluent.ksql.execution.expression.tree.TraversalExpressionVisitor;
import io.confluent.ksql.execution.expression.tree.UnqualifiedColumnReferenceExp;
import io.confluent.ksql.execution.plan.SelectExpression;
import io.confluent.ksql.execution.windows.KsqlWindowExpression;
import io.confluent.ksql.metastore.MetaStore;
import io.confluent.ksql.metastore.model.DataSource;
import io.confluent.ksql.model.WindowType;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.name.FunctionName;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.parser.DefaultTraversalVisitor;
import io.confluent.ksql.parser.NodeLocation;
import io.confluent.ksql.parser.tree.AliasedRelation;
import io.confluent.ksql.parser.tree.AllColumns;
import io.confluent.ksql.parser.tree.AstNode;
import io.confluent.ksql.parser.tree.GroupBy;
import io.confluent.ksql.parser.tree.GroupingElement;
import io.confluent.ksql.parser.tree.Join;
import io.confluent.ksql.parser.tree.JoinOn;
import io.confluent.ksql.parser.tree.Query;
import io.confluent.ksql.parser.tree.Select;
import io.confluent.ksql.parser.tree.SelectItem;
import io.confluent.ksql.parser.tree.SingleColumn;
import io.confluent.ksql.parser.tree.Sink;
import io.confluent.ksql.parser.tree.Table;
import io.confluent.ksql.parser.tree.WindowExpression;
import io.confluent.ksql.planner.plan.JoinNode;
import io.confluent.ksql.schema.ksql.Column;
import io.confluent.ksql.schema.ksql.FormatOptions;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.serde.Format;
import io.confluent.ksql.serde.FormatFactory;
import io.confluent.ksql.serde.FormatInfo;
import io.confluent.ksql.serde.KeyFormat;
import io.confluent.ksql.serde.SerdeOption;
import io.confluent.ksql.serde.SerdeOptions;
import io.confluent.ksql.serde.ValueFormat;
import io.confluent.ksql.serde.WindowInfo;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.SchemaUtil;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

// CHECKSTYLE_RULES.OFF: ClassDataAbstractionCoupling
class Analyzer {
  // CHECKSTYLE_RULES.ON: ClassDataAbstractionCoupling

  private static final String KAFKA_VALUE_FORMAT_LIMITATION_DETAILS = ""
      + "The KAFKA format is primarily intended for use as a key format. "
      + "It can be used as a value format, but can not be used in any operation that "
      + "requires a repartition or changelog topic." + System.lineSeparator()
      + "Removing this limitation requires enhancements to the core of KSQL. "
      + "This will come in a future release. Until then, avoid using the KAFKA format for values."
      + System.lineSeparator() + "If you have an existing topic with "
      + "KAFKA formatted values you can duplicate the data and serialize using Avro or JSON with a "
      + "statement such as: "
      + System.lineSeparator()
      + System.lineSeparator()
      + "'CREATE STREAM <new-stream-name> WITH(VALUE_FORMAT='Avro') AS "
      + "SELECT * FROM <existing-kafka-formated-stream-name>;'"
      + System.lineSeparator()
      + "For more info see https://github.com/confluentinc/ksql/issues/3060";

  private final MetaStore metaStore;
  private final String topicPrefix;
  private final SerdeOptionsSupplier serdeOptionsSupplier;
  private final Set<SerdeOption> defaultSerdeOptions;

  /**
   * @param metaStore the metastore to use.
   * @param topicPrefix the prefix to use for topic names where an explicit name is not specified.
   * @param defaultSerdeOptions the default serde options.
   */
  Analyzer(
      final MetaStore metaStore,
      final String topicPrefix,
      final Set<SerdeOption> defaultSerdeOptions
  ) {
    this(
        metaStore,
        topicPrefix,
        defaultSerdeOptions,
        SerdeOptions::buildForCreateAsStatement);
  }

  @VisibleForTesting
  Analyzer(
      final MetaStore metaStore,
      final String topicPrefix,
      final Set<SerdeOption> defaultSerdeOptions,
      final SerdeOptionsSupplier serdeOptionsSupplier
  ) {
    this.metaStore = requireNonNull(metaStore, "metaStore");
    this.topicPrefix = requireNonNull(topicPrefix, "topicPrefix");
    this.defaultSerdeOptions = ImmutableSet
        .copyOf(requireNonNull(defaultSerdeOptions, "defaultSerdeOptions"));
    this.serdeOptionsSupplier = requireNonNull(serdeOptionsSupplier, "serdeOptionsSupplier");
  }

  /**
   * Analyze the query.
   *
   * @param query the query to analyze.
   * @param sink the sink the query will output to.
   * @return the analysis.
   */
  Analysis analyze(
      final Query query,
      final Optional<Sink> sink
  ) {
    final Visitor visitor = new Visitor(query, sink.isPresent());

    visitor.process(query, null);

    sink.ifPresent(visitor::analyzeNonStdOutSink);

    visitor.validate();

    return visitor.analysis;
  }

  // CHECKSTYLE_RULES.OFF: ClassDataAbstractionCoupling
  private final class Visitor extends DefaultTraversalVisitor<AstNode, Void> {
    // CHECKSTYLE_RULES.ON: ClassDataAbstractionCoupling

    private final Analysis analysis;
    private final boolean persistent;
    private final boolean pullQuery;
    private boolean isJoin = false;
    private boolean isGroupBy = false;

    Visitor(final Query query, final boolean persistent) {
      this.pullQuery = query.isPullQuery();
      this.analysis = new Analysis(query.getResultMaterialization());
      this.persistent = persistent;
    }

    private void analyzeNonStdOutSink(final Sink sink) {
      analysis.setProperties(sink.getProperties());

      setSerdeOptions(sink);

      if (!sink.shouldCreateSink()) {
        final DataSource existing = metaStore.getSource(sink.getName());
        if (existing == null) {
          throw new KsqlException("Unknown source: "
              + sink.getName().toString(FormatOptions.noEscape()));
        }

        analysis.setInto(Into.of(
            sink.getName(),
            false,
            existing.getKsqlTopic()
        ));
        return;
      }

      final String topicName = sink.getProperties().getKafkaTopic()
          .orElseGet(() -> topicPrefix + sink.getName().name());

      final KeyFormat keyFormat = buildKeyFormat();
      final Format format = getValueFormat(sink);

      final Map<String, String> sourceProperties = new HashMap<>();
      if (format.name().equals(getSourceInfo().getFormat())) {
        getSourceInfo().getProperties().forEach((k, v) -> {
          if (format.getInheritableProperties().contains(k)) {
            sourceProperties.put(k, v);
          }
        });
      }

      // overwrite any inheritable properties if they were explicitly
      // specified in the statement
      sourceProperties.putAll(sink.getProperties().getFormatProperties());

      final ValueFormat valueFormat = ValueFormat.of(FormatInfo.of(
          format.name(),
          sourceProperties
      ));

      final KsqlTopic intoKsqlTopic = new KsqlTopic(
          topicName,
          keyFormat,
          valueFormat
      );

      analysis.setInto(Into.of(
          sink.getName(),
          true,
          intoKsqlTopic
      ));
    }

    private KeyFormat buildKeyFormat() {
      final Optional<KsqlWindowExpression> ksqlWindow = analysis.getWindowExpression()
          .map(WindowExpression::getKsqlWindowExpression);

      return ksqlWindow
          .map(w -> KeyFormat.windowed(
              FormatInfo.of(FormatFactory.KAFKA.name()), w.getWindowInfo()))
          .orElseGet(() -> analysis
              .getFromDataSources()
              .get(0)
              .getDataSource()
              .getKsqlTopic()
              .getKeyFormat());
    }

    private void setSerdeOptions(final Sink sink) {
      final List<ColumnName> columnNames = getColumnNames();

      final Format valueFormat = getValueFormat(sink);

      final Set<SerdeOption> serdeOptions = serdeOptionsSupplier.build(
          columnNames,
          valueFormat,
          sink.getProperties().getWrapSingleValues(),
          defaultSerdeOptions
      );

      analysis.setSerdeOptions(serdeOptions);
    }

    private List<ColumnName> getColumnNames() {
      return analysis.getSelectExpressions().stream()
          .map(SelectExpression::getAlias)
          .collect(Collectors.toList());
    }

    private Format getValueFormat(final Sink sink) {
      return sink.getProperties().getValueFormat()
          .orElseGet(() -> FormatFactory.of(getSourceInfo()));
    }

    private FormatInfo getSourceInfo() {
      return analysis
          .getFromDataSources()
          .get(0)
          .getDataSource()
          .getKsqlTopic()
          .getValueFormat()
          .getFormatInfo();
    }


    @Override
    protected AstNode visitQuery(
        final Query node,
        final Void context
    ) {
      process(node.getFrom(), context);

      process(node.getSelect(), context);

      node.getWhere().ifPresent(this::analyzeWhere);
      node.getGroupBy().ifPresent(this::analyzeGroupBy);
      node.getPartitionBy().ifPresent(this::analyzePartitionBy);
      node.getWindow().ifPresent(this::analyzeWindowExpression);
      node.getHaving().ifPresent(this::analyzeHaving);
      node.getLimit().ifPresent(analysis::setLimitClause);

      throwOnUnknownColumnReference();

      return null;
    }

    private void throwOnUnknownColumnReference() {

      final ColumnReferenceValidator columnValidator =
          new ColumnReferenceValidator(analysis.getFromSourceSchemas(true));

      analysis.getWhereExpression()
          .ifPresent(columnValidator::analyzeExpression);

      analysis.getGroupByExpressions()
          .forEach(columnValidator::analyzeExpression);

      analysis.getHavingExpression()
          .ifPresent(columnValidator::analyzeExpression);

      analysis.getSelectExpressions().stream()
          .map(SelectExpression::getExpression)
          .forEach(columnValidator::analyzeExpression);
    }

    @Override
    protected AstNode visitJoin(final Join node, final Void context) {
      isJoin = true;

      process(node.getLeft(), context);
      process(node.getRight(), context);

      final JoinNode.JoinType joinType = getJoinType(node);

      final AliasedDataSource left = analysis.getFromDataSources().get(0);
      final AliasedDataSource right = analysis.getFromDataSources().get(1);

      final JoinOn joinOn = (JoinOn) node.getCriteria();
      final ComparisonExpression comparisonExpression = (ComparisonExpression) joinOn
          .getExpression();

      if (comparisonExpression.getType() != ComparisonExpression.Type.EQUAL) {
        throw new KsqlException("Only equality join criteria is supported.");
      }

      final ColumnReferenceValidator columnValidator =
          new ColumnReferenceValidator(analysis.getFromSourceSchemas(false));

      final Set<SourceName> srcsUsedInLeft = columnValidator
          .analyzeExpression(comparisonExpression.getLeft());

      final Set<SourceName> srcsUsedInRight = columnValidator
          .analyzeExpression(comparisonExpression.getRight());

      final SourceName leftSourceName = getOnlySourceForJoin(
          comparisonExpression.getLeft(), comparisonExpression, srcsUsedInLeft);
      final SourceName rightSourceName = getOnlySourceForJoin(
          comparisonExpression.getRight(), comparisonExpression, srcsUsedInRight);

      throwOnSelfJoin(left, right);
      throwOnIncompleteJoinCriteria(left, right, leftSourceName, rightSourceName);
      throwOnIncompatibleSourceWindowing(left, right);

      final boolean flipped = leftSourceName.equals(right.getAlias());
      analysis.setJoin(new JoinInfo(
          flipped ? comparisonExpression.getRight() : comparisonExpression.getLeft(),
          flipped ? comparisonExpression.getLeft() : comparisonExpression.getRight(),
          joinType,
          node.getWithinExpression()
      ));

      return null;
    }

    private void throwOnSelfJoin(final AliasedDataSource left, final AliasedDataSource right) {
      if (left.getDataSource().getName().equals(right.getDataSource().getName())) {
        throw new KsqlException(
            "Can not join '" + left.getDataSource().getName().toString(FormatOptions.noEscape())
                + "' to '" + right.getDataSource().getName().toString(FormatOptions.noEscape())
                + "': self joins are not yet supported."
        );
      }
    }

    private void throwOnIncompleteJoinCriteria(
        final AliasedDataSource left,
        final AliasedDataSource right,
        final SourceName leftExpressionSource,
        final SourceName rightExpressionSource
    ) {
      final boolean valid = ImmutableSet.of(leftExpressionSource, rightExpressionSource)
          .containsAll(ImmutableList.of(left.getAlias(), right.getAlias()));

      if (!valid) {
        throw new KsqlException(
            "Each side of the join must reference exactly one source and not the same source. "
                + "Left side references " + leftExpressionSource
                + " and right references " + rightExpressionSource
        );
      }
    }

    private void throwOnIncompatibleSourceWindowing(
        final AliasedDataSource left,
        final AliasedDataSource right
    ) {
      final Optional<WindowType> leftWindowType = left.getDataSource()
          .getKsqlTopic()
          .getKeyFormat()
          .getWindowInfo()
          .map(WindowInfo::getType);

      final Optional<WindowType> rightWindowType = right.getDataSource()
          .getKsqlTopic()
          .getKeyFormat()
          .getWindowInfo()
          .map(WindowInfo::getType);

      if (leftWindowType.isPresent() != rightWindowType.isPresent()) {
        throw windowedNonWindowedJoinException(left, right, leftWindowType, rightWindowType);
      }

      if (!leftWindowType.isPresent()) {
        return;
      }

      final WindowType leftWt = leftWindowType.get();
      final WindowType rightWt = rightWindowType.get();
      final boolean compatible = leftWt == WindowType.SESSION
          ? rightWt == WindowType.SESSION
          : rightWt == WindowType.HOPPING || rightWt == WindowType.TUMBLING;

      if (!compatible) {
        throw new KsqlException("Incompatible windowed sources."
            + System.lineSeparator()
            + "Left source: " + leftWt
            + System.lineSeparator()
            + "Right source: " + rightWt
            + System.lineSeparator()
            + "Session windowed sources can only be joined to other session windowed sources, "
            + "and may still not result in expected behaviour as session bounds must be an exact "
            + "match for the join to work"
            + System.lineSeparator()
            + "Hopping and tumbling windowed sources can only be joined to other hopping and "
            + "tumbling windowed sources"
        );
      }
    }

    private KsqlException windowedNonWindowedJoinException(
        final AliasedDataSource left,
        final AliasedDataSource right,
        final Optional<WindowType> leftWindowType,
        final Optional<WindowType> rightWindowType
    ) {
      final String leftMsg = leftWindowType.map(Object::toString).orElse("not");
      final String rightMsg = rightWindowType.map(Object::toString).orElse("not");
      return new KsqlException("Can not join windowed source to non-windowed source."
          + System.lineSeparator()
          + left.getAlias() + " is " + leftMsg + " windowed"
          + System.lineSeparator()
          + right.getAlias() + " is " + rightMsg + " windowed"
      );
    }

    private SourceName getOnlySourceForJoin(
        final Expression exp,
        final ComparisonExpression join,
        final Set<SourceName> sources
    ) {
      try {
        return Iterables.getOnlyElement(sources);
      } catch (final Exception e) {
        throw new KsqlException("Invalid comparison expression '" + exp + "' in join '" + join
            + "'. Each side of the join comparision must contain references from exactly one "
            + "source.");
      }
    }

    private JoinNode.JoinType getJoinType(final Join node) {
      final JoinNode.JoinType joinType;
      switch (node.getType()) {
        case INNER:
          joinType = JoinNode.JoinType.INNER;
          break;
        case LEFT:
          joinType = JoinNode.JoinType.LEFT;
          break;
        case OUTER:
          joinType = JoinNode.JoinType.OUTER;
          break;
        default:
          throw new KsqlException("Join type is not supported: " + node.getType().name());
      }
      return joinType;
    }

    @Override
    protected AstNode visitAliasedRelation(final AliasedRelation node, final Void context) {
      final SourceName structuredDataSourceName = ((Table) node.getRelation()).getName();

      final DataSource source = metaStore.getSource(structuredDataSourceName);
      if (source == null) {
        throw new KsqlException(structuredDataSourceName + " does not exist.");
      }

      analysis.addDataSource(node.getAlias(), source);
      return node;
    }

    @Override
    protected AstNode visitSelect(final Select node, final Void context) {
      for (final SelectItem selectItem : node.getSelectItems()) {
        if (selectItem instanceof AllColumns) {
          visitSelectStar((AllColumns) selectItem);
        } else if (selectItem instanceof SingleColumn) {
          final SingleColumn column = (SingleColumn) selectItem;
          addSelectItem(column.getExpression(), column.getAlias().get());
          visitTableFunctions(column.getExpression());
        } else {
          throw new IllegalArgumentException(
              "Unsupported SelectItem type: " + selectItem.getClass().getName());
        }
      }
      return null;
    }

    @Override
    protected AstNode visitGroupBy(final GroupBy node, final Void context) {
      return null;
    }

    private void analyzeWhere(final Expression node) {
      analysis.setWhereExpression(node);
    }

    private void analyzeGroupBy(final GroupBy groupBy) {
      isGroupBy = true;

      for (final GroupingElement groupingElement : groupBy.getGroupingElements()) {
        final Set<Expression> groupingSet = groupingElement.enumerateGroupingSets().get(0);
        analysis.addGroupByExpressions(groupingSet);
      }
    }

    private void analyzePartitionBy(final Expression partitionBy) {
      analysis.setPartitionBy(partitionBy);
    }

    private void analyzeWindowExpression(final WindowExpression windowExpression) {
      analysis.setWindowExpression(windowExpression);
    }

    private void analyzeHaving(final Expression node) {
      analysis.setHavingExpression(node);
    }

    private void visitSelectStar(final AllColumns allColumns) {

      final Optional<NodeLocation> location = allColumns.getLocation();

      final Optional<SourceName> prefix = allColumns.getSource();

      for (final AliasedDataSource source : analysis.getFromDataSources()) {

        if (prefix.isPresent() && !prefix.get().equals(source.getAlias())) {
          continue;
        }

        final String aliasPrefix = analysis.isJoin()
            ? source.getAlias().name() + "_"
            : "";

        final LogicalSchema schema = source.getDataSource().getSchema();
        final boolean windowed = source.getDataSource().getKsqlTopic().getKeyFormat().isWindowed();

        // Non-join persistent queries only require value columns on SELECT *
        // where as joins and transient queries require all columns in the select:
        // See https://github.com/confluentinc/ksql/issues/3731 for more info
        final List<Column> valueColumns = persistent && !analysis.isJoin()
            ? schema.value()
            : systemColumnsToTheFront(schema.withMetaAndKeyColsInValue(windowed).value());

        for (final Column column : valueColumns) {

          if (pullQuery && schema.isMetaColumn(column.name())) {
            continue;
          }

          final QualifiedColumnReferenceExp selectItem = new QualifiedColumnReferenceExp(
              location,
              source.getAlias(),
              column.name());

          final String alias = aliasPrefix + column.name().name();

          addSelectItem(selectItem, ColumnName.of(alias));
        }
      }
    }

    private List<Column> systemColumnsToTheFront(final List<Column> columns) {
      // When doing a `select *` the system columns should be at the front of the column list
      // but are added at the back during processing for performance reasons.
      // Switch them around here:
      final Map<Boolean, List<Column>> partitioned = columns.stream()
          .collect(Collectors.groupingBy(c -> SchemaUtil.isSystemColumn(c.name())));

      final List<Column> all = partitioned.get(true);
      all.addAll(partitioned.get(false));
      return all;
    }

    public void validate() {
      final String kafkaSources = analysis.getFromDataSources().stream()
          .filter(s -> s.getDataSource().getKsqlTopic().getValueFormat().getFormat()
              == FormatFactory.KAFKA)
          .map(AliasedDataSource::getAlias)
          .map(SourceName::name)
          .collect(Collectors.joining(", "));

      if (kafkaSources.isEmpty()) {
        return;
      }

      if (isJoin) {
        throw new KsqlException("Source(s) " + kafkaSources + " are using the 'KAFKA' value format."
            + " This format does not yet support JOIN."
            + System.lineSeparator() + KAFKA_VALUE_FORMAT_LIMITATION_DETAILS);
      }

      if (isGroupBy) {
        throw new KsqlException("Source(s) " + kafkaSources + " are using the 'KAFKA' value format."
            + " This format does not yet support GROUP BY."
            + System.lineSeparator() + KAFKA_VALUE_FORMAT_LIMITATION_DETAILS);
      }
    }

    private void addSelectItem(final Expression exp, final ColumnName columnName) {
      if (persistent) {
        if (SchemaUtil.isSystemColumn(columnName)) {
          throw new KsqlException("Reserved column name in select: " + columnName + ". "
              + "Please remove or alias the column.");
        }
      }

      final Set<ColumnName> columnNames = new HashSet<>();
      final TraversalExpressionVisitor<Void> visitor = new TraversalExpressionVisitor<Void>() {
        @Override
        public Void visitColumnReference(
            final UnqualifiedColumnReferenceExp node,
            final Void context
        ) {
          columnNames.add(node.getReference());
          return null;
        }

        @Override
        public Void visitQualifiedColumnReference(
            final QualifiedColumnReferenceExp node,
            final Void context
        ) {
          columnNames.add(node.getReference());
          return null;
        }
      };

      visitor.process(exp, null);

      analysis.addSelectItem(exp, columnName);
      analysis.addSelectColumnRefs(columnNames);
    }

    private void visitTableFunctions(final Expression expression) {
      final TableFunctionVisitor visitor = new TableFunctionVisitor();
      visitor.process(expression, null);
    }

    private final class TableFunctionVisitor extends TraversalExpressionVisitor<Void> {

      private Optional<FunctionName> tableFunctionName = Optional.empty();

      @Override
      public Void visitFunctionCall(final FunctionCall functionCall, final Void context) {
        final FunctionName functionName = functionCall.getName();
        final boolean isTableFunction = metaStore.isTableFunction(functionName);

        if (isTableFunction) {
          if (tableFunctionName.isPresent()) {
            throw new KsqlException("Table functions cannot be nested: "
                + tableFunctionName.get() + "(" + functionName + "())");
          }

          tableFunctionName = Optional.of(functionName);

          analysis.addTableFunction(functionCall);
        }

        super.visitFunctionCall(functionCall, context);

        if (isTableFunction) {
          tableFunctionName = Optional.empty();
        }

        return null;
      }
    }
  }

  @FunctionalInterface
  interface SerdeOptionsSupplier {

    Set<SerdeOption> build(
        List<ColumnName> valueColumnNames,
        Format valueFormat,
        Optional<Boolean> wrapSingleValues,
        Set<SerdeOption> singleFieldDefaults
    );
  }
}

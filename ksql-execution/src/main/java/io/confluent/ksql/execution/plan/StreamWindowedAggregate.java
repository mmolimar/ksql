/*
 * Copyright 2019 Confluent Inc.
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

package io.confluent.ksql.execution.plan;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import io.confluent.ksql.execution.expression.tree.FunctionCall;
import io.confluent.ksql.execution.windows.KsqlWindowExpression;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.streams.kstream.Windowed;

@Immutable
public class StreamWindowedAggregate
    implements ExecutionStep<KTableHolder<Windowed<Struct>>> {
  private final ExecutionStepProperties properties;
  private final ExecutionStep<KGroupedStreamHolder> source;
  private final Formats formats;
  private final int nonFuncColumnCount;
  private final ImmutableList<FunctionCall> aggregations;
  private final KsqlWindowExpression windowExpression;

  public StreamWindowedAggregate(
      @JsonProperty(value = "properties", required = true) ExecutionStepProperties properties,
      @JsonProperty(value = "source", required = true)
      ExecutionStep<KGroupedStreamHolder> source,
      @JsonProperty(value = "formats", required = true) Formats formats,
      @JsonProperty(value = "nonFuncColumnCount", required = true) int nonFuncColumnCount,
      @JsonProperty(value = "aggregations", required = true) List<FunctionCall> aggregations,
      @JsonProperty(value = "windowExpression", required = true)
      KsqlWindowExpression windowExpression) {
    this.properties = requireNonNull(properties, "properties");
    this.source = requireNonNull(source, "source");
    this.formats = requireNonNull(formats, "formats");
    this.nonFuncColumnCount = nonFuncColumnCount;
    this.aggregations = ImmutableList.copyOf(requireNonNull(aggregations, "aggregations"));
    this.windowExpression = requireNonNull(windowExpression, "windowExpression");
  }

  @Override
  public ExecutionStepProperties getProperties() {
    return properties;
  }

  @Override
  @JsonIgnore
  public List<ExecutionStep<?>> getSources() {
    return Collections.singletonList(source);
  }

  public int getNonFuncColumnCount() {
    return nonFuncColumnCount;
  }

  public List<FunctionCall> getAggregations() {
    return aggregations;
  }

  public Formats getFormats() {
    return formats;
  }

  public KsqlWindowExpression getWindowExpression() {
    return windowExpression;
  }

  public ExecutionStep<KGroupedStreamHolder> getSource() {
    return source;
  }

  @Override
  public KTableHolder<Windowed<Struct>> build(PlanBuilder builder) {
    return builder.visitStreamWindowedAggregate(this);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    StreamWindowedAggregate that = (StreamWindowedAggregate) o;
    return Objects.equals(properties, that.properties)
        && Objects.equals(source, that.source)
        && Objects.equals(formats, that.formats)
        && Objects.equals(aggregations, that.aggregations)
        && nonFuncColumnCount == that.nonFuncColumnCount
        && Objects.equals(windowExpression, that.windowExpression);
  }

  @Override
  public int hashCode() {

    return Objects.hash(
        properties,
        source,
        formats,
        aggregations,
        nonFuncColumnCount,
        windowExpression
    );
  }
}

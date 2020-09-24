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

package io.confluent.ksql.test.tools;

import static com.google.common.io.Files.getNameWithoutExtension;

import com.google.common.collect.Streams;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.ksql.config.SessionConfig;
import io.confluent.ksql.format.DefaultFormatInjector;
import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.metastore.MetaStore;
import io.confluent.ksql.metastore.MetaStoreImpl;
import io.confluent.ksql.metastore.MutableMetaStore;
import io.confluent.ksql.parser.DefaultKsqlParser;
import io.confluent.ksql.parser.KsqlParser;
import io.confluent.ksql.parser.KsqlParser.ParsedStatement;
import io.confluent.ksql.parser.KsqlParser.PreparedStatement;
import io.confluent.ksql.parser.SqlBaseParser;
import io.confluent.ksql.parser.properties.with.CreateSourceProperties;
import io.confluent.ksql.parser.properties.with.SourcePropertiesUtil;
import io.confluent.ksql.parser.tree.CreateSource;
import io.confluent.ksql.parser.tree.RegisterType;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.PersistenceSchema;
import io.confluent.ksql.serde.Format;
import io.confluent.ksql.serde.FormatFactory;
import io.confluent.ksql.serde.FormatInfo;
import io.confluent.ksql.serde.SerdeOptions;
import io.confluent.ksql.serde.SerdeOptionsFactory;
import io.confluent.ksql.statement.ConfiguredStatement;
import io.confluent.ksql.util.KsqlConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Util class for common code used by test case builders
 */
@SuppressWarnings("UnstableApiUsage")
public final class TestCaseBuilderUtil {

  private static final String FORMAT_REPLACE_ERROR =
      "To use {FORMAT} in your statements please set the 'format' test case element";

  private TestCaseBuilderUtil() {
  }

  public static String buildTestName(
      final Path originalFileName,
      final String testName,
      final Optional<String> explicitFormat
  ) {
    final String prefix = filePrefix(originalFileName.toString());

    final String pf = explicitFormat
        .map(f -> " - " + f)
        .orElse("");

    return prefix + testName + pf;
  }

  public static String extractSimpleTestName(
      final String originalFileName,
      final String testName
  ) {
    final String prefix = filePrefix(originalFileName);

    if (!testName.startsWith(prefix)) {
      throw new IllegalArgumentException("Not prefixed test name: " + testName);
    }

    return testName.substring(prefix.length());
  }

  public static List<String> buildStatements(
      final List<String> statements,
      final Optional<String> explicitFormat
  ) {
    final String format = explicitFormat.orElse(FORMAT_REPLACE_ERROR);

    return statements.stream()
        .map(stmt -> stmt.replace("{FORMAT}", format))
        .collect(Collectors.toList());
  }

  public static Collection<Topic> getAllTopics(
      final Collection<String> statements,
      final Collection<Topic> topics,
      final Collection<Record> outputs,
      final Collection<Record> inputs,
      final FunctionRegistry functionRegistry,
      final KsqlConfig ksqlConfig
  ) {
    final Map<String, Topic> allTopics = new HashMap<>();

    // Add all topics from topic nodes to the map:
    topics.forEach(topic -> allTopics.put(topic.getName(), topic));

    // Infer topics if not added already:
    final MutableMetaStore metaStore = new MetaStoreImpl(functionRegistry);
    for (String sql : statements) {
      final Topic topicFromStatement = createTopicFromStatement(sql, metaStore, ksqlConfig);
      if (topicFromStatement != null) {
        allTopics.putIfAbsent(topicFromStatement.getName(), topicFromStatement);
      }
    }

    // Get topics from inputs and outputs fields:
    Streams.concat(inputs.stream(), outputs.stream())
        .map(record -> new Topic(record.getTopicName(), Optional.empty()))
        .forEach(topic -> allTopics.putIfAbsent(topic.getName(), topic));

    return allTopics.values();
  }

  private static Topic createTopicFromStatement(
      final String sql,
      final MutableMetaStore metaStore,
      final KsqlConfig ksqlConfig
  ) {
    final KsqlParser parser = new DefaultKsqlParser();

    final Function<ConfiguredStatement<?>, Topic> extractTopic = (ConfiguredStatement<?> stmt) -> {
      final CreateSource statement = (CreateSource) stmt.getStatement();
      final CreateSourceProperties props = statement.getProperties();

      final FormatInfo valueFormatInfo = SourcePropertiesUtil.getValueFormat(props);
      final Format keyFormat = FormatFactory.of(SourcePropertiesUtil.getKeyFormat(props));
      final Format valueFormat = FormatFactory.of(valueFormatInfo);

      final Optional<ParsedSchema> valueSchema;
      if (valueFormat.supportsSchemaInference()) {
        final LogicalSchema logicalSchema = statement.getElements().toLogicalSchema();

        SerdeOptions serdeOptions;
        try {
          serdeOptions = SerdeOptionsFactory.buildForCreateStatement(
              logicalSchema,
              keyFormat,
              valueFormat,
              props.getSerdeOptions(),
              ksqlConfig
          );
        } catch (final Exception e) {
          // Catch block allows negative tests to fail in the correct place, later.
          serdeOptions = SerdeOptions.of();
        }

        valueSchema = logicalSchema.value().isEmpty()
            ? Optional.empty()
            : Optional.of(valueFormat.toParsedSchema(
                PersistenceSchema.from(logicalSchema.value(), serdeOptions.valueFeatures()),
                valueFormatInfo
            ));
      } else {
        valueSchema = Optional.empty();
      }

      final int partitions = props.getPartitions()
          .orElse(Topic.DEFAULT_PARTITIONS);

      final short rf = props.getReplicas()
          .orElse(Topic.DEFAULT_RF);

      return new Topic(props.getKafkaTopic(), partitions, rf, valueSchema);
    };

    try {
      final List<ParsedStatement> parsed = parser.parse(sql);
      if (parsed.size() > 1) {
        throw new IllegalArgumentException("SQL contains more than one statement: " + sql);
      }

      final List<Topic> topics = new ArrayList<>();
      for (ParsedStatement stmt : parsed) {
        // in order to extract the topics, we may need to also register type statements
        if (stmt.getStatement().statement() instanceof SqlBaseParser.RegisterTypeContext) {
          final PreparedStatement<?> prepare = parser.prepare(stmt, metaStore);
          registerType(prepare, metaStore);
        }

        if (isCsOrCT(stmt)) {
          final PreparedStatement<?> prepare = parser.prepare(stmt, metaStore);
          final ConfiguredStatement<?> configured =
              ConfiguredStatement.of(prepare, SessionConfig.of(ksqlConfig, Collections.emptyMap()));
          final ConfiguredStatement<?> withFormats = new DefaultFormatInjector().inject(configured);
          topics.add(extractTopic.apply(withFormats));
        }
      }

      return topics.isEmpty() ? null : topics.get(0);
    } catch (final Exception e) {
      // Statement won't parse: this will be detected/handled later.
      System.out.println("Error parsing statement (which may be expected): " + sql);
      e.printStackTrace(System.out);
      return null;
    }
  }

  private static void registerType(final PreparedStatement<?> prepare, final MetaStore metaStore) {
    if (prepare.getStatement() instanceof RegisterType) {
      final RegisterType statement = (RegisterType) prepare.getStatement();
      metaStore.registerType(statement.getName(), statement.getType().getSqlType());
    }
  }

  private static boolean isCsOrCT(final ParsedStatement stmt) {
    return stmt.getStatement().statement() instanceof SqlBaseParser.CreateStreamContext
        || stmt.getStatement().statement() instanceof SqlBaseParser.CreateTableContext;
  }

  private static String filePrefix(final String testPath) {
    return getNameWithoutExtension(testPath) + " - ";
  }
}
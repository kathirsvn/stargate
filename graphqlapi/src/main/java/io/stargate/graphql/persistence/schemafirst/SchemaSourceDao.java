/*
 * Copyright The Stargate Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.stargate.graphql.persistence.schemafirst;

import com.google.common.annotations.VisibleForTesting;
import io.stargate.db.datastore.DataStore;
import io.stargate.db.datastore.ResultSet;
import io.stargate.db.datastore.Row;
import io.stargate.db.query.BoundQuery;
import io.stargate.db.query.Predicate;
import io.stargate.db.query.builder.Replication;
import io.stargate.db.schema.Column;
import io.stargate.db.schema.ImmutableColumn;
import io.stargate.db.schema.ImmutableTable;
import io.stargate.db.schema.Keyspace;
import io.stargate.db.schema.Table;
import io.stargate.graphql.schema.schemafirst.migration.CassandraSchemaHelper;
import io.stargate.graphql.schema.schemafirst.util.Uuids;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO purge old entries
public class SchemaSourceDao {

  private static final Logger LOGGER = LoggerFactory.getLogger(SchemaSourceDao.class);
  public static final String KEYSPACE_NAME = "stargate_graphql";
  public static final String TABLE_NAME = "schema_source";
  @VisibleForTesting static final String NAMESPACE_COLUMN_NAME = "namespace";
  @VisibleForTesting static final String VERSION_COLUMN_NAME = "version";
  @VisibleForTesting static final String LATEST_VERSION_COLUMN_NAME = "latest_version";
  @VisibleForTesting static final String CONTENTS_COLUMN_NAME = "contents";
  @VisibleForTesting static final String APPLIED_COLUMN_NAME = "[applied]";

  @VisibleForTesting
  static final String DEPLOYMENT_IN_PROGRESS_COLUMN_NAME = "deployment_in_progress";

  private static final int NUMBER_OF_RETAINED_SCHEMA_VERSIONS = 10;

  @VisibleForTesting
  static final Table EXPECTED_TABLE =
      ImmutableTable.builder()
          .keyspace(KEYSPACE_NAME)
          .name(TABLE_NAME)
          .addColumns(
              ImmutableColumn.create(
                  NAMESPACE_COLUMN_NAME, Column.Kind.PartitionKey, Column.Type.Varchar),
              ImmutableColumn.create(
                  VERSION_COLUMN_NAME,
                  Column.Kind.Clustering,
                  Column.Type.Timeuuid,
                  Column.Order.DESC),
              ImmutableColumn.create(
                  CONTENTS_COLUMN_NAME, Column.Kind.Regular, Column.Type.Varchar),
              ImmutableColumn.create(
                  LATEST_VERSION_COLUMN_NAME, Column.Kind.Static, Column.Type.Timeuuid),
              ImmutableColumn.create(
                  DEPLOYMENT_IN_PROGRESS_COLUMN_NAME, Column.Kind.Static, Column.Type.Boolean))
          .build();

  private final DataStore dataStore;

  public SchemaSourceDao(DataStore dataStore) {
    this.dataStore = dataStore;
  }

  public List<SchemaSource> getSchemaHistory(String namespace) throws Exception {
    if (!tableExists()) {
      return Collections.emptyList();
    }
    List<Row> row = dataStore.execute(schemaQuery(namespace)).get().rows();
    if (row == null) {
      return Collections.emptyList();
    }
    return row.stream().map(r -> toSchemaSource(namespace, r)).collect(Collectors.toList());
  }

  public SchemaSource getByVersion(
      String namespace,
      @SuppressWarnings("OptionalUsedAsFieldOrParameterType") Optional<UUID> maybeVersion)
      throws Exception {
    if (!tableExists()) {
      return null;
    }
    ResultSet resultSet;
    if (maybeVersion.isPresent()) {
      UUID versionUuid = maybeVersion.get();
      if (versionUuid.version() != 1) { // must be time-based
        return null;
      }
      resultSet = dataStore.execute(schemaQueryWithSpecificVersion(namespace, versionUuid)).get();
    } else {
      resultSet = dataStore.execute(schemaQuery(namespace)).get();
    }
    if (!resultSet.iterator().hasNext()) {
      return null;
    }
    return toSchemaSource(namespace, resultSet.one());
  }

  public SchemaSource getLatest(String namespace) throws Exception {
    return getByVersion(namespace, Optional.empty());
  }

  private boolean tableExists() {
    Keyspace keyspace = dataStore.schema().keyspace(KEYSPACE_NAME);
    if (keyspace == null) {
      return false;
    }
    Table table = keyspace.table(TABLE_NAME);
    if (table == null) {
      return false;
    }
    failIfUnexpectedSchema(table);
    return true;
  }

  private SchemaSource toSchemaSource(String namespace, Row r) {
    return new SchemaSource(
        namespace, r.getUuid(VERSION_COLUMN_NAME), r.getString(CONTENTS_COLUMN_NAME));
  }

  @VisibleForTesting
  BoundQuery schemaQueryWithSpecificVersion(String namespace, UUID uuid) {
    return dataStore
        .queryBuilder()
        .select()
        .column(VERSION_COLUMN_NAME, CONTENTS_COLUMN_NAME)
        .from(KEYSPACE_NAME, TABLE_NAME)
        .where(NAMESPACE_COLUMN_NAME, Predicate.EQ, namespace)
        .where(VERSION_COLUMN_NAME, Predicate.EQ, uuid)
        .build()
        .bind();
  }

  @VisibleForTesting
  BoundQuery schemaQuery(String namespace) {
    return dataStore
        .queryBuilder()
        .select()
        .column(VERSION_COLUMN_NAME, CONTENTS_COLUMN_NAME)
        .from(KEYSPACE_NAME, TABLE_NAME)
        .where(NAMESPACE_COLUMN_NAME, Predicate.EQ, namespace)
        .orderBy(VERSION_COLUMN_NAME, Column.Order.DESC)
        .build()
        .bind();
  }

  /** @return the new version */
  public SchemaSource insert(String namespace, String newContents) {

    UUID newVersion = Uuids.timeBased();

    BoundQuery insertNewSchema =
        dataStore
            .queryBuilder()
            .insertInto(KEYSPACE_NAME, TABLE_NAME)
            .value(NAMESPACE_COLUMN_NAME, namespace)
            .value(VERSION_COLUMN_NAME, newVersion)
            .value(LATEST_VERSION_COLUMN_NAME, newVersion)
            .value(CONTENTS_COLUMN_NAME, newContents)
            .value(DEPLOYMENT_IN_PROGRESS_COLUMN_NAME, false)
            .build()
            .bind();

    try {
      dataStore.execute(insertNewSchema).get();
    } catch (Exception e) {
      throw new RuntimeException(
          String.format(
              "Schema deployment for namespace: %s and version: %s failed.",
              namespace, newVersion));
    }
    return new SchemaSource(namespace, newVersion, newContents);
  }

  private void ensureTableExists() throws Exception {
    dataStore
        .execute(
            dataStore
                .queryBuilder()
                .create()
                .keyspace(KEYSPACE_NAME)
                .ifNotExists()
                .withReplication(Replication.simpleStrategy(1))
                .build()
                .bind())
        .get();
    dataStore
        .execute(
            dataStore
                .queryBuilder()
                .create()
                .table(KEYSPACE_NAME, TABLE_NAME)
                .ifNotExists()
                .column(EXPECTED_TABLE.columns())
                .build()
                .bind())
        .get();

    // If the table already existed, CREATE IF NOT EXISTS does not guarantee that it matches what we
    // were trying to create.
    failIfUnexpectedSchema(dataStore.schema().keyspace(KEYSPACE_NAME).table(TABLE_NAME));
  }

  private static void failIfUnexpectedSchema(Table table) {
    if (!CassandraSchemaHelper.compare(EXPECTED_TABLE, table).isEmpty()) {
      throw new IllegalStateException(
          String.format(
              "Table '%s.%s' already exists, but it doesn't have the expected structure",
              KEYSPACE_NAME, TABLE_NAME));
    }
  }

  /**
   * "Locks" the table to start a new deployment. Concurrent calls to this method will fail until
   * either {@link #abortDeployment(String)} or {@link #insert(String, String)} have been called.
   *
   * @throws IllegalStateException if the deployment could not be started.
   */
  public void startDeployment(String namespace, UUID expectedLatestVersion) throws Exception {
    ensureTableExists();
    BoundQuery updateDeploymentToInProgress =
        dataStore
            .queryBuilder()
            .update(KEYSPACE_NAME, TABLE_NAME)
            .value(DEPLOYMENT_IN_PROGRESS_COLUMN_NAME, true)
            .where(NAMESPACE_COLUMN_NAME, Predicate.EQ, namespace)
            .ifs(DEPLOYMENT_IN_PROGRESS_COLUMN_NAME, Predicate.NEQ, true)
            .ifs(LATEST_VERSION_COLUMN_NAME, Predicate.EQ, expectedLatestVersion)
            .build()
            .bind();

    ResultSet resultSet = dataStore.execute(updateDeploymentToInProgress).get();
    Row row = resultSet.one();
    if (!row.getBoolean(APPLIED_COLUMN_NAME)) {
      boolean hasVersion =
          row.columns().stream().anyMatch(c -> LATEST_VERSION_COLUMN_NAME.equals(c.name()));
      if (!hasVersion) {
        throw new IllegalStateException(
            "You specified expectedVersion but no previous version was found");
      }
      UUID actualLatestVersion = row.getUuid(LATEST_VERSION_COLUMN_NAME);
      if (Objects.equals(actualLatestVersion, expectedLatestVersion)) {
        assert row.getBoolean(DEPLOYMENT_IN_PROGRESS_COLUMN_NAME);
        throw new IllegalStateException(
            "It looks like someone else is deploying a new schema. Please try again later.");
      }
      throw new IllegalStateException(
          String.format(
              "You specified expectedVersion %s, but there is a more recent version %s",
              expectedLatestVersion, actualLatestVersion));
    }
  }

  public void abortDeployment(String namespace) throws ExecutionException, InterruptedException {
    BoundQuery updateDeploymentToNotInProgress =
        dataStore
            .queryBuilder()
            .update(KEYSPACE_NAME, TABLE_NAME)
            .value(DEPLOYMENT_IN_PROGRESS_COLUMN_NAME, false)
            .where(NAMESPACE_COLUMN_NAME, Predicate.EQ, namespace)
            .build()
            .bind();
    dataStore.execute(updateDeploymentToNotInProgress).get();
  }

  public void purgeOldSchemaEntries(String namespace) throws Exception {
    List<SchemaSource> allSchemasForNamespace = getSchemaHistory(namespace);

    int numberOfEntriesToRemove =
        allSchemasForNamespace.size() - NUMBER_OF_RETAINED_SCHEMA_VERSIONS;
    if (numberOfEntriesToRemove > 0) {
      LOGGER.info("Removing {} old schema entries.", numberOfEntriesToRemove);

      // remove N oldest entries
      SchemaSource mostRecentToRemove =
          allSchemasForNamespace.get(NUMBER_OF_RETAINED_SCHEMA_VERSIONS);

      BoundQuery deleteSchemaQuery =
          dataStore
              .queryBuilder()
              .delete()
              .from(KEYSPACE_NAME, TABLE_NAME)
              .where(NAMESPACE_COLUMN_NAME, Predicate.EQ, namespace)
              .where(VERSION_COLUMN_NAME, Predicate.LTE, mostRecentToRemove.getVersion())
              .build()
              .bind();
      dataStore.execute(deleteSchemaQuery).get();
    }
  }
}
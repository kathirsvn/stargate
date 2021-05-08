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
package io.stargate.web.docsapi.service;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import hu.akarnokd.rxjava2.operators.ExpandStrategy;
import hu.akarnokd.rxjava2.operators.FlowableTransformers;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableOnSubscribe;
import io.reactivex.Single;
import io.stargate.db.ImmutableParameters;
import io.stargate.db.datastore.DataStore;
import io.stargate.db.datastore.ResultSet;
import io.stargate.db.datastore.Row;
import io.stargate.db.query.builder.AbstractBound;
import io.stargate.db.query.builder.BuiltSelect;
import io.stargate.db.schema.Column;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Executes pre-built document queries, groups document rows and manages document pagination. */
public class QueryExecutor {
  private final Accumulator TERM = new Accumulator();

  private final DataStore dataStore;

  public QueryExecutor(DataStore dataStore) {
    this.dataStore = dataStore;
  }

  public Flowable<RawDocument> queryDocs(
      AbstractBound<?> query, int pageSize, ByteBuffer pagingState) {
    return queryDocs(1, query, pageSize, pagingState);
  }

  public Flowable<RawDocument> queryDocs(
      int identityDepth, AbstractBound<?> query, int pageSize, ByteBuffer pagingState) {
    BuiltSelect select = (BuiltSelect) query.source().query();
    if (identityDepth < 1 || identityDepth > select.table().primaryKeyColumns().size()) {
      throw new IllegalArgumentException("Invalid document identity depth: " + identityDepth);
    }

    List<Column> idColumns = select.table().primaryKeyColumns().subList(0, identityDepth);

    return execute(query, pageSize, pagingState)
        .concatMap(rs -> Flowable.fromIterable(seeds(rs, idColumns)), 1)
        .concatWith(Single.just(TERM))
        .scan(Accumulator::combine)
        .filter(Accumulator::isComplete)
        .map(Accumulator::toDoc);
  }

  public Flowable<ResultSet> execute(AbstractBound<?> query, int pageSize, ByteBuffer pagingState) {
    return fetchPage(query, pageSize, pagingState)
        .compose( // Expand BREADTH_FIRST to reduce the number of "proactive" page requests
            FlowableTransformers.expand(
                rs -> fetchNext(rs, pageSize, query), ExpandStrategy.BREADTH_FIRST, 1));
  }

  private Flowable<ResultSet> fetchPage(
      AbstractBound<?> query, int pageSize, ByteBuffer pagingState) {
    CompletableFuture<ResultSet> futureResult = new CompletableFuture<>();
    return Flowable.create(
            (FlowableOnSubscribe<ResultSet>)
                emitter -> // separate subscription from execution - see doOnRequest
                futureResult.whenComplete(
                        (rows, t) -> {
                          if (t != null) {
                            emitter.onError(t);
                          } else {
                            emitter.onNext(rows);
                            emitter.onComplete();
                          }
                        }),
            BackpressureStrategy.BUFFER)
        .doOnRequest( // execute only when requested
            n -> // Note: `n` should be 1 and used only once - see .limit(1) below
            dataStore
                    .execute(
                        query,
                        p -> {
                          if (pageSize <= 0) {
                            return p; // if page size is not set pagingState is ignored by C*
                          }

                          ImmutableParameters.Builder builder = p.toBuilder();
                          builder.pageSize(pageSize);
                          if (pagingState != null) {
                            builder.pagingState(pagingState);
                          }
                          return builder.build();
                        })
                    .whenComplete(
                        (rows, t) -> {
                          if (t != null) {
                            futureResult.completeExceptionally(t);
                          } else {
                            futureResult.complete(rows);
                          }
                        }))
        .limit(1);
  }

  private Flowable<ResultSet> fetchNext(ResultSet rs, int pageSize, AbstractBound<?> query) {
    ByteBuffer nextPagingState = rs.getPagingState();
    if (nextPagingState == null) {
      return Flowable.empty();
    } else {
      return fetchPage(query, pageSize, nextPagingState);
    }
  }

  private Iterable<Accumulator> seeds(ResultSet rs, List<Column> keyColumns) {
    List<Row> rows = rs.currentPageRows();
    List<Accumulator> seeds = new ArrayList<>(rows.size());
    for (Row row : rows) {
      String id = row.getString("key");
      Builder<String> docKey = ImmutableList.builder();
      for (Column c : keyColumns) {
        docKey.add(Objects.requireNonNull(row.getString(c.name())));
      }
      seeds.add(new Accumulator(id, docKey.build(), rs, row));
    }
    return seeds;
  }

  public class Accumulator {

    private final String id;
    private final List<String> docKey;
    private final List<Row> rows;
    private final boolean complete;
    private final Accumulator next;
    private ResultSet lastResultSet;

    private Accumulator() {
      id = null;
      docKey = null;
      rows = null;
      next = null;
      complete = false;
    }

    private Accumulator(String id, List<String> docKey, ResultSet resultSet, Row seedRow) {
      this.id = id;
      this.docKey = docKey;
      this.rows = new ArrayList<>();
      this.next = null;
      this.complete = false;
      this.lastResultSet = resultSet;

      rows.add(seedRow);
    }

    private Accumulator(
        String id, List<String> docKey, ResultSet resultSet, List<Row> rows, Accumulator next) {
      this.id = id;
      this.docKey = docKey;
      this.rows = rows;
      this.next = next;
      this.complete = true;
      this.lastResultSet = resultSet;
    }

    boolean isComplete() {
      return complete;
    }

    public RawDocument toDoc() {
      if (!complete) {
        throw new IllegalStateException("Incomplete document.");
      }

      boolean hasNext = next != null || lastResultSet.getPagingState() != null;
      return new RawDocument(id, docKey, lastResultSet, hasNext, rows);
    }

    private Accumulator end() {
      if (next != null) {
        if (!complete) {
          throw new IllegalStateException("Ending an incomplete document");
        }

        return next.end();
      }

      if (complete) {
        throw new IllegalStateException("Already complete");
      }

      return new Accumulator(id, docKey, lastResultSet, rows, null);
    }

    private void append(Accumulator other) {
      rows.addAll(other.rows);
      lastResultSet = other.lastResultSet;
    }

    private Accumulator combine(Accumulator buffer) {
      if (buffer == TERM) {
        return end();
      }

      if (complete) {
        if (next == null) {
          throw new IllegalStateException(
              "Unexpected continuation after a terminal document element.");
        }

        return next.combine(buffer);
      }

      if (docKey.equals(buffer.docKey)) {
        append(buffer);
        return this; // still not complete
      } else {
        return new Accumulator(id, docKey, lastResultSet, rows, buffer);
      }
    }
  }
}
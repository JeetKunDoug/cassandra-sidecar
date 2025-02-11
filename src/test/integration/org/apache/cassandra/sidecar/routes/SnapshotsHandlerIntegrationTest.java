/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.sidecar.routes;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.extension.ExtendWith;

import com.datastax.driver.core.Session;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.apache.cassandra.sidecar.IntegrationTestBase;
import org.apache.cassandra.sidecar.common.containers.ExtendedCassandraContainer;
import org.apache.cassandra.sidecar.common.data.QualifiedTableName;
import org.apache.cassandra.sidecar.common.testing.CassandraIntegrationTest;
import org.apache.cassandra.sidecar.common.testing.CassandraTestContext;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(VertxExtension.class)
class SnapshotsHandlerIntegrationTest extends IntegrationTestBase
{
    @CassandraIntegrationTest
    void createSnapshotEndpointFailsWhenKeyspaceDoesNotExist(VertxTestContext context) throws InterruptedException
    {
        WebClient client = WebClient.create(vertx);
        String testRoute = "/api/v1/keyspaces/non-existent/tables/testtable/snapshots/my-snapshot";
        client.put(config.getPort(), "localhost", testRoute)
              .expect(ResponsePredicate.SC_NOT_FOUND)
              .send(context.succeedingThenComplete());
        // wait until the test completes
        assertThat(context.awaitCompletion(30, TimeUnit.SECONDS)).isTrue();
    }

    @CassandraIntegrationTest
    void createSnapshotEndpointFailsWhenTableDoesNotExist(VertxTestContext context,
                                                          CassandraTestContext cassandraTestContext)
    throws InterruptedException
    {
        createTestKeyspace(cassandraTestContext);

        WebClient client = WebClient.create(vertx);
        String testRoute = "/api/v1/keyspaces/testkeyspace/tables/non-existent/snapshots/my-snapshot";
        client.put(config.getPort(), "localhost", testRoute)
              .expect(ResponsePredicate.SC_NOT_FOUND)
              .send(context.succeedingThenComplete());
        // wait until the test completes
        assertThat(context.awaitCompletion(30, TimeUnit.SECONDS)).isTrue();
    }

    @CassandraIntegrationTest
    void createSnapshotFailsWhenSnapshotAlreadyExists(VertxTestContext context,
                                                      CassandraTestContext cassandraTestContext)
    throws InterruptedException
    {
        createTestKeyspace(cassandraTestContext);
        String table = createTestTableAndPopulate(cassandraTestContext);

        WebClient client = WebClient.create(vertx);
        String testRoute = String.format("/api/v1/keyspaces/%s/tables/%s/snapshots/my-snapshot",
                                         TEST_KEYSPACE, table);
        client.put(config.getPort(), "localhost", testRoute)
              .expect(ResponsePredicate.SC_OK)
              .send(context.succeeding(response -> context.verify(() ->
              {
                  assertThat(response.statusCode()).isEqualTo(OK.code());

                  // creating the snapshot with the same name will return a 409 (Conflict) status code
                  client.put(config.getPort(), "localhost", testRoute)
                        .expect(ResponsePredicate.SC_CONFLICT)
                        .send(context.succeedingThenComplete());
              })));
        // wait until the test completes
        assertThat(context.awaitCompletion(30, TimeUnit.SECONDS)).isTrue();
    }

    @CassandraIntegrationTest
    void testCreateSnapshotEndpoint(VertxTestContext context, CassandraTestContext cassandraTestContext)
    throws InterruptedException
    {
        createTestKeyspace(cassandraTestContext);
        String table = createTestTableAndPopulate(cassandraTestContext);

        WebClient client = WebClient.create(vertx);
        String testRoute = String.format("/api/v1/keyspaces/%s/tables/%s/snapshots/my-snapshot",
                                         TEST_KEYSPACE, table);
        client.put(config.getPort(), "localhost", testRoute)
              .expect(ResponsePredicate.SC_OK)
              .send(context.succeeding(response -> context.verify(() ->
              {
                  assertThat(response.statusCode()).isEqualTo(OK.code());

                  // validate that the snapshot is created
                  ExtendedCassandraContainer container = cassandraTestContext.container;
                  final String directory = container.execInContainer("find",
                                                                     "/opt/cassandra/data/",
                                                                     "-name",
                                                                     "my-snapshot").getStdout().trim();
                  assertThat(directory).isNotEmpty();
                  assertThat(directory).contains("testkeyspace");
                  assertThat(directory).contains("testtable");
                  assertThat(directory).contains("my-snapshot");
                  final String lsSnapshot = container.execInContainer("ls", directory).getStdout();
                  assertThat(lsSnapshot).contains("manifest.json");
                  assertThat(lsSnapshot).contains("schema.cql");
                  assertThat(lsSnapshot).contains("-big-Data.db");

                  context.completeNow();
              })));
        // wait until test completes
        assertThat(context.awaitCompletion(30, TimeUnit.SECONDS)).isTrue();
    }

    @CassandraIntegrationTest
    void deleteSnapshotFailsWhenKeyspaceDoesNotExist(VertxTestContext context) throws InterruptedException
    {
        String testRoute = "/api/v1/keyspaces/non-existent/tables/testtable/snapshots/my-snapshot";
        assertNotFoundOnDeleteSnapshot(context, testRoute);
    }

    @CassandraIntegrationTest
    void deleteSnapshotFailsWhenTableDoesNotExist(VertxTestContext context,
                                                  CassandraTestContext cassandraTestContext)
    throws InterruptedException
    {
        createTestKeyspace(cassandraTestContext);
        createTestTableAndPopulate(cassandraTestContext);

        String testRoute = "/api/v1/keyspaces/testkeyspace/tables/non-existent/snapshots/my-snapshot";
        assertNotFoundOnDeleteSnapshot(context, testRoute);
    }

    @CassandraIntegrationTest
    void deleteSnapshotFailsWhenSnapshotDoesNotExist(VertxTestContext context,
                                                     CassandraTestContext cassandraTestContext)
    throws InterruptedException
    {
        createTestKeyspace(cassandraTestContext);
        String table = createTestTableAndPopulate(cassandraTestContext);

        String testRoute = String.format("/api/v1/keyspaces/%s/tables/%s/snapshots/non-existent",
                                         TEST_KEYSPACE, table);
        assertNotFoundOnDeleteSnapshot(context, testRoute);
    }

    @CassandraIntegrationTest
    void testDeleteSnapshotEndpoint(VertxTestContext context, CassandraTestContext cassandraTestContext)
    throws InterruptedException
    {
        createTestKeyspace(cassandraTestContext);
        String table = createTestTableAndPopulate(cassandraTestContext);

        WebClient client = WebClient.create(vertx);
        String testRoute = String.format("/api/v1/keyspaces/%s/tables/%s/snapshots/my-snapshot",
                                         TEST_KEYSPACE, table);

        // first create the snapshot
        client.put(config.getPort(), "localhost", testRoute)
              .expect(ResponsePredicate.SC_OK)
              .send(context.succeeding(createResponse -> context.verify(() ->
              {
                  assertThat(createResponse.statusCode()).isEqualTo(OK.code());
                  ExtendedCassandraContainer container = cassandraTestContext.container;
                  final String directory = container.execInContainer("find",
                                                                     "/opt/cassandra/data/",
                                                                     "-name",
                                                                     "my-snapshot").getStdout().trim();
                  // snapshot directory exists inside container
                  assertThat(directory).isNotBlank();

                  // then delete the snapshot
                  client.delete(config.getPort(), "localhost", testRoute)
                        .expect(ResponsePredicate.SC_OK)
                        .send(context.succeeding(deleteResponse -> context.verify(() ->
                        {
                            assertThat(deleteResponse.statusCode()).isEqualTo(OK.code());
                            // validate that the snapshot is removed
                            final String removedDir = container.execInContainer("find",
                                                                                "/opt/cassandra/data/",
                                                                                "-name",
                                                                                "my-snapshot").getStdout().trim();
                            assertThat(removedDir).isEmpty();

                            context.completeNow();
                        })));
              })));
        // wait until the test completes
        assertThat(context.awaitCompletion(30, TimeUnit.SECONDS)).isTrue();
    }

    private String createTestTableAndPopulate(CassandraTestContext cassandraTestContext)
    {
        QualifiedTableName tableName = createTestTable(cassandraTestContext,
                                                       "CREATE TABLE %s (id text PRIMARY KEY, name text);");
        Session session = maybeGetSession(cassandraTestContext);

        session.execute("INSERT INTO " + tableName + " (id, name) VALUES ('1', 'Francisco');");
        session.execute("INSERT INTO " + tableName + " (id, name) VALUES ('2', 'Saranya');");
        session.execute("INSERT INTO " + tableName + " (id, name) VALUES ('3', 'Yifan');");
        return tableName.tableName();
    }

    private void assertNotFoundOnDeleteSnapshot(VertxTestContext context, String testRoute) throws InterruptedException
    {
        WebClient client = WebClient.create(vertx);
        client.delete(config.getPort(), "localhost", testRoute)
              .expect(ResponsePredicate.SC_NOT_FOUND)
              .send(context.succeedingThenComplete());
        // wait until test completes
        assertThat(context.awaitCompletion(30, TimeUnit.SECONDS)).isTrue();
    }
}

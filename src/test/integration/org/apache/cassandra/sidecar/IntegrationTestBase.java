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

package org.apache.cassandra.sidecar;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.Session;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Modules;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxTestContext;
import org.apache.cassandra.sidecar.common.data.QualifiedTableName;
import org.apache.cassandra.sidecar.common.testing.CassandraTestContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for integration test.
 * Start a docker container of cassandra at the beginning of each test, and
 * teardown the container after each test.
 */
public abstract class IntegrationTestBase
{
    protected Logger logger = LoggerFactory.getLogger(this.getClass());
    protected Vertx vertx;
    protected HttpServer server;
    protected Configuration config;

    protected static final String TEST_KEYSPACE = "testkeyspace";
    private static final String TEST_TABLE_PREFIX = "testtable";
    private static final AtomicInteger TEST_TABLE_ID = new AtomicInteger(0);

    @BeforeEach
    public void setup(CassandraTestContext cassandraTestContext) throws InterruptedException
    {
        Injector injector = Guice.createInjector(Modules.override(new MainModule())
                                                        .with(new IntegrationTestModule(cassandraTestContext)));
        server = injector.getInstance(HttpServer.class);
        vertx = injector.getInstance(Vertx.class);
        config = injector.getInstance(Configuration.class);

        VertxTestContext context = new VertxTestContext();
        server.listen(config.getPort(), config.getHost(), context.succeeding(p -> {
            config.getInstancesConfig().instances()
                  .forEach(instanceMetadata -> instanceMetadata.delegate().healthCheck());
            context.completeNow();
        }));

        context.awaitCompletion(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() throws InterruptedException
    {
        final CountDownLatch closeLatch = new CountDownLatch(1);
        server.close(res -> closeLatch.countDown());
        vertx.close();
        if (closeLatch.await(60, TimeUnit.SECONDS))
            logger.info("Close event received before timeout.");
        else
            logger.error("Close event timed out.");
    }

    protected void testWithClient(VertxTestContext context, Consumer<WebClient> tester) throws Exception
    {
        WebClient client = WebClient.create(vertx);

        tester.accept(client);

        // wait until the test completes
        assertThat(context.awaitCompletion(30, TimeUnit.SECONDS)).isTrue();
    }

    protected void createTestKeyspace(CassandraTestContext cassandraTestContext)
    {
        Session session = maybeGetSession(cassandraTestContext);

        session.execute(
        "CREATE KEYSPACE " + TEST_KEYSPACE +
        " WITH REPLICATION = { 'class' : 'org.apache.cassandra.locator.SimpleStrategy', 'replication_factor': '1' }" +
        " AND DURABLE_WRITES = true;"
        );
    }

    protected QualifiedTableName createTestTable(CassandraTestContext cassandraTestContext, String createTableStatement)
    {
        Session session = maybeGetSession(cassandraTestContext);
        QualifiedTableName tableName = uniqueTestTableFullName();
        session.execute(String.format(createTableStatement, tableName));
        return tableName;
    }

    protected Session maybeGetSession(CassandraTestContext cassandraTestContext)
    {
        Session session = cassandraTestContext.session.localCql();
        assertThat(session).isNotNull();
        return session;
    }

    private static QualifiedTableName uniqueTestTableFullName()
    {
        return new QualifiedTableName(TEST_KEYSPACE, TEST_TABLE_PREFIX + TEST_TABLE_ID.getAndIncrement());
    }
}

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

package org.apache.cassandra.sidecar.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import okio.Okio;
import org.apache.cassandra.sidecar.client.exception.RetriesExhaustedException;
import org.apache.cassandra.sidecar.client.request.ImportSSTableRequest;
import org.apache.cassandra.sidecar.client.request.NodeSettingsRequest;
import org.apache.cassandra.sidecar.client.request.Request;
import org.apache.cassandra.sidecar.client.request.RequestExecutorTest;
import org.apache.cassandra.sidecar.client.retry.RetryAction;
import org.apache.cassandra.sidecar.client.retry.RetryPolicy;
import org.apache.cassandra.sidecar.common.ApiEndpointsV1;
import org.apache.cassandra.sidecar.common.NodeSettings;
import org.apache.cassandra.sidecar.common.data.GossipInfoResponse;
import org.apache.cassandra.sidecar.common.data.ListSnapshotFilesResponse;
import org.apache.cassandra.sidecar.common.data.RingEntry;
import org.apache.cassandra.sidecar.common.data.RingResponse;
import org.apache.cassandra.sidecar.common.data.SSTableImportResponse;
import org.apache.cassandra.sidecar.common.data.SchemaResponse;
import org.apache.cassandra.sidecar.common.data.TimeSkewResponse;
import org.apache.cassandra.sidecar.common.utils.HttpRange;

import static io.netty.handler.codec.http.HttpResponseStatus.ACCEPTED;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.PARTIAL_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.fail;

abstract class SidecarClientTest
{
    SidecarClient client;
    List<MockWebServer> servers;
    List<SidecarInstanceImpl> instances;

    @BeforeEach
    void setup()
    {
        servers = new ArrayList<>();
        for (int i = 0; i < 4; i++)
        {
            servers.add(new MockWebServer());
        }

        instances = servers.stream()
                           .map(RequestExecutorTest::newSidecarInstance)
                           .collect(Collectors.toList());
        client = initialize(instances);
    }

    protected abstract SidecarClient initialize(List<SidecarInstanceImpl> instances);

    @AfterEach
    void tearDown() throws Exception
    {
        for (MockWebServer server : servers)
        {
            server.shutdown();
        }
        client.close();
    }

    @Test
    void testFullSchema() throws Exception
    {
        String fullSchemaAsString = "{\"schema\":\"CREATE KEYSPACE sample_ks.sample_table ...\"}";
        MockResponse response = new MockResponse().setResponseCode(OK.code()).setBody(fullSchemaAsString);
        enqueue(response);

        SchemaResponse result = client.fullSchema().get(30, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.keyspace()).isNull();
        assertThat(result.schema()).isEqualTo("CREATE KEYSPACE sample_ks.sample_table ...");

        validateResponseServed(ApiEndpointsV1.ALL_KEYSPACES_SCHEMA_ROUTE);
    }

    @Test
    void testSchema() throws Exception
    {
        String schemaAsString = "{\"keyspace\":\"cycling\",\"schema\":\"CREATE KEYSPACE sample_ks.sample_table ...\"}";
        MockResponse response = new MockResponse().setResponseCode(OK.code()).setBody(schemaAsString);
        enqueue(response);

        SchemaResponse result = client.schema("cycling").get(30, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.keyspace()).isEqualTo("cycling");
        assertThat(result.schema()).isEqualTo("CREATE KEYSPACE sample_ks.sample_table ...");

        validateResponseServed(ApiEndpointsV1.KEYSPACE_SCHEMA_ROUTE.replaceAll(ApiEndpointsV1.KEYSPACE_PATH_PARAM,
                                                                               "cycling"));
    }

    @Test
    void testRing() throws Exception
    {
        String schemaAsString = "[{\"datacenter\":\"dc\",\"address\":\"127.0.0.1\",\"port\":80,\"rack\":\"r1\"," +
                                "\"status\":\"up\",\"state\":\"normal\",\"load\":\"1 KiB\",\"owns\":\"1%\"," +
                                "\"token\":\"100\",\"fqdn\":\"local\",\"hostId\":\"000\"}]";
        MockResponse response = new MockResponse().setResponseCode(OK.code()).setBody(schemaAsString);
        enqueue(response);

        RingResponse result = client.ring("cycling").get(30, TimeUnit.SECONDS);
        assertThat(result).isNotNull()
                          .hasSize(1);
        RingEntry entry = result.iterator().next();
        assertThat(entry.datacenter()).isEqualTo("dc");
        assertThat(entry.address()).isEqualTo("127.0.0.1");
        assertThat(entry.port()).isEqualTo(80);
        assertThat(entry.rack()).isEqualTo("r1");
        assertThat(entry.status()).isEqualTo("up");
        assertThat(entry.state()).isEqualTo("normal");
        assertThat(entry.load()).isEqualTo("1 KiB");
        assertThat(entry.owns()).isEqualTo("1%");
        assertThat(entry.token()).isEqualTo("100");
        assertThat(entry.fqdn()).isEqualTo("local");
        assertThat(entry.hostId()).isEqualTo("000");

        validateResponseServed(ApiEndpointsV1.RING_ROUTE_PER_KEYSPACE.replaceAll(ApiEndpointsV1.KEYSPACE_PATH_PARAM,
                                                                                 "cycling"));
    }

    @Test
    public void testNodeSettings() throws Exception
    {
        String nodeSettingsAsString = "{\"partitioner\":\"test-partitioner\", \"releaseVersion\": \"4.0.0\"}";
        MockResponse response = new MockResponse().setResponseCode(OK.code()).setBody(nodeSettingsAsString);
        enqueue(response);

        NodeSettings result = client.nodeSettings().get(30, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.partitioner()).isEqualTo("test-partitioner");
        assertThat(result.releaseVersion()).isEqualTo("4.0.0");

        validateResponseServed(ApiEndpointsV1.NODE_SETTINGS_ROUTE);
    }

    @Test
    public void testNodeSettingsFromSpecifiedInstance() throws Exception
    {
        String nodeSettingsAsString = "{\"partitioner\":\"test-partitioner\", \"releaseVersion\": \"4.0.0\"}";
        MockResponse response = new MockResponse().setResponseCode(OK.code()).setBody(nodeSettingsAsString);
        MockWebServer mockWebServer = servers.get(1);
        mockWebServer.enqueue(response);

        SidecarInstanceImpl instance = new SidecarInstanceImpl(mockWebServer.getHostName(), mockWebServer.getPort());
        NodeSettings result = client.nodeSettings(instance).get(30, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.partitioner()).isEqualTo("test-partitioner");
        assertThat(result.releaseVersion()).isEqualTo("4.0.0");

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getPath()).isEqualTo(ApiEndpointsV1.NODE_SETTINGS_ROUTE);
    }

    @Test
    public void testGossipInfo() throws Exception
    {
        String gossipInfoAsString = "{\"/127.0.0.1:7000\":{\"generation\":\"1\",\"schema\":\"4994b214\"," +
                                    "\"rack\":\"r2\",\"heartbeat\":\"214\",\"releaseVersion\":\"4.0.7\"," +
                                    "\"sstableVersions\":\"big-nb\"}}";
        MockResponse response = new MockResponse().setResponseCode(OK.code()).setBody(gossipInfoAsString);
        enqueue(response);

        GossipInfoResponse result = client.gossipInfo().get(30, TimeUnit.SECONDS);
        assertThat(result).isNotNull().hasSize(1);
        String key = result.entrySet().iterator().next().getKey();
        GossipInfoResponse.GossipInfo gossipInfo = result.get(key);
        assertThat(gossipInfo.generation()).isEqualTo("1");
        assertThat(gossipInfo.schema()).isEqualTo("4994b214");
        assertThat(gossipInfo.rack()).isEqualTo("r2");
        assertThat(gossipInfo.heartbeat()).isEqualTo("214");
        assertThat(gossipInfo.releaseVersion()).isEqualTo("4.0.7");
        assertThat(gossipInfo.sstableVersions()).isEqualTo(Collections.singletonList("big-nb"));

        validateResponseServed(ApiEndpointsV1.GOSSIP_INFO_ROUTE);
    }

    @Test
    public void testTimeSkew() throws Exception
    {
        String timeSkewAsString = "{\"currentTime\":\"123456789\",\"allowableSkewInMinutes\":\"122\"}}";
        MockResponse response = new MockResponse().setResponseCode(OK.code()).setBody(timeSkewAsString);
        enqueue(response);

        TimeSkewResponse result = client.timeSkew().get(30, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.allowableSkewInMinutes).isEqualTo(122);
        assertThat(result.currentTime).isEqualTo(123456789);

        validateResponseServed(ApiEndpointsV1.TIME_SKEW_ROUTE);
    }

    @Test
    public void testTimeSkewFromReplicaSet() throws Exception
    {
        String timeSkewAsString = "{\"currentTime\":\"5555555\",\"allowableSkewInMinutes\":\"24\"}}";
        MockResponse response = new MockResponse().setResponseCode(OK.code()).setBody(timeSkewAsString);
        enqueue(response);

        TimeSkewResponse result = client.timeSkew(instances.subList(1, 2)).get(30, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.allowableSkewInMinutes).isEqualTo(24);
        assertThat(result.currentTime).isEqualTo(5555555);

        validateResponseServed(ApiEndpointsV1.TIME_SKEW_ROUTE);
    }

    @Test
    public void testListSnapshotFiles() throws Exception
    {
        String responseAsString = "{\"snapshotFilesInfo\":[{\"size\":15,\"host\":\"localhost1\",\"port\":2020," +
                                  "\"dataDirIndex\":1,\"snapshotName\":\"2023.04.11\",\"keySpaceName\":\"cycling\"," +
                                  "\"tableName\":\"cyclist_name\",\"fileName\":\"nb-203-big-TOC.txt\"}]}";
        MockResponse response = new MockResponse().setResponseCode(OK.code()).setBody(responseAsString);
        SidecarInstanceImpl sidecarInstance = instances.get(2);
        MockWebServer mockWebServer = servers.get(2);
        mockWebServer.enqueue(response);

        ListSnapshotFilesResponse result = client.listSnapshotFiles(sidecarInstance,
                                                                    "cycling",
                                                                    "cyclist_name",
                                                                    "2023.04.11")
                                                 .get(30, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.snapshotFilesInfo()).hasSize(1);
        ListSnapshotFilesResponse.FileInfo fileInfo = result.snapshotFilesInfo().get(0);
        assertThat(fileInfo.size).isEqualTo(15);
        assertThat(fileInfo.host).isEqualTo("localhost1");
        assertThat(fileInfo.port).isEqualTo(2020);
        assertThat(fileInfo.dataDirIndex).isEqualTo(1);
        assertThat(fileInfo.snapshotName).isEqualTo("2023.04.11");
        assertThat(fileInfo.keySpaceName).isEqualTo("cycling");
        assertThat(fileInfo.tableName).isEqualTo("cyclist_name");
        assertThat(fileInfo.fileName).isEqualTo("nb-203-big-TOC.txt");

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getPath()).isEqualTo(ApiEndpointsV1.SNAPSHOTS_ROUTE
                                                .replaceAll(ApiEndpointsV1.KEYSPACE_PATH_PARAM, "cycling")
                                                .replaceAll(ApiEndpointsV1.TABLE_PATH_PARAM, "cyclist_name")
                                                .replaceAll(ApiEndpointsV1.SNAPSHOT_PATH_PARAM, "2023.04.11")
                                                + "?includeSecondaryIndexFiles=true");
    }

    @Test
    public void testListSnapshotFilesWithoutSecondaryIndexFiles() throws Exception
    {
        String responseAsString = "{\"snapshotFilesInfo\":[{\"size\":15,\"host\":\"localhost1\",\"port\":2020," +
                                  "\"dataDirIndex\":1,\"snapshotName\":\"2023.04.11\",\"keySpaceName\":\"cycling\"," +
                                  "\"tableName\":\"cyclist_name\",\"fileName\":\"nb-203-big-TOC.txt\"}]}";
        MockResponse response = new MockResponse().setResponseCode(OK.code()).setBody(responseAsString);
        SidecarInstanceImpl sidecarInstance = instances.get(2);
        MockWebServer mockWebServer = servers.get(2);
        mockWebServer.enqueue(response);

        ListSnapshotFilesResponse result = client.listSnapshotFiles(sidecarInstance,
                                                                    "cycling",
                                                                    "cyclist_name",
                                                                    "2023.04.11",
                                                                    false)
                                                 .get(30, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.snapshotFilesInfo()).hasSize(1);
        ListSnapshotFilesResponse.FileInfo fileInfo = result.snapshotFilesInfo().get(0);
        assertThat(fileInfo.size).isEqualTo(15);
        assertThat(fileInfo.host).isEqualTo("localhost1");
        assertThat(fileInfo.port).isEqualTo(2020);
        assertThat(fileInfo.dataDirIndex).isEqualTo(1);
        assertThat(fileInfo.snapshotName).isEqualTo("2023.04.11");
        assertThat(fileInfo.keySpaceName).isEqualTo("cycling");
        assertThat(fileInfo.tableName).isEqualTo("cyclist_name");
        assertThat(fileInfo.fileName).isEqualTo("nb-203-big-TOC.txt");

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getPath()).isEqualTo(ApiEndpointsV1.SNAPSHOTS_ROUTE
                                                .replaceAll(ApiEndpointsV1.KEYSPACE_PATH_PARAM, "cycling")
                                                .replaceAll(ApiEndpointsV1.TABLE_PATH_PARAM, "cyclist_name")
                                                .replaceAll(ApiEndpointsV1.SNAPSHOT_PATH_PARAM, "2023.04.11"));
    }


    @Test
    void testClearSnapshot() throws Exception
    {
        MockResponse response = new MockResponse().setResponseCode(OK.code());
        SidecarInstanceImpl sidecarInstance = instances.get(2);
        MockWebServer mockWebServer = servers.get(2);
        mockWebServer.enqueue(response);

        client.clearSnapshot(sidecarInstance, "cycling", "cyclist_name", "2023.04.11")
              .get(30, TimeUnit.SECONDS);

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getPath()).isEqualTo(ApiEndpointsV1.SNAPSHOTS_ROUTE
                                                .replaceAll(ApiEndpointsV1.KEYSPACE_PATH_PARAM, "cycling")
                                                .replaceAll(ApiEndpointsV1.TABLE_PATH_PARAM, "cyclist_name")
                                                .replaceAll(ApiEndpointsV1.SNAPSHOT_PATH_PARAM, "2023.04.11"));
        assertThat(request.getMethod()).isEqualTo("DELETE");
    }

    @Test
    void testCreateSnapshot() throws Exception
    {
        MockResponse response = new MockResponse().setResponseCode(OK.code());
        SidecarInstanceImpl sidecarInstance = instances.get(3);
        MockWebServer mockWebServer = servers.get(3);
        mockWebServer.enqueue(response);

        client.createSnapshot(sidecarInstance, "cycling", "cyclist_name", "2023.04.11")
              .get(30, TimeUnit.SECONDS);

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getPath()).isEqualTo(ApiEndpointsV1.SNAPSHOTS_ROUTE
                                                .replaceAll(ApiEndpointsV1.KEYSPACE_PATH_PARAM, "cycling")
                                                .replaceAll(ApiEndpointsV1.TABLE_PATH_PARAM, "cyclist_name")
                                                .replaceAll(ApiEndpointsV1.SNAPSHOT_PATH_PARAM, "2023.04.11"));
        assertThat(request.getMethod()).isEqualTo("PUT");
    }

    @Test
    void testCleanUploadSession() throws Exception
    {
        MockResponse response = new MockResponse().setResponseCode(OK.code());
        SidecarInstanceImpl sidecarInstance = instances.get(0);
        MockWebServer mockWebServer = servers.get(0);
        mockWebServer.enqueue(response);

        client.cleanUploadSession(sidecarInstance, "00000")
              .get(30, TimeUnit.SECONDS);

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getPath()).isEqualTo(ApiEndpointsV1.SSTABLE_CLEANUP_ROUTE
                                                .replaceAll(ApiEndpointsV1.UPLOAD_ID_PATH_PARAM, "00000"));
        assertThat(request.getMethod()).isEqualTo("DELETE");
    }

    @Test
    void testSSTableImport() throws Exception
    {
        String responseAsString = "{\"success\":true,\"uploadId\":\"0000-0000\",\"keyspace\":\"cycling\"," +
                                  "\"tableName\":\"cyclist_name\"}";
        MockResponse response = new MockResponse().setResponseCode(OK.code()).setBody(responseAsString);
        SidecarInstanceImpl sidecarInstance = instances.get(0);
        MockWebServer mockWebServer = servers.get(0);
        mockWebServer.enqueue(response);

        ImportSSTableRequest.ImportOptions options = new ImportSSTableRequest.ImportOptions();
        SSTableImportResponse result = client.importSSTableRequest(sidecarInstance,
                                                                   "cycling",
                                                                   "cyclist_name",
                                                                   "0000-0000",
                                                                   options)
                                             .get(30, TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        assertThat(result.keyspace()).isEqualTo("cycling");
        assertThat(result.tableName()).isEqualTo("cyclist_name");
        assertThat(result.success()).isTrue();
        assertThat(result.uploadId()).isEqualTo("0000-0000");

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getPath()).isEqualTo(ApiEndpointsV1.SSTABLE_IMPORT_ROUTE
                                                .replaceAll(ApiEndpointsV1.KEYSPACE_PATH_PARAM, "cycling")
                                                .replaceAll(ApiEndpointsV1.TABLE_PATH_PARAM, "cyclist_name")
                                                .replaceAll(ApiEndpointsV1.UPLOAD_ID_PATH_PARAM, "0000-0000"));
        assertThat(request.getMethod()).isEqualTo("PUT");
    }

    @Test
    void testSSTableImportWithAcceptedResponse() throws Exception
    {
        String responseAsString = "{\"success\":true,\"uploadId\":\"0000-0000\",\"keyspace\":\"cycling\"," +
                                  "\"tableName\":\"cyclist_name\"}";
        SidecarInstanceImpl sidecarInstance = instances.get(0);
        MockWebServer mockWebServer = servers.get(0);
        mockWebServer.enqueue(new MockResponse().setResponseCode(ACCEPTED.code()));
        mockWebServer.enqueue(new MockResponse().setResponseCode(ACCEPTED.code()));
        mockWebServer.enqueue(new MockResponse().setResponseCode(ACCEPTED.code()));
        mockWebServer.enqueue(new MockResponse().setResponseCode(ACCEPTED.code()));
        mockWebServer.enqueue(new MockResponse().setResponseCode(OK.code()).setBody(responseAsString));

        ImportSSTableRequest.ImportOptions options = new ImportSSTableRequest.ImportOptions();
        SSTableImportResponse result = client.importSSTableRequest(sidecarInstance,
                                                                   "cycling",
                                                                   "cyclist_name",
                                                                   "0000-0000",
                                                                   options)
                                             .get(30, TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        assertThat(result.keyspace()).isEqualTo("cycling");
        assertThat(result.tableName()).isEqualTo("cyclist_name");
        assertThat(result.success()).isTrue();
        assertThat(result.uploadId()).isEqualTo("0000-0000");

        assertThat(mockWebServer.getRequestCount()).isEqualTo(5);
        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getPath()).isEqualTo(ApiEndpointsV1.SSTABLE_IMPORT_ROUTE
                                                .replaceAll(ApiEndpointsV1.KEYSPACE_PATH_PARAM, "cycling")
                                                .replaceAll(ApiEndpointsV1.TABLE_PATH_PARAM, "cyclist_name")
                                                .replaceAll(ApiEndpointsV1.UPLOAD_ID_PATH_PARAM, "0000-0000"));
        assertThat(request.getMethod()).isEqualTo("PUT");
    }

    @Test
    void testUploadSSTableFailsWhenFileDoesNotExist()
    {

        assertThatIllegalArgumentException()
        .isThrownBy(() -> client.uploadSSTableRequest(new SidecarInstanceImpl("host", 8080),
                                                      "cycling",
                                                      "cyclist_name",
                                                      "0000-0000",
                                                      "nb-1-big-TOC.txt",
                                                      null,
                                                      "path")
                                .get(30, TimeUnit.SECONDS))
        .withMessage("File 'path' does not exist");
    }

    @Test
    void testUploadSSTableWithoutChecksum(@TempDir Path tempDirectory) throws Exception
    {
        Path fileToUpload = prepareFile(tempDirectory);
        try (MockWebServer server = new MockWebServer())
        {
            server.enqueue(new MockResponse().setResponseCode(OK.code()));

            SidecarInstanceImpl sidecarInstance = RequestExecutorTest.newSidecarInstance(server);
            client.uploadSSTableRequest(sidecarInstance,
                                        "cycling",
                                        "cyclist_name",
                                        "0000-0000",
                                        "nb-1-big-TOC.txt",
                                        null,
                                        fileToUpload.toString())
                  .get(30, TimeUnit.SECONDS);

            assertThat(server.getRequestCount()).isEqualTo(1);
            RecordedRequest request = server.takeRequest();
            assertThat(request.getPath())
            .isEqualTo(ApiEndpointsV1.SSTABLE_UPLOAD_ROUTE
                       .replaceAll(ApiEndpointsV1.UPLOAD_ID_PATH_PARAM, "0000-0000")
                       .replaceAll(ApiEndpointsV1.KEYSPACE_PATH_PARAM, "cycling")
                       .replaceAll(ApiEndpointsV1.TABLE_PATH_PARAM, "cyclist_name")
                       .replaceAll(ApiEndpointsV1.COMPONENT_PATH_PARAM, "nb-1-big-TOC.txt"));
            assertThat(request.getMethod()).isEqualTo("PUT");
            assertThat(request.getHeader(HttpHeaderNames.CONTENT_MD5.toString())).isNull();
            assertThat(request.getHeader(HttpHeaderNames.CONTENT_LENGTH.toString())).isEqualTo("80");
            assertThat(request.getBodySize()).isEqualTo(80);
        }
    }

    @Test
    void testUploadSSTableWithChecksum(@TempDir Path tempDirectory) throws Exception
    {
        Path fileToUpload = prepareFile(tempDirectory);
        try (MockWebServer server = new MockWebServer())
        {
            server.enqueue(new MockResponse().setResponseCode(OK.code()));

            SidecarInstanceImpl sidecarInstance = RequestExecutorTest.newSidecarInstance(server);
            client.uploadSSTableRequest(sidecarInstance,
                                        "cycling",
                                        "cyclist_name",
                                        "0000-0000",
                                        "nb-1-big-TOC.txt",
                                        "15a69dc6501aa5ae17af037fe053f610",
                                        fileToUpload.toString())
                  .get(30, TimeUnit.SECONDS);

            assertThat(server.getRequestCount()).isEqualTo(1);
            RecordedRequest request = server.takeRequest();
            assertThat(request.getPath())
            .isEqualTo(ApiEndpointsV1.SSTABLE_UPLOAD_ROUTE
                       .replaceAll(ApiEndpointsV1.UPLOAD_ID_PATH_PARAM, "0000-0000")
                       .replaceAll(ApiEndpointsV1.KEYSPACE_PATH_PARAM, "cycling")
                       .replaceAll(ApiEndpointsV1.TABLE_PATH_PARAM, "cyclist_name")
                       .replaceAll(ApiEndpointsV1.COMPONENT_PATH_PARAM, "nb-1-big-TOC.txt"));
            assertThat(request.getMethod()).isEqualTo("PUT");
            assertThat(request.getHeader(HttpHeaderNames.CONTENT_MD5.toString()))
            .isEqualTo("15a69dc6501aa5ae17af037fe053f610");
            assertThat(request.getHeader(HttpHeaderNames.CONTENT_LENGTH.toString())).isEqualTo("80");
            assertThat(request.getBodySize()).isEqualTo(80);
        }
    }

    @Test
    void testStreamSSTableComponentWithNoRange() throws Exception
    {
        try (MockWebServer server = new MockWebServer())
        {
            CountDownLatch latch = new CountDownLatch(1);
            List<byte[]> receivedBytes = new ArrayList<>();
            StreamConsumer mockStreamConsumer = new StreamConsumer()
            {
                @Override
                public void onRead(StreamBuffer buffer)
                {
                    assertThat(buffer.readableBytes()).isGreaterThan(0);
                    byte[] dst = new byte[buffer.readableBytes()];
                    buffer.copyBytes(0, dst, 0, buffer.readableBytes());
                    receivedBytes.add(dst);
                }

                @Override
                public void onComplete()
                {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable throwable)
                {
                    latch.countDown();
                    fail("Should not encounter an error", throwable);
                }
            };
            InputStream inputStream = resourceInputStream("sstables/nb-1-big-TOC.txt");
            Buffer buffer = Okio.buffer(Okio.source(inputStream)).getBuffer();
            Okio.use(buffer, buffer1 -> {
                try
                {
                    return buffer1.writeAll(Okio.source(inputStream));
                }
                catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            });

            SidecarInstanceImpl sidecarInstance = RequestExecutorTest.newSidecarInstance(server);
            MockResponse response =
            new MockResponse().setResponseCode(OK.code())
                              .setHeader(HttpHeaderNames.CONTENT_TYPE.toString(),
                                         HttpHeaderValues.APPLICATION_OCTET_STREAM)
                              .setHeader(HttpHeaderNames.ACCEPT_RANGES.toString(), "bytes")
                              .setBody(buffer);
            server.enqueue(response);

            client.streamSSTableComponent(sidecarInstance,
                                          "cycling",
                                          "cyclist_name",
                                          "2023.04.12",
                                          "nb-203-big-Data.db",
                                          null,
                                          mockStreamConsumer);

            assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();

            RecordedRequest request = server.takeRequest();
            assertThat(request.getPath())
            .isEqualTo(ApiEndpointsV1.COMPONENTS_ROUTE
                       .replaceAll(ApiEndpointsV1.KEYSPACE_PATH_PARAM, "cycling")
                       .replaceAll(ApiEndpointsV1.TABLE_PATH_PARAM, "cyclist_name")
                       .replaceAll(ApiEndpointsV1.SNAPSHOT_PATH_PARAM, "2023.04.12")
                       .replaceAll(ApiEndpointsV1.COMPONENT_PATH_PARAM, "nb-203-big-Data.db"));
            assertThat(request.getHeader("User-Agent")).isEqualTo("cassandra-sidecar-test/0.0.1");
            assertThat(request.getHeader("range")).isNull();

            byte[] bytes = receivedBytes.stream()
                                        .collect(ByteArrayOutputStream::new,
                                                 (outputStream, src) -> outputStream.write(src, 0, src.length),
                                                 (outputStream, src) -> {
                                                 })
                                        .toByteArray();
            assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("Summary.db\n" +
                                                                            "TOC.txt\n" +
                                                                            "Statistics.db\n" +
                                                                            "Filter.db\n" +
                                                                            "Data.db\n" +
                                                                            "CRC.db\n" +
                                                                            "Digest.crc32\n" +
                                                                            "Index.db\n");
        }
    }

    @Test
    void testStreamSSTableComponentWithRange() throws Exception
    {
        try (MockWebServer server = new MockWebServer())
        {
            CountDownLatch latch = new CountDownLatch(1);
            List<byte[]> receivedBytes = new ArrayList<>();
            StreamConsumer mockStreamConsumer = new StreamConsumer()
            {
                @Override
                public void onRead(StreamBuffer buffer)
                {
                    assertThat(buffer.readableBytes()).isGreaterThan(0);
                    byte[] dst = new byte[buffer.readableBytes()];
                    buffer.copyBytes(0, dst, 0, buffer.readableBytes());
                    receivedBytes.add(dst);
                }

                @Override
                public void onComplete()
                {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable throwable)
                {
                    latch.countDown();
                    fail("Should not encounter an error", throwable);
                }
            };

            SidecarInstanceImpl sidecarInstance = RequestExecutorTest.newSidecarInstance(server);
            MockResponse response =
            new MockResponse().setResponseCode(PARTIAL_CONTENT.code())
                              .setHeader(HttpHeaderNames.CONTENT_TYPE.toString(),
                                         HttpHeaderValues.APPLICATION_OCTET_STREAM)
                              .setHeader(HttpHeaderNames.ACCEPT_RANGES.toString(), "bytes")
                              .setHeader(HttpHeaderNames.CONTENT_RANGE.toString(), "bytes 10-20/80")
                              .setBody("TOC.txt\nSt");
            server.enqueue(response);

            client.streamSSTableComponent(sidecarInstance,
                                          "cycling",
                                          "cyclist_name",
                                          "2023.04.12",
                                          "nb-203-big-Data.db",
                                          HttpRange.of(10, 20),
                                          mockStreamConsumer);

            assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();

            RecordedRequest request = server.takeRequest();
            assertThat(request.getPath())
            .isEqualTo(ApiEndpointsV1.COMPONENTS_ROUTE
                       .replaceAll(ApiEndpointsV1.KEYSPACE_PATH_PARAM, "cycling")
                       .replaceAll(ApiEndpointsV1.TABLE_PATH_PARAM, "cyclist_name")
                       .replaceAll(ApiEndpointsV1.SNAPSHOT_PATH_PARAM, "2023.04.12")
                       .replaceAll(ApiEndpointsV1.COMPONENT_PATH_PARAM, "nb-203-big-Data.db"));
            assertThat(request.getHeader("User-Agent")).isEqualTo("cassandra-sidecar-test/0.0.1");
            assertThat(request.getHeader("range")).isEqualTo("bytes=10-20");

            byte[] bytes = receivedBytes.stream()
                                        .collect(ByteArrayOutputStream::new,
                                                 (outputStream, src) -> outputStream.write(src, 0, src.length),
                                                 (outputStream, src) -> {
                                                 })
                                        .toByteArray();
            assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("TOC.txt\nSt");
        }
    }

    @Test
    void testStreamSSTableComponentFailsMidStream() throws Exception
    {
        try (MockWebServer server = new MockWebServer())
        {
            CountDownLatch latch = new CountDownLatch(1);
            List<byte[]> receivedBytes = new ArrayList<>();
            StreamConsumer mockStreamConsumer = new StreamConsumer()
            {
                @Override
                public void onRead(StreamBuffer buffer)
                {
                    assertThat(buffer.readableBytes()).isGreaterThan(0);
                    byte[] dst = new byte[buffer.readableBytes()];
                    buffer.copyBytes(0, dst, 0, buffer.readableBytes());
                    receivedBytes.add(dst);
                }

                @Override
                public void onComplete()
                {
                }

                @Override
                public void onError(Throwable throwable)
                {
                    latch.countDown();
                    assertThat(throwable).isNotNull();
                }
            };
            InputStream inputStream = resourceInputStream("sstables/nb-1-big-TOC.txt");
            Buffer buffer = Okio.buffer(Okio.source(inputStream)).getBuffer();
            Okio.use(buffer, buffer1 -> {
                try
                {
                    return buffer1.writeAll(Okio.source(inputStream));
                }
                catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            });

            SidecarInstanceImpl sidecarInstance = RequestExecutorTest.newSidecarInstance(server);
            MockResponse response =
            new MockResponse().setResponseCode(OK.code())
                              .setHeader(HttpHeaderNames.CONTENT_TYPE.toString(),
                                         HttpHeaderValues.APPLICATION_OCTET_STREAM)
                              .setHeader(HttpHeaderNames.ACCEPT_RANGES.toString(), "bytes")
                              .setBody(buffer)
                              .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY);
            server.enqueue(response);

            client.streamSSTableComponent(sidecarInstance,
                                          "cycling",
                                          "cyclist_name",
                                          "2023.04.12",
                                          "nb-203-big-Data.db",
                                          null,
                                          mockStreamConsumer);

            assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();

            RecordedRequest request = server.takeRequest();
            assertThat(request.getPath())
            .isEqualTo(ApiEndpointsV1.COMPONENTS_ROUTE
                       .replaceAll(ApiEndpointsV1.KEYSPACE_PATH_PARAM, "cycling")
                       .replaceAll(ApiEndpointsV1.TABLE_PATH_PARAM, "cyclist_name")
                       .replaceAll(ApiEndpointsV1.SNAPSHOT_PATH_PARAM, "2023.04.12")
                       .replaceAll(ApiEndpointsV1.COMPONENT_PATH_PARAM, "nb-203-big-Data.db"));
            assertThat(request.getHeader("User-Agent")).isEqualTo("cassandra-sidecar-test/0.0.1");
            assertThat(request.getHeader("range")).isNull();
            assertThat(receivedBytes).hasSizeGreaterThan(0);
        }
    }

    @Test
    void testStreamSSTableComponentWithRetries() throws Exception
    {
        try (MockWebServer server = new MockWebServer())
        {
            CountDownLatch latch = new CountDownLatch(1);
            List<byte[]> receivedBytes = new ArrayList<>();
            StreamConsumer mockStreamConsumer = new StreamConsumer()
            {
                @Override
                public void onRead(StreamBuffer buffer)
                {
                    assertThat(buffer.readableBytes()).isGreaterThan(0);
                    byte[] dst = new byte[buffer.readableBytes()];
                    buffer.copyBytes(0, dst, 0, buffer.readableBytes());
                    receivedBytes.add(dst);
                }

                @Override
                public void onComplete()
                {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable throwable)
                {
                }
            };

            SidecarInstanceImpl sidecarInstance = RequestExecutorTest.newSidecarInstance(server);
            MockResponse response =
            new MockResponse().setResponseCode(PARTIAL_CONTENT.code())
                              .setHeader(HttpHeaderNames.CONTENT_TYPE.toString(),
                                         HttpHeaderValues.APPLICATION_OCTET_STREAM)
                              .setHeader(HttpHeaderNames.ACCEPT_RANGES.toString(), "bytes")
                              .setHeader(HttpHeaderNames.CONTENT_RANGE.toString(), "bytes 10-20/80")
                              .setBody("TOC.txt\nSt");
            server.enqueue(new MockResponse().setResponseCode(INTERNAL_SERVER_ERROR.code())
                                             .setBody("{\"error\":\"some error\"}"));
            server.enqueue(new MockResponse().setResponseCode(INTERNAL_SERVER_ERROR.code())
                                             .setBody("{\"error\":\"some error\"}"));
            server.enqueue(response);

            client.streamSSTableComponent(sidecarInstance,
                                          "cycling",
                                          "cyclist_name",
                                          "2023.04.12",
                                          "nb-203-big-Data.db",
                                          HttpRange.of(10, 20),
                                          mockStreamConsumer);

            assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();

            server.takeRequest(); // first 500
            server.takeRequest(); // second 500
            RecordedRequest request3 = server.takeRequest();
            assertThat(request3.getPath())
            .isEqualTo(ApiEndpointsV1.COMPONENTS_ROUTE
                       .replaceAll(ApiEndpointsV1.KEYSPACE_PATH_PARAM, "cycling")
                       .replaceAll(ApiEndpointsV1.TABLE_PATH_PARAM, "cyclist_name")
                       .replaceAll(ApiEndpointsV1.SNAPSHOT_PATH_PARAM, "2023.04.12")
                       .replaceAll(ApiEndpointsV1.COMPONENT_PATH_PARAM, "nb-203-big-Data.db"));
            assertThat(request3.getHeader("User-Agent")).isEqualTo("cassandra-sidecar-test/0.0.1");
            assertThat(request3.getHeader("range")).isEqualTo("bytes=10-20");

            byte[] bytes = receivedBytes.stream()
                                        .collect(ByteArrayOutputStream::new,
                                                 (outputStream, src) -> outputStream.write(src, 0, src.length),
                                                 (outputStream, src) -> {
                                                 })
                                        .toByteArray();
            assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("TOC.txt\nSt");
        }
    }

    @Test
    void testFailsWithOneAttemptPerServer()
    {
        for (MockWebServer server : servers)
        {
            MockResponse response = new MockResponse().setResponseCode(INTERNAL_SERVER_ERROR.code())
                                                      .setBody("{\"error\":\"some error\"}");
            server.enqueue(response);
        }

        assertThatExceptionOfType(ExecutionException.class)
        .isThrownBy(() -> client.schema("cycling").get(30, TimeUnit.SECONDS))
        .withRootCauseInstanceOf(RetriesExhaustedException.class)
        .withMessageContaining("Unable to complete request '/api/v1/keyspaces/cycling/schema' after 4 attempts");
    }

    @Test
    void testProvidingCustomRetryPolicy() throws ExecutionException, InterruptedException, TimeoutException
    {
        String nodeSettingsAsString = "{\"partitioner\":\"test-partitioner\", \"releaseVersion\": \"4.0.0\"}";
        MockResponse response = new MockResponse().setResponseCode(ACCEPTED.code()).setBody(nodeSettingsAsString);
        enqueue(response);

        RequestContext requestContext = client.requestBuilder()
                                     .request(new NodeSettingsRequest())
                                     .retryPolicy(new RetryPolicy()
                                     {
                                         @Override
                                         public void onResponse(CompletableFuture<HttpResponse> responseFuture,
                                                                Request request,
                                                                HttpResponse response,
                                                                Throwable throwable,
                                                                int attempts,
                                                                boolean canRetryOnADifferentHost,
                                                                RetryAction retryAction)
                                         {
                                             if (response != null && response.statusCode() == ACCEPTED.code())
                                             {
                                                 responseFuture.complete(response);
                                             }
                                             else
                                             {
                                                 client.defaultRetryPolicy().onResponse(responseFuture,
                                                                                        request,
                                                                                        response,
                                                                                        throwable,
                                                                                        attempts,
                                                                                        canRetryOnADifferentHost,
                                                                                        retryAction);
                                             }
                                         }
                                     })
                                     .build();
        NodeSettings result = client.<NodeSettings>executeRequestAsync(requestContext).get(30, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.partitioner()).isEqualTo("test-partitioner");
        assertThat(result.releaseVersion()).isEqualTo("4.0.0");

        validateResponseServed(ApiEndpointsV1.NODE_SETTINGS_ROUTE);
    }

    private void enqueue(MockResponse response)
    {
        for (MockWebServer server : servers)
        {
            server.enqueue(response);
        }
    }

    private void validateResponseServed(String expectedEndpointPath) throws InterruptedException
    {
        for (MockWebServer server : servers)
        {
            if (server.getRequestCount() > 0)
            {
                assertThat(server.getRequestCount()).isEqualTo(1);
                RecordedRequest request = server.takeRequest();
                assertThat(request.getPath()).isEqualTo(expectedEndpointPath);
                return;
            }
        }
        fail("The request was not served by any of the provided servers");
    }

    private InputStream resourceInputStream(String name)
    {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(name);
        assertThat(inputStream).isNotNull();
        return inputStream;
    }

    private Path prepareFile(Path tempDirectory) throws IOException
    {
        Path fileToUpload = tempDirectory.resolve("nb-1-big-TOC.txt");
        try (InputStream inputStream = resourceInputStream("sstables/nb-1-big-TOC.txt"))
        {
            Files.copy(inputStream, fileToUpload, StandardCopyOption.REPLACE_EXISTING);
        }
        return fileToUpload;
    }
}

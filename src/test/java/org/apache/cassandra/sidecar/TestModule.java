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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import io.vertx.core.Vertx;
import io.vertx.core.file.FileSystem;
import org.apache.cassandra.sidecar.cluster.InstancesConfig;
import org.apache.cassandra.sidecar.cluster.InstancesConfigImpl;
import org.apache.cassandra.sidecar.cluster.instance.InstanceMetadata;
import org.apache.cassandra.sidecar.common.CassandraAdapterDelegate;
import org.apache.cassandra.sidecar.common.CassandraVersionProvider;
import org.apache.cassandra.sidecar.common.MockCassandraFactory;
import org.apache.cassandra.sidecar.common.NodeSettings;
import org.apache.cassandra.sidecar.common.TestValidationConfiguration;
import org.apache.cassandra.sidecar.common.utils.ValidationConfiguration;
import org.apache.cassandra.sidecar.config.CacheConfiguration;
import org.apache.cassandra.sidecar.config.WorkerPoolConfiguration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Provides the basic dependencies for unit tests.
 */
public class TestModule extends AbstractModule
{
    @Singleton
    @Provides
    public CassandraAdapterDelegate delegate()
    {
        return mock(CassandraAdapterDelegate.class);
    }

    @Provides
    @Singleton
    public Configuration configuration(InstancesConfig instancesConfig)
    {
        return abstractConfig(instancesConfig);
    }

    protected Configuration abstractConfig(InstancesConfig instancesConfig)
    {
        WorkerPoolConfiguration workPoolConf = new WorkerPoolConfiguration("test-pool", 10,
                                                                           30000);
        return new Configuration.Builder()
               .setInstancesConfig(instancesConfig)
               .setHost("127.0.0.1")
               .setPort(6475)
               .setHealthCheckFrequency(1000)
               .setSslEnabled(false)
               .setRateLimitStreamRequestsPerSecond(1)
               .setThrottleDelayInSeconds(5)
               .setThrottleTimeoutInSeconds(10)
               .setAllowableSkewInMinutes(60)
               .setRequestIdleTimeoutMillis(300_000)
               .setRequestTimeoutMillis(300_000L)
               .setConcurrentUploadsLimit(80)
               .setMinSpacePercentRequiredForUploads(0)
               .setSSTableImportCacheConfiguration(new CacheConfiguration(60_000, 100))
               .setServerWorkerPoolConfiguration(workPoolConf)
               .setServerInternalWorkerPoolConfiguration(workPoolConf)
               .build();
    }

    @Provides
    @Singleton
    public InstancesConfig instancesConfig()
    {
        return new InstancesConfigImpl(instancesMetas());
    }

    public List<InstanceMetadata> instancesMetas()
    {
        InstanceMetadata instance1 = mockInstance("localhost", 1, "src/test/resources/instance1/data", true);
        InstanceMetadata instance2 = mockInstance("localhost2", 2, "src/test/resources/instance2/data", false);
        InstanceMetadata instance3 = mockInstance("localhost3", 3, "src/test/resources/instance3/data", true);
        final List<InstanceMetadata> instanceMetas = new ArrayList<>();
        instanceMetas.add(instance1);
        instanceMetas.add(instance2);
        instanceMetas.add(instance3);
        return instanceMetas;
    }

    private InstanceMetadata mockInstance(String host, int id, String dataDir, boolean isUp)
    {
        InstanceMetadata instanceMeta = mock(InstanceMetadata.class);
        when(instanceMeta.id()).thenReturn(id);
        when(instanceMeta.host()).thenReturn(host);
        when(instanceMeta.port()).thenReturn(6475);
        when(instanceMeta.dataDirs()).thenReturn(Collections.singletonList(dataDir));

        CassandraAdapterDelegate delegate = mock(CassandraAdapterDelegate.class);
        if (isUp)
        {
            when(delegate.nodeSettings()).thenReturn(new NodeSettings("testVersion", "testPartitioner"));
        }
        when(delegate.isUp()).thenReturn(isUp);
        when(instanceMeta.delegate()).thenReturn(delegate);
        return instanceMeta;
    }

    @Provides
    @Singleton
    public FileSystem fileSystem(Vertx vertx)
    {
        return vertx.fileSystem();
    }

    /**
     * The Mock factory is used for testing purposes, enabling us to test all failures and possible results
     *
     * @return the {@link CassandraVersionProvider}
     */
    @Provides
    @Singleton
    public CassandraVersionProvider cassandraVersionProvider()
    {
        CassandraVersionProvider.Builder builder = new CassandraVersionProvider.Builder();
        builder.add(new MockCassandraFactory());
        return builder.build();
    }

    @Provides
    @Singleton
    public ValidationConfiguration validationConfiguration()
    {
        return new TestValidationConfiguration();
    }
}

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

package org.apache.cassandra.sidecar.common;

import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;

import com.google.common.collect.Sets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.util.Preconditions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;


/***
 * In order to support multiple versions of Cassandra in the Sidecar, we would like to avoid depending directly on
 * any Cassandra code.
 * Additionally, whe would like to avoid copy/pasting the entire MBean interface classes into the Sidecar.
 * This test exists to prove out some assumptions about using matching sub-interfaces (or even functional interfaces)
 * to make JMX calls. This particular call happens to match the signature of the `importNewSSTables` method on
 * StorageServiceProxy in C* 4.0.
 */
public class JmxClientTest
{

    private static final JMXServiceURL serviceURL;
    private static final String objectName = "org.apache.cassandra.jmx:type=ExtendedImport";
    public static final int PROXIES_TO_TEST = 10_000;
    private static StorageService importMBean;
    private static JMXConnectorServer jmxServer;
    private static MBeanServer mbs;
    private static Registry registry;

    @BeforeAll
    public static void setUp() throws Exception
    {
        System.setProperty("java.rmi.server.randomIds", "true");
        String passwordFile = Objects.requireNonNull(JmxClientTest.class
                                                     .getClassLoader()
                                                     .getResource("testJmxPassword.properties")).getPath();
        Map<String, String> env = new HashMap<>();
        env.put("jmx.remote.x.password.file", passwordFile);
        registry = LocateRegistry.createRegistry(9999);
        mbs = ManagementFactory.getPlatformMBeanServer();
        jmxServer = JMXConnectorServerFactory.newJMXConnectorServer(serviceURL, env, mbs);
        jmxServer.start();
        importMBean = new StorageService();
        mbs.registerMBean(importMBean, new ObjectName(objectName));
    }

    @AfterAll
    public static void tearDown() throws Exception
    {
        jmxServer.stop();
        final ObjectName name = new ObjectName(objectName);
        if (mbs.isRegistered(name))
        {
            mbs.unregisterMBean(name);
        }
        UnicastRemoteObject.unexportObject(registry, true);
        registry = null;
    }

    @BeforeEach
    public void setup()
    {
        importMBean.shouldSucceed = true;
    }

    @Test
    public void testCanCallMethodWithoutEntireInterface()
    {
        JmxClient client = new JmxClient(serviceURL, "controlRole", "password");
        List<String> result = client.proxy(Import.class, objectName)
                                    .importNewSSTables(Sets.newHashSet("foo", "bar"), true,
                                                       true, true, true, true,
                                                       true);
        assertThat(result.size()).isEqualTo(0);
    }

    @Test
    public void testCanCallMethodWithoutEntireInterfaceGetResults()
    {
        importMBean.shouldSucceed = false;
        JmxClient client = new JmxClient(serviceURL, "controlRole", "password");
        final HashSet<String> srcPaths = Sets.newHashSet("foo", "bar");
        final List<String> failedDirs = client.proxy(Import.class, objectName)
                                              .importNewSSTables(srcPaths, true,
                                                                 true, true, true, true,
                                                                 true);
        assertThat(failedDirs.size()).isEqualTo(2);
        assertThat(failedDirs.toArray()).isEqualTo(srcPaths.toArray());
    }

    @Test
    public void testCallWithoutCredentialsFails()
    {
        assertThatExceptionOfType(SecurityException.class)
        .isThrownBy(() ->
                    {
                        JmxClient client = new JmxClient(serviceURL);
                        client.proxy(Import.class, objectName)
                              .importNewSSTables(Sets.newHashSet("foo", "bar"),
                                                 true,
                                                 true,
                                                 true,
                                                 true,
                                                 true,
                                                 true);
                    });
    }

    @Test
    public void testDisconnectReconnect() throws Exception
    {
        JmxClient client = new JmxClient(serviceURL, "controlRole", "password");
        assertThat(client.isConnected()).isFalse();
        List<String> result = client.proxy(Import.class, objectName)
                                    .importNewSSTables(
                                    Sets.newHashSet("foo", "bar"), true, true, true,
                                    true, true,
                                    true);
        assertThat(client.isConnected()).isTrue();
        assertThat(result.size()).isEqualTo(0);

        tearDown();
        setUp();

        result = client.proxy(Import.class, objectName)
                       .importNewSSTables(
                       Sets.newHashSet("foo", "bar"), true, true, true,
                       true, true,
                       true);
        assertThat(result.size()).isEqualTo(0);
    }

    @Test
    public void testLotsOfProxies()
    {
        JmxClient client = new JmxClient(serviceURL, "controlRole", "password");
        for (int i = 0; i < PROXIES_TO_TEST; i++)
        {
            List<String> result = client.proxy(Import.class, objectName)
                                        .importNewSSTables(
                                        Sets.newHashSet("foo", "bar"), true, true, true,
                                        true, true,
                                        true);
            assertThat(result).isNotNull();
        }
    }

    /**
     * Simulates to C*'s `nodetool import` call
     */
    public interface Import
    {
        List<String> importNewSSTables(Set<String> srcPaths, boolean resetLevel, boolean clearRepaired,
                                       boolean verifySSTables, boolean verifyTokens, boolean invalidateCaches,
                                       boolean extendedVerify);
    }

    /**
     * Simulates the larger Storage Service MBean interface
     */
    public interface StorageServiceMBean
    {
        List<String> importNewSSTables(Set<String> srcPaths, boolean resetLevel, boolean clearRepaired,
                                       boolean verifySSTables, boolean verifyTokens, boolean invalidateCaches,
                                       boolean extendedVerify);

        void someOtherMethod(String helloString);
    }

    /**
     * An implementation of our mock StorageServiceMBean
     */
    public static class StorageService implements StorageServiceMBean
    {

        private static final Logger logger = Logger.getLogger(StorageService.class.getSimpleName());
        public boolean shouldSucceed = true;

        @Override
        public List<String> importNewSSTables(Set<String> srcPaths, boolean resetLevel, boolean clearRepaired,
                                              boolean verifySSTables, boolean verifyTokens,
                                              boolean invalidateCaches, boolean extendedVerify)
        {
            Preconditions.notNull(srcPaths, "Source Paths missing");
            if (shouldSucceed)
            {
                return Collections.emptyList();
            }
            return Arrays.asList(srcPaths.toArray(new String[0]));
        }

        @Override
        public void someOtherMethod(String helloString)
        {
            logger.info(helloString);
        }
    }

    static
    {
        try
        {
            serviceURL = new JMXServiceURL("service:jmx:rmi://localhost/jndi/rmi://localhost:9999/jmxrmi");
        }
        catch (MalformedURLException e)
        {
            throw new RuntimeException(e);
        }
    }
}

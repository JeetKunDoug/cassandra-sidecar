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

package org.apache.cassandra.sidecar.utils;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

/**
 * Utility class for SSL related operations
 */
public class SslUtils
{
    /**
     * Given the parameters, validate the keystore can be loaded and is usable
     *
     * @param keyStorePath     the path to the keystore
     * @param keystorePassword the password for the keystore
     * @throws KeyStoreException        when there is an error accessing the keystore
     * @throws NoSuchAlgorithmException when the keystore type algorithm is not available
     * @throws IOException              when an IO exception occurs
     * @throws CertificateException     when a problem was encountered with the certificate
     */
    public static void validateSslOpts(String keyStorePath, String keystorePassword) throws KeyStoreException,
                                                                                            NoSuchAlgorithmException,
                                                                                            IOException,
                                                                                            CertificateException
    {
        final KeyStore ks;

        if (keyStorePath.endsWith("p12"))
            ks = KeyStore.getInstance("PKCS12");
        else if (keyStorePath.endsWith("jks"))
            ks = KeyStore.getInstance("JKS");
        else
            throw new IllegalArgumentException("Unrecognized keystore format extension: "
                                               + keyStorePath.substring(keyStorePath.length() - 3));
        try (FileInputStream keystore = new FileInputStream(keyStorePath))
        {
            ks.load(keystore, keystorePassword.toCharArray());
        }
    }
}

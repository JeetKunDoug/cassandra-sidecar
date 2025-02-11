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
package org.apache.cassandra.sidecar.common.data;

import java.util.Objects;

/**
 * Contains the keyspace and table name in Cassandra
 */
public class QualifiedTableName
{
    private final String keyspace;
    private final String tableName;

    /**
     * Constructs a qualified name with the given {@code keyspace} and {@code tableName}
     *
     * @param keyspace  the keyspace in Cassandra
     * @param tableName the table name in Cassandra
     */
    public QualifiedTableName(String keyspace, String tableName)
    {
        this(keyspace, tableName, true);
    }

    /**
     * Constructs a qualified name with the given {@code keyspace} and {@code tableName}. When {@code required}
     * is {@code false}, allow constructing the object with {@code null} {@code keyspace}/{@code tableName}.
     *
     * @param keyspace  the keyspace in Cassandra
     * @param tableName the table name in Cassandra
     * @param required  true if keyspace and table name are required, false if {@code null} is allowed
     */
    public QualifiedTableName(String keyspace, String tableName, boolean required)
    {
        this.keyspace = !required && keyspace == null ? null : Objects.requireNonNull(keyspace,
                                                                                      "keyspace must not be null");
        this.tableName = !required && tableName == null ? null : Objects.requireNonNull(tableName,
                                                                                        "tableName must not be null");
    }

    /**
     * @return the keyspace in Cassandra
     */
    public String keyspace()
    {
        return keyspace;
    }

    /**
     * @return the table name in Cassandra
     */
    public String tableName()
    {
        return tableName;
    }

    /**
     * {@inheritDoc}
     */
    public String toString()
    {
        return keyspace + "." + tableName;
    }
}

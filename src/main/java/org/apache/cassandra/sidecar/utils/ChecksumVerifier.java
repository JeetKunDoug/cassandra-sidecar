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

import io.vertx.core.Future;

/**
 * Interface to verify integrity of SSTables uploaded.
 * <p>
 * Note: If checksum calculations of multiple files are happening at the same time, we would want to limit concurrent
 * checksum calculations. Since {@link ChecksumVerifier} is currently used only by upload handler, we are not
 * introducing another limit here. Concurrent uploads limit should limit concurrent checksum calculations as well.
 */
public interface ChecksumVerifier
{
    /**
     * @param checksum  expected checksum value
     * @param filePath  path to SSTable component
     * @return String   component path, if verification is a success, else a failed future is returned
     */
    Future<String> verify(String checksum, String filePath);
}

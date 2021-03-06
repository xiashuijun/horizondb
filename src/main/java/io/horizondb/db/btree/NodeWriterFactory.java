/**
 * Copyright 2013 Benjamin Lerer
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
package io.horizondb.db.btree;

import io.horizondb.io.files.FileDataOutput;

import java.io.IOException;

/**
 * Factory for <code>NodeWriter</code> instances.
 * 
 * @author Benjamin
 * 
 */
public interface NodeWriterFactory<K extends Comparable<K>, V> {

    /**
     * Creates a new writer instance that use the specified output to write to the file.
     * 
     * @param output the file output.
     * @return a new writer instance that use the specified output to write to the file.
     * @throws IOException if a problem occurs while creating a writer instance.
     */
    NodeWriter<K, V> newWriter(FileDataOutput output) throws IOException;
}

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
package io.horizondb.db;

import java.io.IOException;
import java.io.InputStream;

/**
 * Configuration loader.
 * 
 * @author Benjamin
 * 
 */
public interface ConfigurationLoader {

    /**
     * Loads the database configuration from the specified input stream.
     * 
     * @param input the input stream containing the configuration information.
     * @return the database configuration
     * @throws IOException if an I/O problem occurs while reading the input
     * @throws HorizonDBException if the configuration is invalid
     */
    Configuration loadConfigurationFrom(InputStream input) throws IOException, HorizonDBException;
    
    /**
     * Loads the database configuration from the specified file within the classpath.
     * 
     * @param filename the name of the configuration file.
     * @return the database configuration
     * @throws IOException if an I/O problem occurs while reading the input
     * @throws HorizonDBException if the configuration is invalid
     */
    Configuration loadConfigurationFromClasspath(String filename) throws IOException, HorizonDBException;
}

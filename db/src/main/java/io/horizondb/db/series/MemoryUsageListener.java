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
package io.horizondb.db.series;

/**
 * Listener called when the memory usage change.
 * 
 * @author Benjamin
 * 
 */
interface MemoryUsageListener {

    /**
     * Notification that the memory usage of the specified partition did change.
     * 
     * @param partition the partition that has changed its memory usage.
     * @param previousMemoryUsage the previous memory usage.
     * @param newMemoryUsage the new memory usage.
     */
    void memoryUsageChanged(TimeSeriesPartition partition, int previousMemoryUsage, int newMemoryUsage);
}

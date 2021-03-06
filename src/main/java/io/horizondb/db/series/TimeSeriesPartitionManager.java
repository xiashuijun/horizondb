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

import io.horizondb.db.Component;
import io.horizondb.db.HorizonDBException;
import io.horizondb.db.btree.KeyValueIterator;
import io.horizondb.model.schema.TimeSeriesDefinition;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import com.google.common.util.concurrent.ListenableFuture;

/**
 * @author Benjamin
 * 
 */
public interface TimeSeriesPartitionManager extends Component {

    /**
     * Saves the specified partition meta data.
     * 
     * @param id the partition ID
     * @param metaData the partition new meta data
     * @throws IOException if an I/O problem occurs
     * @throws ExecutionException if the commit log replay position cannot be retrieved
     * @throws InterruptedException if the thread is interrupted while retrieving the last replay position of the partition
     */
    void save(PartitionId id, TimeSeriesPartitionMetaData metaData) throws IOException, InterruptedException, ExecutionException;

    /**
     * Returns the partition with the specified ID from the specified time series for write access.
     * 
     * @param PartitionId partitionId the partition id
     * @param seriesDefinition the time series definition
     * @return the time series partition with the specified ID.
     * @throws IOException if an I/O problem occurs while retrieving the partition.
     * @throws HorizonDBException if a problem occurs.
     */
    TimeSeriesPartition getPartitionForWrite(PartitionId partitionId, TimeSeriesDefinition seriesDefinition) throws IOException,
                                                                                                            HorizonDBException;
    
    /**
     * Returns an iterator to iterate over the time series partition whose IDs range from {@code fromId}, inclusive, to
     * {@code toId}, inclusive.
     * 
     * @param fromId low end-point (inclusive) of the IDs in the returned iterator
     * @param toId high end-point (inclusive) of the IDs in the returned iterator
     * @return an iterator to iterate over the time series partition whose IDs range from {@code fromId}, inclusive, to
     * {@code toId}, exclusive.
     * @throws IOException if an I/O problem occurs.
     */
    KeyValueIterator<PartitionId, TimeSeriesPartition> getRangeForRead(PartitionId fromId,
                                                                       PartitionId toId,
                                                                       TimeSeriesDefinition definition) 
                                                                               throws IOException;
    
    /**
     * Flush the specified partition.
     * 
     * @param timeSeriesPartition the partition to flush.
     * @param listeners the listeners that need to be notified from the flush
     */
    void flush(TimeSeriesPartition timeSeriesPartition, FlushListener... listeners);

    /**
     * Force flush the specified partition.
     * 
     * @param timeSeriesPartition the partition to flush.
     * @param listeners the listeners that need to be notified from the flush
     */
    void forceFlush(TimeSeriesPartition timeSeriesPartition, FlushListener... listeners);
    
    /**
     * Force flush the specified partition if it contains non persisted data within the 
     * specified segment. 
     * 
     * @param id the segment id
     * @param timeSeriesPartition the partition to flush.
     * @param listeners the listeners that need to be notified from the flush
     */
    void forceFlush(long id, TimeSeriesPartition timeSeriesPartition, FlushListener... listeners);

    /**
     * Forces the flush of all the partitions that contains non persisted data within the 
     * specified segment.  
     * 
     * @param id the segment id
     * @return a listenable future
     * @throws InterruptedException if the thread is interrupted
     */
    ListenableFuture<Boolean> forceFlush(long id) throws InterruptedException;
}

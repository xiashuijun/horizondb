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

import io.horizondb.db.HorizonDBException;
import io.horizondb.db.OperationContext;
import io.horizondb.db.commitlog.ReplayPosition;
import io.horizondb.db.queries.Expression;
import io.horizondb.db.util.concurrent.FutureUtils;
import io.horizondb.io.ReadableBuffer;
import io.horizondb.model.Globals;
import io.horizondb.model.core.Field;
import io.horizondb.model.core.Record;
import io.horizondb.model.core.RecordIterator;
import io.horizondb.model.core.iterators.BinaryTimeSeriesRecordIterator;
import io.horizondb.model.schema.TimeSeriesDefinition;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.TimeZone;

import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;

/**
 * Represents a time series.
 * 
 * @author Benjamin
 * 
 */
public final class TimeSeries {

    /**
     * The database name.
     */
    private final String databaseName;
    
    /**
     * The time series definition
     */
    private final TimeSeriesDefinition definition;

    /**
     * The partition manager.
     */
    private final TimeSeriesPartitionManager partitionManager;

    /**
     * Creates a new <code>TimeSeries</code> instance.
     * 
     * @param databaseName the name of database to which belongs this time series 
     * @param definition the time series definition
     * @param partitionManager the partition manager 
     */
    public TimeSeries(String databaseName, 
                      TimeSeriesDefinition definition, 
                      TimeSeriesPartitionManager partitionManager) {

        this.databaseName = databaseName;
        this.partitionManager = partitionManager;
        this.definition = definition;
    }

    /**
     * Returns the time series definition.
     * @return the time series definition.
     */
    public TimeSeriesDefinition getDefinition() {

        return this.definition;
    }

    public void write(OperationContext context, Range<Field> partitionTimeRange, ReadableBuffer buffer) throws IOException,
                                                                                               HorizonDBException {

        PartitionId partitionId = toPartitionId(partitionTimeRange);

        TimeSeriesPartition partition = this.partitionManager.getPartitionForWrite(partitionId, this.definition);
            
        if (context.isReplay()) {
            
            final ReplayPosition currentReplayPosition = FutureUtils.safeGet(context.getFuture());
            final ReplayPosition partitionReplayPosition = FutureUtils.safeGet(partition.getFuture());
            
            if (!currentReplayPosition.isAfter(partitionReplayPosition)) {
                
                return;
            }
        }
        
        BinaryTimeSeriesRecordIterator iterator = new BinaryTimeSeriesRecordIterator(this.definition, buffer);
        
        partition.write(iterator, context.getFuture());
    }

    /**
     * Returns the records of this time series that match the specified expression.
     *  
     * @param expression the expression used to filter the data
     * @throws IOException if an I/O problem occurs
     * @throws HorizonDBException if another problem occurs
     */
    public RecordIterator read(Expression expression) throws IOException, HorizonDBException {

        Field prototype = this.definition.newField(Globals.TIMESTAMP_FIELD);
        TimeZone timezone = this.definition.getTimeZone();
        
        RangeSet<Field> timeRanges = expression.getTimestampRanges(prototype, timezone);
        Filter<Record> filter = expression.toFilter(this.definition);

        return read(timeRanges, filter);
    }
    
    /**
     * Returns the records of this time series that belong to the specified time ranges and are accepted by the 
     * specified filter.
     * 
     * @param timeRanges the time ranges for which the data must be read
     * @throws IOException if an I/O problem occurs
     * @throws HorizonDBException if another problem occurs
     */
    public RecordIterator read(RangeSet<Field> timeRanges, Filter<Record> filter) throws IOException, HorizonDBException {

        Range<Field> span = timeRanges.span();
        
        List<Range<Field>> ranges = this.definition.splitRange(span);
        
        return new PartitionRecordIterator(timeRanges, ranges, filter);
    }

    /**
     * Creates the partition ID associated to the specified time range.
     * 
     * @param range the partition time range
     * @return the partition ID associated to the specified time range.
     */
    private PartitionId toPartitionId(Range<Field> range) {
        return new PartitionId(this.databaseName, this.definition.getName(), range);
    }
    
    /**
     * <code>RecordIterator</code> used to read records over multiple partitions.
     */
    private final class PartitionRecordIterator implements RecordIterator {

        /**
         * The time ranges for which data has been requested. 
         */
        private final RangeSet<Field> timeRanges;
        
        /**
         * The filter used to filter data.
         */
        private final Filter<Record> filter;
        
        /**
         * The iterator over the partitions.
         */
        private final Iterator<Range<Field>> partitionIterator; 
        
        /**
         * The record iterator for the current partition been read.
         */
        private RecordIterator recordIterator;
                        
        /**
         * Creates a new <code>PartitionRecordIterator</code> to read records from the specified partitions.
         * 
         * @param timeRanges the time ranges for which data has been requested
         * @param filter the filter used to filter the returned data
         * @param partitionIterator the partitions.
         */
        public PartitionRecordIterator(RangeSet<Field> rangeSet,
                                       List<Range<Field>> partitionRanges,
                                       Filter<Record> filter) {
            
            this.timeRanges = rangeSet;
            this.filter = filter;
            this.partitionIterator = partitionRanges.iterator();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void close() throws IOException {
            
            closeRecordIteratorIfNeeded();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean hasNext() throws IOException {

            if (this.recordIterator != null && this.recordIterator.hasNext()) {
                return true;
            }
            
            closeRecordIteratorIfNeeded();
            
            while (this.partitionIterator.hasNext()) {
                
                Range<Field> range = getDefinition().getPartitionTimeRange(this.partitionIterator.next().lowerEndpoint());
                
                RangeSet<Field> subRangeSet = this.timeRanges.subRangeSet(range);
                
                if (!subRangeSet.isEmpty()) {
                    
                    try {
                        
                        PartitionId id = toPartitionId(range);
                        TimeSeriesPartition partition = 
                                TimeSeries.this.partitionManager.getPartitionForRead(id, getDefinition());     
                        this.recordIterator = partition.read(subRangeSet, this.filter);
                        
                        if (this.recordIterator.hasNext()) {                        
                            return true;
                        }    
                        
                    } catch (HorizonDBException e) {
                        throw new IllegalStateException(e);
                    }
                }    
            }    
            
            return false;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Record next() throws IOException {

            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            
            return this.recordIterator.next();
        }
        
        /**
         * Closes the record iterator if it is not <code>closed</code> yet.
         * 
         * @throws IOException if an I/O problem occurs.
         */
        private void closeRecordIteratorIfNeeded() throws IOException {
            if (this.recordIterator != null) {
                this.recordIterator.close();
            }
        }
    }
}

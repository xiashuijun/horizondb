/**
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

import io.horizondb.db.Configuration;
import io.horizondb.db.HorizonDBException;
import io.horizondb.db.commitlog.CommitLog;
import io.horizondb.db.commitlog.ReplayPosition;
import io.horizondb.model.core.DataBlock;
import io.horizondb.model.core.Field;
import io.horizondb.model.core.Filter;
import io.horizondb.model.core.Record;
import io.horizondb.model.core.ResourceIterator;
import io.horizondb.model.core.iterators.BinaryTimeSeriesRecordIterator;
import io.horizondb.model.core.iterators.FilteringRecordIterator;
import io.horizondb.model.schema.DatabaseDefinition;
import io.horizondb.model.schema.TimeSeriesDefinition;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.util.concurrent.ListenableFuture;

import static io.horizondb.io.files.FileUtils.printNumberOfBytes;
import static org.apache.commons.lang.Validate.notNull;

/**
 * A partition of a given time series.
 * 
 */
@ThreadSafe
public final class TimeSeriesPartition implements Comparable<TimeSeriesPartition>, TimeSeriesElement {

    /**
     * The logger.
     */
    private final Logger logger = LoggerFactory.getLogger(getClass());
    
    /**
     * The database configuration.
     */
    private final Configuration configuration;
    
    /**
     * The ID associated to this partition.
     */
    private final PartitionId id;
    
    /**
     * The database definition.
     */
    private final DatabaseDefinition databaseDefinition;

    /**
     * The partitions manager.
     */
    private final TimeSeriesPartitionManager manager;

    /**
     * The time series definition.
     */
    private final TimeSeriesDefinition definition;

    /**
     * The partition range.
     */
    private final Range<Field> timeRange;

    /**
     * Used to combat heap fragmentation.
     */
    @GuardedBy("this")
    private final SlabAllocator allocator;

    /**
     * The <code>TimeSeriesPartitionListener</code>s that must be notified when the memory usage or 
     * the first commit log segment containing non persisted data change..
     */
    private final List<TimeSeriesPartitionListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * The time series elements composing this partition.
     */
    private final AtomicReference<TimeSeriesElements> elements = new AtomicReference<>();

    /**
     * Creates a new <code>TimeSeriesPartition</code> for the specified time series.
     * 
     * @param manager the manager that created this time series partition
     * @param configuration the database configuration
     * @param databaseDefinition the database database definition
     * @param definition the time series definition
     * @param metadata the meta data of this partition
     * @throws IOException if an I/O problem occurs while creating this partition
     */
    public TimeSeriesPartition(TimeSeriesPartitionManager manager,
                               Configuration configuration,
                               DatabaseDefinition databaseDefinition,
                               TimeSeriesDefinition definition,
                               TimeSeriesPartitionMetaData metadata) throws IOException {

        notNull(manager, "the manager parameter must not be null.");
        notNull(configuration, "the configuration parameter must not be null.");
        notNull(databaseDefinition, "the databaseDefinition parameter must not be null.");
        notNull(definition, "the definition parameter must not be null.");
        notNull(metadata, "the metadata parameter must not be null.");

        this.configuration = configuration;
        this.manager = manager;
        this.timeRange = metadata.getRange();
        this.databaseDefinition = databaseDefinition;
        this.definition = definition;
        
        this.id = new PartitionId(this.databaseDefinition,
                                  this.definition,
                                  this.timeRange);
        
        this.allocator = new SlabAllocator(configuration.getMemTimeSeriesSize());

        TimeSeriesElement file = TimeSeriesFile.open(configuration, this.databaseDefinition, definition, metadata);

        this.elements.set(new TimeSeriesElements(configuration, definition, file));
    }

    /**
     * Returns the ID of this partition.
     * 
     * @return the ID of this partition.
     */
    public PartitionId getId() {

        return this.id;
    }

    /**
     * Writes the specified records in this partition.
     * 
     * @param block the block containing the records to write
     * @param future the commit log future
     * @throws IOException if an I/O problem occurs.
     * @throws HorizonDBException if the record set is invalid.
     * @throws InterruptedException if the commit log thread was interrupted
     */
    public synchronized void write(DataBlock block,
                                   ListenableFuture<ReplayPosition> future)throws IOException, HorizonDBException {

        this.logger.debug("writing records to partition {}", getId());

        TimeSeriesElements oldElements = this.elements.get();
        TimeSeriesElements newElements = oldElements.write(this.allocator, block, future);
 
        CommitLog.waitForCommitLogWriteIfNeeded(this.configuration, future);
        
        this.elements.set(newElements);

        notifyListenersMemoryUsageChanged(oldElements.getMemoryUsage(), newElements.getMemoryUsage());
        notifyListenersfirstSegmentContainingNonPersistedDataChanged(oldElements.getFirstSegmentContainingNonPersistedData(), 
                                                                     newElements.getFirstSegmentContainingNonPersistedData());
 
        MemTimeSeries memSeries = newElements.getLastMemTimeSeries();

        if (memSeries.isFull()) {

            this.logger.debug("a memTimeSeries of partition {} is full => triggering flush", getId());

            scheduleFlush();
        }
    }

    /**
     * Schedules the flush of this partition. 
     * 
     * @param listeners the flush listeners
     */
    void scheduleFlush(FlushListener... listeners) {
        this.manager.flush(this, listeners);
    }

    /**
     * Schedules the force flush of this partition. 
     * 
     * @param listeners the flush listeners
     */
    void scheduleForceFlush(FlushListener... listeners) {
        this.manager.forceFlush(this, listeners);
    }
    
    /**
     * Returns a <code>RecordIterator</code> containing the data from the specified time range.
     * 
     * @param rangeSet the time range for which the data must be returned
     * @param recordTypeFilter the filter used to filter the records by type
     * @param filter the filter used to filter the records being returned
     * @return a <code>RecordIterator</code> containing the data from the specified time range
     * @throws IOException if an I/O problem occurs while writing the data
     */
    public  ResourceIterator<Record> read(RangeSet<Field> rangeSet, 
                                                          Filter<String> recordTypeFilter, 
                                                          Filter<Record> filter) throws IOException {

        return new FilteringRecordIterator(this.definition,
                                           new BinaryTimeSeriesRecordIterator(this.definition, 
                                                                              iterator(rangeSet), 
                                                                              recordTypeFilter),
                                           filter);
    }

    /**
     * Adds the specified listener to the list of listeners
     * 
     * @param listener the listener to add.
     */
    public void addListener(TimeSeriesPartitionListener listener) {
        
        this.listeners.add(listener);
    }

    /**
     * Remove the specified listener from the list of listeners
     * 
     * @param listener the listener to remove.
     */
    public void removeListener(TimeSeriesPartitionListener listener) {

        this.listeners.remove(listener);
    }

    /**
     * Returns the memory usage of this partition.
     * 
     * @return the memory usage of this partition.
     */
    public int getMemoryUsage() {

        return this.elements.get().getMemoryUsage();
    }

    /**
     * Returns the ID of the first segment that contains non persisted data or <code>null</code> if all the data have been
     * flushed to disk.
     * 
     * @return the ID of the first segment that contains non persisted data or <code>null</code> if all the data have been
     * flushed to disk.
     */
    public Long getFirstSegmentContainingNonPersistedData() {

        return this.elements.get().getFirstSegmentContainingNonPersistedData();
    }
    
    /**
     * Flushes to the disk the <code>MemTimeSeries</code> that are full.
     * 
     * @throws IOException if an I/O problem occurs while flushing the data to the disk.
     * @throws InterruptedException if the thread has been interrupted.
     * @throws ExecutionException if the last replay position cannot be retrieved
     */
    public void flush() throws IOException, InterruptedException, ExecutionException {

        synchronized (this) {

            this.logger.debug("performing flush on the partition {}", getId());

            TimeSeriesElements oldElements = this.elements.get();
            TimeSeriesElements newElements = oldElements.flush();

            if (oldElements == newElements) {

                this.logger.debug("no memTimeSeries had to be flushed for partition {}", getId());

                return;
            }

            this.manager.save(getId(), getMetaData(newElements.getFile()));  
            
            this.elements.set(newElements);
            
            notifyListenersMemoryUsageChanged(oldElements.getMemoryUsage(), newElements.getMemoryUsage());
            notifyListenersfirstSegmentContainingNonPersistedDataChanged(oldElements.getFirstSegmentContainingNonPersistedData(), 
                                                                         newElements.getFirstSegmentContainingNonPersistedData());
        }
    }

    /**
     * Flushes to the disk all <code>MemTimeSeries</code>.
     * 
     * @throws IOException if an I/O problem occurs while flushing the data to the disk.
     * @throws InterruptedException if the thread has been interrupted.
     * @throws ExecutionException if the last replay position cannot be retrieved
     */
    public void forceFlush() throws IOException, InterruptedException, ExecutionException {

        synchronized (this) {

            TimeSeriesElements oldElements = this.elements.get();

            TimeSeriesElements newElements = oldElements.forceFlush();

            if (oldElements == newElements) {
                return;
            }

            this.manager.save(getId(), getMetaData(newElements.getFile()));
            
            this.elements.set(newElements);
            this.allocator.release();

            notifyListenersMemoryUsageChanged(oldElements.getMemoryUsage(), newElements.getMemoryUsage());
            notifyListenersfirstSegmentContainingNonPersistedDataChanged(oldElements.getFirstSegmentContainingNonPersistedData(), 
                                                                         newElements.getFirstSegmentContainingNonPersistedData());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ListenableFuture<ReplayPosition> getFuture() {

        TimeSeriesElements elementList = this.elements.get();

        return elementList.getLast().getFuture();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ResourceIterator<DataBlock> iterator() throws IOException {
        TimeSeriesElements elementList = this.elements.get();
        return elementList.iterator();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ResourceIterator<DataBlock> iterator(RangeSet<Field> rangeSet) throws IOException {
        TimeSeriesElements elementList = this.elements.get();
        return elementList.iterator(rangeSet);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compareTo(TimeSeriesPartition other) {
       
        return this.id.compareTo(other.id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE).append("id", this.id)
                                                                          .toString();
    }
    
    /**
     * Returns the meta data from this partition.
     * 
     * @return the meta data of this partition.
     * @throws ExecutionException if a problem occurred when writing the data to the commit log
     * @throws InterruptedException if the thread was interrupted
     */
    public TimeSeriesPartitionMetaData getMetaData() throws InterruptedException, ExecutionException {

        TimeSeriesFile file = this.elements.get().getFile();
        
        return TimeSeriesPartitionMetaData.newBuilder(this.timeRange)
                                          .fileSize(file.size())
                                          .blockPositions(file.getBlockPositions())
                                          .replayPosition(file.getFuture().get())
                                          .build();
    }
    
    /**
     * Notifies the listeners that the memory usage has changed.
     * 
     * @param previousMemoryUsage the previous memory usage
     * @param newMemoryUsage the new memory usage
     */
    private void notifyListenersMemoryUsageChanged(int previousMemoryUsage, int newMemoryUsage) {

        if (previousMemoryUsage == newMemoryUsage) {

            return;
        }

        this.logger.debug("memory usage for partition {} changed (previous = {}, new = {})", new Object[] { getId(),
                printNumberOfBytes(previousMemoryUsage), printNumberOfBytes(newMemoryUsage) });

        for (int i = 0, m = this.listeners.size(); i < m; i++) {

            this.listeners.get(i).memoryUsageChanged(this, previousMemoryUsage, newMemoryUsage);
        }
    }
    
    /**
     * Notifies the listeners that the first segment containing non persisted data changed.
     * 
     * @param previousSegment the previous first segment containing non persisted data changed
     * @param newSegment the new first segment containing non persisted data changed
     */
    private void notifyListenersfirstSegmentContainingNonPersistedDataChanged(Long previousSegment, Long newSegment) {

        if (previousSegment != null && previousSegment.equals(newSegment)) {

            return;
        }

        for (int i = 0, m = this.listeners.size(); i < m; i++) {

            this.listeners.get(i).firstSegmentContainingNonPersistedDataChanged(this, previousSegment, newSegment);
        }
    } 
    
    /**
     * Returns the meta data from the specified file.
     * 
     * @param file the time series file
     * @return the meta data of this partition.
     * @throws ExecutionException if a problem occurred when writing the data to the commit log
     * @throws InterruptedException if the thread was interrupted
     */
    private TimeSeriesPartitionMetaData getMetaData(TimeSeriesFile file) throws InterruptedException, ExecutionException {
        return TimeSeriesPartitionMetaData.newBuilder(this.timeRange)
                                          .fileSize(file.size())
                                          .blockPositions(file.getBlockPositions())
                                          .replayPosition(file.getFuture().get())
                                          .build();
    }
}

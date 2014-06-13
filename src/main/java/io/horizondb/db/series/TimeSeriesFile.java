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

import io.horizondb.db.Configuration;
import io.horizondb.db.commitlog.ReplayPosition;
import io.horizondb.io.files.RandomAccessDataFile;
import io.horizondb.io.files.SeekableFileDataInput;
import io.horizondb.io.files.SeekableFileDataInputs;
import io.horizondb.io.files.SeekableFileDataOutput;
import io.horizondb.model.core.Field;
import io.horizondb.model.schema.TimeSeriesDefinition;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Range;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * File containing the time series data.
 * 
 * @author Benjamin
 * 
 */
final class TimeSeriesFile implements Closeable, TimeSeriesElement {

    /**
     * The logger.
     */
    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * The file meta data.
     */
    private final FileMetaData metadata;

    /**
     * The underlying file.
     */
    private final RandomAccessDataFile file;

    /**
     * The expected file size.
     */
    private final long fileSize;

    /**
     * The future returning the replay position of the data on disk.
     */
    private final ListenableFuture<ReplayPosition> future;

    /**
     * Opens the time series file.
     * 
     * @param configuration the database configuration
     * @param databaseName the database name
     * @param definition the time series definition
     * @param partitionMetadata the partition meta data
     * @return the time series file.
     * @throws IOException if an I/O problem occurs while opening the file.
     */
    public static TimeSeriesFile open(Configuration configuration,
                                      String databaseName,
                                      TimeSeriesDefinition definition,
                                      TimeSeriesPartitionMetaData partitionMetadata) throws IOException {

        Path path = getFilePath(configuration, databaseName, definition, partitionMetadata);
        
        RandomAccessDataFile file = RandomAccessDataFile.open(path, false, partitionMetadata.getFileSize());

        FileMetaData fileMetaData;

        if (file.exists() && file.size() != 0) {

            try (SeekableFileDataInput input = file.newInput()) {
                fileMetaData = FileMetaData.parseFrom(input);
            }

        } else {

            fileMetaData = new FileMetaData(databaseName,
                                            definition.getName(),
                                            partitionMetadata.getRange());
        }

        return new TimeSeriesFile(fileMetaData,
                                  file,
                                  partitionMetadata.getFileSize(),
                                  Futures.immediateFuture(partitionMetadata.getReplayPosition()));
    }

    /**
     * Returns the file size.
     * 
     * @return the file size.
     */
    public long size() {

        return this.fileSize;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SeekableFileDataInput newInput() throws IOException {

        if (this.fileSize == 0) {

            return SeekableFileDataInputs.empty();
        }

        return SeekableFileDataInputs.truncate(this.file.newInput(), FileMetaData.METADATA_LENGTH, this.fileSize
                - FileMetaData.METADATA_LENGTH);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ListenableFuture<ReplayPosition> getFuture() {
        return this.future;
    }

    /**
     * Appends the content of the specified <code>memTimeSeries</code> to this file.
     * 
     * @param memTimeSeriesList the set of time series that need to be written to the disk.
     * @param
     * @throws IOException if a problem occurs while writing to the disk.
     * @throws InterruptedException if the tread has been interrupted
     */
    public TimeSeriesFile append(List<TimeSeriesElement> memTimeSeriesList) throws IOException, InterruptedException {

        this.logger.debug("appending " + memTimeSeriesList.size() + " memTimeSeries to file: " + getPath()
                + " at position " + this.fileSize);

        ListenableFuture<ReplayPosition> future = null;

        try (SeekableFileDataOutput output = this.file.getOutput()) {

            output.seek(this.fileSize);

            if (this.fileSize == 0) {

                output.writeObject(this.metadata);
            }

            for (int i = 0, m = memTimeSeriesList.size(); i < m; i++) {

                TimeSeriesElement memTimeSeries = memTimeSeriesList.get(i);

                ((MemTimeSeries) memTimeSeries).writeTo(output);
                
                future = memTimeSeries.getFuture();
            }

            output.flush();
        }

        return new TimeSeriesFile(this.metadata, this.file, this.file.size(), future);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        this.file.close();
    }

    /**
     * Returns the file path.
     * 
     * @return the file path.
     */
    public Path getPath() {
        return this.file.getPath();
    }

    /**
     * Creates the time series file.
     * 
     * @param metadata the file meta data.
     * @param file the underlying file.
     * @param size the expected size of the file.
     * @param future the future returning the replay position of the last record written to the disk.
     * @throws IOException if an I/O problem occurs.
     */
    private TimeSeriesFile(FileMetaData metadata, 
                           RandomAccessDataFile file, 
                           long size, 
                           ListenableFuture<ReplayPosition> future) 
                                   throws IOException {

        this.metadata = metadata;
        this.file = file;
        this.fileSize = size;
        this.future = future;
    }

    /**
     * Returns the path to the data file.
     * 
     * @param configuration the database configuration
     * @param databaseName the database name
     * @param definition the time series definition
     * @param partitionMetadata the partition meta data
     * @return the path to the data file
     */
    private static Path getFilePath(Configuration configuration,
                                    String databaseName,
                                    TimeSeriesDefinition definition,
                                    TimeSeriesPartitionMetaData partitionMetadata) {

        Path dataDirectory = configuration.getDataDirectory();
        Path databaseDirectory = dataDirectory.resolve(databaseName);

        return databaseDirectory.resolve(filename(definition, partitionMetadata));
    }

    /**
     * Returns the filename of the data file associated to this partition.
     * 
     * @param definition the time series definition
     * @param partitionMetadata the partition meta data
     * @return the filename of the data file associated to this partition.
     */
    private static String filename(TimeSeriesDefinition definition, TimeSeriesPartitionMetaData partitionMetadata) {

        Range<Field> range = partitionMetadata.getRange();
        
        return new StringBuilder().append(definition.getName())
                                  .append('-')
                                  .append(range.lowerEndpoint().getTimestampInMillis())
                                  .append(".ts")
                                  .toString();
    }
}

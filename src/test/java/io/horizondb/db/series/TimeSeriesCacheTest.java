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
import io.horizondb.db.HorizonDBException;
import io.horizondb.io.files.FileUtils;
import io.horizondb.model.ErrorCodes;
import io.horizondb.model.schema.DatabaseDefinition;
import io.horizondb.model.schema.RecordTypeDefinition;
import io.horizondb.model.schema.TimeSeriesDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.google.common.cache.CacheStats;

import static org.junit.Assert.assertEquals;

/**
 * @author Benjamin
 * 
 */
public class TimeSeriesCacheTest {

    private Path testDirectory;

    private Configuration configuration;

    private TimeSeriesPartitionManager partitionManager;

    @Before
    public void setUp() throws IOException {

        this.testDirectory = Files.createTempDirectory("test");

        this.configuration = Configuration.newBuilder().dataDirectory(this.testDirectory.resolve("data")).build();

        this.partitionManager = EasyMock.createNiceMock(TimeSeriesPartitionManager.class);
    }

    @After
    public void tearDown() throws IOException {

        FileUtils.forceDelete(this.testDirectory);

        this.partitionManager = null;
        this.configuration = null;
        this.testDirectory = null;
    }

    @Test
    public void testCreateTimeSeries() throws IOException, HorizonDBException, InterruptedException {

        TimeSeriesManager manager = EasyMock.createMock(TimeSeriesManager.class);

        RecordTypeDefinition quote = RecordTypeDefinition.newBuilder("Quote")
                                                         .addDecimalField("bestBid")
                                                         .addDecimalField("bestAsk")
                                                         .addIntegerField("bidVolume")
                                                         .addIntegerField("askVolume")
                                                         .build();

        String databaseName = "test";
        DatabaseDefinition databaseDefinition = new DatabaseDefinition(databaseName);

        TimeSeriesDefinition definition = databaseDefinition.newTimeSeriesDefinitionBuilder("DAX")
                                                            .timeUnit(TimeUnit.NANOSECONDS)
                                                            .addRecordType(quote)
                                                            .build();

        manager.start();
        manager.createTimeSeries(databaseDefinition, definition, true);
        TimeSeries series = new TimeSeries(databaseDefinition, definition, this.partitionManager);
        EasyMock.expect(manager.getTimeSeries(new TimeSeriesId(databaseDefinition, "dax"))).andReturn(series);
        manager.shutdown();

        EasyMock.replay(manager);

        TimeSeriesManagerCache cache = new TimeSeriesManagerCache(this.configuration, manager);
        cache.start();

        cache.createTimeSeries(databaseDefinition, definition, true);

        TimeSeries firstCall = cache.getTimeSeries(databaseDefinition, "DAX");

        TimeSeries secondCall = cache.getTimeSeries(new TimeSeriesId(databaseDefinition, "DAX"));

        assertEquals(firstCall, secondCall);

        TimeSeries thirdCall = cache.getTimeSeries(databaseDefinition, "DAX");

        assertEquals(firstCall, thirdCall);

        CacheStats stats = cache.stats();

        assertEquals(1, stats.loadCount());
        assertEquals(1, stats.missCount());
        assertEquals(2, stats.hitCount());

        assertEquals(1, cache.size());

        cache.shutdown();

        EasyMock.verify(manager);
    }

    @Test
    public void testGetTimeSeriesWithUnknownTimeSeries() throws IOException, HorizonDBException, InterruptedException {

        TimeSeriesManager manager = EasyMock.createMock(TimeSeriesManager.class);

        RecordTypeDefinition quote = RecordTypeDefinition.newBuilder("Quote")
                                                         .addDecimalField("bestBid")
                                                         .addDecimalField("bestAsk")
                                                         .addIntegerField("bidVolume")
                                                         .addIntegerField("askVolume")
                                                         .build();

        final String databaseName = "test";
        DatabaseDefinition databaseDefinition = new DatabaseDefinition(databaseName);

        TimeSeriesDefinition definition = databaseDefinition.newTimeSeriesDefinitionBuilder("DAX")
                                                            .timeUnit(TimeUnit.NANOSECONDS)
                                                            .addRecordType(quote)
                                                            .build();

        EasyMock.expect(manager.getTimeSeries(new TimeSeriesId(databaseDefinition, "dax")))
                .andThrow(new HorizonDBException(ErrorCodes.UNKNOWN_TIMESERIES, "boom"));

        manager.start();
        manager.createTimeSeries(databaseDefinition, definition, true);
        TimeSeries series = new TimeSeries(databaseDefinition, definition, this.partitionManager);
        EasyMock.expect(manager.getTimeSeries(new TimeSeriesId(databaseDefinition, "dax"))).andReturn(series);
        manager.shutdown();

        EasyMock.replay(manager);

        TimeSeriesManagerCache cache = new TimeSeriesManagerCache(this.configuration, manager);
        cache.start();

        try {

            cache.getTimeSeries(databaseDefinition, "DAX");
            Assert.fail();

        } catch (HorizonDBException e) {

            Assert.assertEquals(ErrorCodes.UNKNOWN_TIMESERIES, e.getCode());
        }

        cache.createTimeSeries(databaseDefinition, definition, true);

        cache.getTimeSeries(databaseDefinition, "DAX");

        CacheStats stats = cache.stats();

        assertEquals(2, stats.loadCount());
        assertEquals(1, stats.loadExceptionCount());
        assertEquals(2, stats.missCount());
        assertEquals(0, stats.hitCount());

        assertEquals(1, cache.size());

        cache.shutdown();

        EasyMock.verify(manager);
    }

    @Test
    public void testCreateTimeSeriesWithExistingTimeSeries() throws IOException,
                                                            HorizonDBException,
                                                            InterruptedException {

        TimeSeriesManager manager = EasyMock.createMock(TimeSeriesManager.class);

        RecordTypeDefinition quote = RecordTypeDefinition.newBuilder("Quote")
                                                         .addDecimalField("bestBid")
                                                         .addDecimalField("bestAsk")
                                                         .addIntegerField("bidVolume")
                                                         .addIntegerField("askVolume")
                                                         .build();

        DatabaseDefinition databaseDefinition = new DatabaseDefinition("test");

        TimeSeriesDefinition definition = databaseDefinition.newTimeSeriesDefinitionBuilder("DAX")
                                                            .timeUnit(TimeUnit.NANOSECONDS)
                                                            .addRecordType(quote)
                                                            .build();
        manager.start();
        manager.createTimeSeries(databaseDefinition, definition, true);
        manager.createTimeSeries(databaseDefinition, definition, true);
        EasyMock.expectLastCall().andThrow(new HorizonDBException(ErrorCodes.DUPLICATE_TIMESERIES, "boom"));
        manager.shutdown();

        EasyMock.replay(manager);

        TimeSeriesManagerCache cache = new TimeSeriesManagerCache(this.configuration, manager);
        cache.start();

        try {

            cache.createTimeSeries(databaseDefinition, definition, true);
            cache.createTimeSeries(databaseDefinition, definition, true);
            Assert.fail();

        } catch (HorizonDBException e) {

            Assert.assertEquals(ErrorCodes.DUPLICATE_TIMESERIES, e.getCode());
        }
        cache.shutdown();

        EasyMock.verify(manager);
    }

}

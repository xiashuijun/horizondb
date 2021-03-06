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

import io.horizondb.io.Buffer;
import io.horizondb.io.buffers.Buffers;
import io.horizondb.io.checksum.ChecksumMismatchException;
import io.horizondb.model.core.Field;

import java.io.IOException;

import org.junit.Test;

import com.google.common.collect.Range;

import static io.horizondb.model.schema.FieldType.MILLISECONDS_TIMESTAMP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author Benjamin
 * 
 */
public class FileMetaDataTest {

    @Test
    public void testParseFrom() throws IOException {

        Range<Field> range = MILLISECONDS_TIMESTAMP.range("'2013-11-26'", "'2013-11-27'");

        FileMetaData metadata = new FileMetaData("test", "test", range);

        Buffer buffer = Buffers.allocate(metadata.computeSerializedSize());
        metadata.writeTo(buffer);

        FileMetaData result = FileMetaData.parseFrom(buffer);

        assertEquals(metadata, result);
    }

    @Test
    public void testParseFromWithInvalidCrc() throws IOException {

        Range<Field> range = MILLISECONDS_TIMESTAMP.range("'2013-11-26'", "'2013-11-27'");

        FileMetaData metadata = new FileMetaData("test", "test", range);

        Buffer buffer = Buffers.allocate(metadata.computeSerializedSize());
        metadata.writeTo(buffer);

        buffer.writerIndex(buffer.writerIndex() - 8).writeLong(1); // Modify the
                                                                   // CRC

        try {
            FileMetaData.parseFrom(buffer);
            fail();
        } catch (ChecksumMismatchException e) {
            assertTrue(true);
        }
    }

}

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

import io.horizondb.db.HorizonDBException;
import io.horizondb.io.BufferAllocator;
import io.horizondb.io.compression.CompressionType;
import io.horizondb.io.compression.Compressor;
import io.horizondb.io.files.CompositeSeekableFileDataInput;
import io.horizondb.io.files.SeekableFileDataInput;
import io.horizondb.io.files.SeekableFileDataInputs;
import io.horizondb.io.files.SeekableFileDataOutput;
import io.horizondb.model.core.Field;
import io.horizondb.model.core.Record;
import io.horizondb.model.core.records.TimeSeriesRecord;
import io.horizondb.model.schema.BlockPosition;
import io.horizondb.model.schema.TimeSeriesDefinition;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.annotation.concurrent.Immutable;

import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;

/**
 * A set of data blocks.
 * 
 * @author Benjamin
 *
 */
@Immutable
final class DataBlocks {

    /**
     * The time series definition.
     */
    private final TimeSeriesDefinition definition;
    
    /**
     * The blocks
     */
    private final LinkedList<DataBlock> blocks;
      
    /**
     * Creates a new empty <code>DataBlocks</code> instance.
     */
    public DataBlocks(TimeSeriesDefinition definition) {
        
        this(definition, new LinkedList<DataBlock>());
    }

    /**
     * Returns a new <code>SeekableFileDataInput</code> instance to read the content of the data blocks.
     * 
     * @return a new <code>SeekableFileDataInput</code> instance to read the content of the data blocks
     * @throws IOException if an I/O problem occurs
     */
    public SeekableFileDataInput newInput(RangeSet<Field> rangeSet) throws IOException {
        
        if (this.blocks.isEmpty()) {
            
            return SeekableFileDataInputs.empty();
        }
        
        if (this.blocks.size() == 1) {
                        
            DataBlock first = this.blocks.getFirst();
            
            if (rangeSet.subRangeSet(first.getRange()).isEmpty()) {
                return SeekableFileDataInputs.empty();
            }
            
            return first.newInput();
        }
        
        CompositeSeekableFileDataInput composite = new CompositeSeekableFileDataInput();

        for (int i = 0, m = this.blocks.size(); i < m; i++) {
            
            DataBlock block = this.blocks.get(i);
            
            if (!rangeSet.subRangeSet(block.getRange()).isEmpty()) {
                composite.add(block.newInput());
            }
        }

        return composite;
    }

    /**
     * Returns the total size of the blocks (including headers).
     * 
     * @return the total size of the blocks (including headers)
     */
    public int size() {
        
        int size = 0;
        
        for (int i = 0, m = this.blocks.size(); i < m; i++) {
            size += this.blocks.get(i).size();
        }
        
        return size;
    }

    /**
     * Returns the number of blocks.
     * @return the number of blocks.
     */
    public int getNumberOfBlocks() {
        return this.blocks.size();
    }
    
    /**
     * Writes the specified records to those blocks.
     * 
     * @param allocator the buffer allocator.
     * @param lastRecords the last records of each types
     * @param records the records to write
     * @return a new <code>DataBlocks</code>
     * @throws HorizonDBException if a problem occurs
     * @throws IOException if an I/O problem occurs
     */
    public DataBlocks write(BufferAllocator allocator, TimeSeriesRecord[] lastRecords, List<? extends Record> records) throws IOException, HorizonDBException {
        
        LinkedList<DataBlock> newBlocks = new LinkedList<>(this.blocks);
        
        if (newBlocks.isEmpty() || newBlocks.getLast().isFull()) {
            
            newBlocks.add(new DataBlock(this.definition));
            Arrays.fill(lastRecords, null);
        } 
        
        DataBlock last = newBlocks.removeLast();
        
        DataBlock newLast = last.write(allocator, lastRecords, records);
        
        newBlocks.addLast(newLast);
        
        DataBlocks dataBlocks = new DataBlocks(this.definition, newBlocks);
        
        return dataBlocks;
    }
    
    /**
     * Writes to the specified output the data blocks.
     * 
     * @param blockPositions the collecting parameter for the block positions
     * @param output the output to write to
     * @throws IOException if an I/O problem occurs. 
     */
    public void writeTo(Map<Range<Field>, BlockPosition> blockPositions, SeekableFileDataOutput output) throws IOException {
        
        CompressionType compressionType = this.definition.getCompressionType();
        Compressor compressor = compressionType.newCompressor();
        
        long position = output.getPosition();
        
        for (int i = 0, m = this.blocks.size(); i < m; i++) {
            DataBlock block = this.blocks.get(i);
            block.writeTo(compressor, output); 
            long newPosition = output.getPosition();
            int length = (int) (newPosition - position);
            BlockPosition blockPosition = new BlockPosition(position, length);
            blockPositions.put(block.getRange(), blockPosition);
            position = output.getPosition();
        }
    }
    
    /**
     * Creates a new <code>DataBlocks</code> instance that contains the specified blocks.
     * 
     * @param definition the time series definition
     * @param blocks the data blocks
     */
    private DataBlocks(TimeSeriesDefinition definition, LinkedList<DataBlock> blocks) {
        
        this.definition = definition;
        this.blocks = blocks;
    }
}

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
package io.horizondb.db.operations;

import io.horizondb.db.HorizonDBException;
import io.horizondb.db.Operation;
import io.horizondb.db.OperationContext;
import io.horizondb.db.databases.Database;
import io.horizondb.db.series.TimeSeries;
import io.horizondb.model.core.records.TimeSeriesRecord;
import io.horizondb.model.protocol.InsertPayload;
import io.horizondb.model.protocol.Msg;
import io.horizondb.model.protocol.MsgHeader;
import io.horizondb.model.protocol.Msgs;
import io.horizondb.model.protocol.OpCode;
import io.horizondb.model.schema.TimeSeriesDefinition;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * <code>Operation</code> that handle <code>INSERT</code> operations.
 * 
 * @author Benjamin
 */
final class InsertOperation implements Operation {

    /**
     * {@inheritDoc}
     */
    @Override
    public Object perform(OperationContext context, Msg<?> request) throws IOException, HorizonDBException {

        InsertPayload payload = Msgs.getPayload(request);

        Database database = context.getDatabaseManager().getDatabase(payload.getDatabase());

        TimeSeries series = database.getTimeSeries(payload.getSeries());

        TimeSeriesRecord record = newRecord(series.getDefinition(), payload);
        
        series.write(Collections.singletonList(record), context.getFuture(), context.isReplay());
        
        return Msg.emptyMsg(MsgHeader.newResponseHeader(request.getHeader(), OpCode.NOOP, 0, 0));
    }

    /**
     * Creates a new record from the specified payload.
     * 
     * @param definition the time series definition
     * @param payload the payload
     * @return the new record
     */
    private static TimeSeriesRecord newRecord(TimeSeriesDefinition definition, InsertPayload payload) {
        
        int recordIndex = definition.getRecordTypeIndex(payload.getRecordType());
        TimeSeriesRecord record = definition.newRecord(recordIndex);
        
        List<String> fieldNames = payload.getFieldNames();
        List<String> fieldValues = payload.getFieldValues();
        
        if (fieldNames.isEmpty()) {
            
            for (int i = 0, m = fieldValues.size();  i < m ; i++) {
                                 
                record.getField(i).setValueFromString(definition.getTimeZone(), fieldValues.get(i));
            }
            
        } else {
        
            for (int i = 0, m = fieldNames.size();  i < m ; i++) {
                
                String fieldName = fieldNames.get(i);
                int fieldIndex = definition.getFieldIndex(recordIndex, fieldName);
                 
                record.getField(fieldIndex).setValueFromString(definition.getTimeZone(), fieldValues.get(i));
            }
        }
        return record;
    }
}
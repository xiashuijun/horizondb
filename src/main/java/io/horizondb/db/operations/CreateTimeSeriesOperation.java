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
import io.horizondb.db.databases.DatabaseManager;
import io.horizondb.model.protocol.CreateTimeSeriesPayload;
import io.horizondb.model.protocol.Msg;
import io.horizondb.model.protocol.Msgs;

import java.io.IOException;

/**
 * @author Benjamin
 * 
 */
final class CreateTimeSeriesOperation implements Operation {

    /**
     * {@inheritDoc}
     */
    @Override
    public Object perform(OperationContext context, Msg<?> request) throws IOException, HorizonDBException {

        CreateTimeSeriesPayload payload = Msgs.getPayload(request);

        DatabaseManager manager = context.getDatabaseManager();
        Database database = manager.getDatabase(payload.getDatabaseName());
        database.createTimeSeries(payload.getDefinition(), !context.isReplay());
        
        return Msgs.newCreateTimeSeriesResponse(request);
    }
}

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
package io.horizondb.db.queries;

import io.horizondb.model.core.Field;

import java.util.TimeZone;

import com.google.common.collect.RangeSet;

/**
 * @author Benjamin
 *
 */
public interface Expression {
    
    /**
     * Returns the timestamp range corresponding to this expression.
     * 
     * @param prototype the timestamp field used as prototype
     * @param timeZone the time series time zone
     * @return the timestamp ranges accepted by this expression.
     */
    RangeSet<Field> getTimestampRanges(Field prototype, TimeZone timeZone);    
}
/*
 *
 *  * Copyright [2022] [DMetaSoul Team]
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.apache.flink.lakesoul;

import org.apache.flink.table.catalog.CatalogDatabase;

import java.util.Map;
import java.util.Optional;

public class LakesoulCatalogDatabase implements CatalogDatabase {
    private final Map<String, String> properties=null;
    private final String comment ="Only default Database";

    @Override
    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public String getComment() {
        return comment;
    }

    @Override
    public CatalogDatabase copy() {
        return new LakesoulCatalogDatabase();
    }

    @Override
    public CatalogDatabase copy(Map<String, String> map) {
        return new LakesoulCatalogDatabase();
    }

    @Override
    public Optional<String> getDescription() {
        return  Optional.ofNullable(comment);
    }

    @Override
    public Optional<String> getDetailedDescription() {
        return  Optional.ofNullable(comment);
    }
}

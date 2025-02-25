/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.table;

import org.apache.flink.core.fs.Path;
import org.apache.flink.table.store.CoreOptions;
import org.apache.flink.table.store.file.schema.TableSchema;
import org.apache.flink.table.store.file.utils.SnapshotManager;
import org.apache.flink.table.store.table.source.DataTableScan;
import org.apache.flink.table.types.logical.RowType;

import java.util.List;

/**
 * An abstraction layer above {@link org.apache.flink.table.store.file.FileStore} to provide reading
 * and writing of {@link org.apache.flink.table.data.RowData}.
 */
public interface FileStoreTable extends Table, SupportsPartition, SupportsWrite {

    CoreOptions options();

    Path location();

    @Override
    default String name() {
        return location().getName();
    }

    @Override
    default RowType rowType() {
        return schema().logicalRowType();
    }

    @Override
    default List<String> partitionKeys() {
        return schema().partitionKeys();
    }

    TableSchema schema();

    SnapshotManager snapshotManager();

    @Override
    DataTableScan newScan();
}

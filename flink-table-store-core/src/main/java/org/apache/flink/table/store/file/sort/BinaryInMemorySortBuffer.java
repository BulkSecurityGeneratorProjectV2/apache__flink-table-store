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

package org.apache.flink.table.store.file.sort;

import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.runtime.io.disk.SimpleCollectingOutputView;
import org.apache.flink.runtime.operators.sort.QuickSort;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.binary.BinaryRowData;
import org.apache.flink.table.runtime.generated.NormalizedKeyComputer;
import org.apache.flink.table.runtime.generated.RecordComparator;
import org.apache.flink.table.runtime.operators.sort.BinaryIndexedSortable;
import org.apache.flink.table.runtime.typeutils.AbstractRowDataSerializer;
import org.apache.flink.table.runtime.typeutils.BinaryRowDataSerializer;
import org.apache.flink.table.runtime.util.MemorySegmentPool;
import org.apache.flink.util.MutableObjectIterator;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;

import static org.apache.flink.util.Preconditions.checkArgument;

/**
 * In memory sort buffer for binary row. The main code is copied from Flink {@code
 * BinaryInMemorySortBuffer} instead of extended because it's a final class.
 *
 * <p>The main differences in the new sort buffer are:
 *
 * <ul>
 *   <li>1. Add clear method to clean all memory.
 *   <li>2. Add tryInitialized() method to initialize memory before write and read in buffer, while
 *       the old buffer will do it in the constructor and reset().
 *   <li>3. Remove reset() and etc. methods which are not used in flink table store.
 * </ul>
 */
public class BinaryInMemorySortBuffer extends BinaryIndexedSortable implements SortBuffer {

    private static final int MIN_REQUIRED_BUFFERS = 3;

    private final AbstractRowDataSerializer<RowData> inputSerializer;
    private final ArrayList<MemorySegment> recordBufferSegments;
    private final SimpleCollectingOutputView recordCollector;

    private long currentDataBufferOffset;
    private long sortIndexBytes;
    private boolean isInitialized;

    /** Create a memory sorter in `insert` way. */
    public static BinaryInMemorySortBuffer createBuffer(
            NormalizedKeyComputer normalizedKeyComputer,
            AbstractRowDataSerializer<RowData> serializer,
            RecordComparator comparator,
            MemorySegmentPool memoryPool) {
        checkArgument(memoryPool.freePages() >= MIN_REQUIRED_BUFFERS);
        ArrayList<MemorySegment> recordBufferSegments = new ArrayList<>(16);
        return new BinaryInMemorySortBuffer(
                normalizedKeyComputer,
                serializer,
                comparator,
                recordBufferSegments,
                new SimpleCollectingOutputView(
                        recordBufferSegments, memoryPool, memoryPool.pageSize()),
                memoryPool);
    }

    private BinaryInMemorySortBuffer(
            NormalizedKeyComputer normalizedKeyComputer,
            AbstractRowDataSerializer<RowData> inputSerializer,
            RecordComparator comparator,
            ArrayList<MemorySegment> recordBufferSegments,
            SimpleCollectingOutputView recordCollector,
            MemorySegmentPool pool) {
        super(
                normalizedKeyComputer,
                new BinaryRowDataSerializer(inputSerializer.getArity()),
                comparator,
                recordBufferSegments,
                pool);
        this.inputSerializer = inputSerializer;
        this.recordBufferSegments = recordBufferSegments;
        this.recordCollector = recordCollector;
        // The memory will be initialized in super()
        this.isInitialized = true;
        this.clear();
    }

    // -------------------------------------------------------------------------
    // Memory Segment
    // -------------------------------------------------------------------------

    private void returnToSegmentPool() {
        // return all memory
        this.memorySegmentPool.returnAll(this.sortIndex);
        this.memorySegmentPool.returnAll(this.recordBufferSegments);
        this.sortIndex.clear();
        this.recordBufferSegments.clear();
    }

    public int getBufferSegmentCount() {
        return this.recordBufferSegments.size();
    }

    /** Try to initialize the sort buffer if all contained data is discarded. */
    private void tryInitialize() {
        if (!isInitialized) {
            // grab first buffer
            this.currentSortIndexSegment = nextMemorySegment();
            this.sortIndex.add(this.currentSortIndexSegment);
            // grab second buffer
            this.recordCollector.reset();
            this.isInitialized = true;
        }
    }

    /**
     * We add clear() method here instead of reset() to release all memory segments. The reset()
     * method in flink sort buffer will clear memory and grab two buffers for
     * currentSortIndexSegment and recordCollector.
     */
    @Override
    public void clear() {
        if (this.isInitialized) {
            // reset all offsets
            this.numRecords = 0;
            this.currentSortIndexOffset = 0;
            this.currentDataBufferOffset = 0;
            this.sortIndexBytes = 0;

            // return all memory
            returnToSegmentPool();
            this.currentSortIndexSegment = null;
            this.isInitialized = false;
        }
    }

    @Override
    public long getOccupancy() {
        return this.currentDataBufferOffset + this.sortIndexBytes;
    }

    @Override
    public boolean flushMemory() {
        return false;
    }

    boolean isEmpty() {
        return this.numRecords == 0;
    }

    /**
     * Writes a given record to this sort buffer. The written record will be appended and take the
     * last logical position.
     *
     * @param record The record to be written.
     * @return True, if the record was successfully written, false, if the sort buffer was full.
     * @throws IOException Thrown, if an error occurred while serializing the record into the
     *     buffers.
     */
    @Override
    public boolean write(RowData record) throws IOException {
        tryInitialize();

        // check whether we need a new memory segment for the sort index
        if (!checkNextIndexOffset()) {
            return false;
        }

        // serialize the record into the data buffers
        int skip;
        try {
            skip = this.inputSerializer.serializeToPages(record, this.recordCollector);
        } catch (EOFException e) {
            return false;
        }

        final long newOffset = this.recordCollector.getCurrentOffset();
        long currOffset = currentDataBufferOffset + skip;

        writeIndexAndNormalizedKey(record, currOffset);

        this.currentDataBufferOffset = newOffset;

        return true;
    }

    private BinaryRowData getRecordFromBuffer(BinaryRowData reuse, long pointer)
            throws IOException {
        this.recordBuffer.setReadPosition(pointer);
        return this.serializer.mapFromPages(reuse, this.recordBuffer);
    }

    // -------------------------------------------------------------------------

    /**
     * Gets an iterator over all records in this buffer in their logical order.
     *
     * @return An iterator returning the records in their logical order.
     */
    private MutableObjectIterator<BinaryRowData> iterator() {
        tryInitialize();

        return new MutableObjectIterator<BinaryRowData>() {
            private final int size = size();
            private int current = 0;

            private int currentSegment = 0;
            private int currentOffset = 0;

            private MemorySegment currentIndexSegment = sortIndex.get(0);

            @Override
            public BinaryRowData next(BinaryRowData target) {
                if (this.current < this.size) {
                    this.current++;
                    if (this.currentOffset > lastIndexEntryOffset) {
                        this.currentOffset = 0;
                        this.currentIndexSegment = sortIndex.get(++this.currentSegment);
                    }

                    long pointer = this.currentIndexSegment.getLong(this.currentOffset);
                    this.currentOffset += indexEntrySize;

                    try {
                        return getRecordFromBuffer(target, pointer);
                    } catch (IOException ioe) {
                        throw new RuntimeException(ioe);
                    }
                } else {
                    return null;
                }
            }

            @Override
            public BinaryRowData next() {
                throw new RuntimeException("Not support!");
            }
        };
    }

    @Override
    public final MutableObjectIterator<BinaryRowData> sortedIterator() {
        new QuickSort().sort(this);
        return iterator();
    }
}

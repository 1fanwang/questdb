/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.table;

import io.questdb.MessageBus;
import io.questdb.cairo.*;
import io.questdb.cairo.map.Map;
import io.questdb.cairo.map.MapKey;
import io.questdb.cairo.map.MapValue;
import io.questdb.cairo.sql.*;
import io.questdb.cairo.sql.async.PageFrameReduceTask;
import io.questdb.cairo.sql.async.PageFrameReduceTaskFactory;
import io.questdb.cairo.sql.async.PageFrameReducer;
import io.questdb.cairo.sql.async.PageFrameSequence;
import io.questdb.cairo.vm.api.MemoryCARW;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.engine.functions.GroupByFunction;
import io.questdb.griffin.engine.groupby.GroupByFunctionsUpdater;
import io.questdb.griffin.engine.groupby.GroupByRecordCursorFactory;
import io.questdb.jit.CompiledFilter;
import io.questdb.mp.SCSequence;
import io.questdb.std.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static io.questdb.cairo.sql.DataFrameCursorFactory.ORDER_ASC;
import static io.questdb.cairo.sql.DataFrameCursorFactory.ORDER_DESC;
import static io.questdb.griffin.engine.table.AsyncGroupByNotKeyedRecordCursorFactory.applyCompiledFilter;
import static io.questdb.griffin.engine.table.AsyncGroupByNotKeyedRecordCursorFactory.applyFilter;

public class AsyncGroupByRecordCursorFactory extends AbstractRecordCursorFactory {

    private static final PageFrameReducer AGGREGATE = AsyncGroupByRecordCursorFactory::aggregate;
    private static final PageFrameReducer FILTER_AND_AGGREGATE = AsyncGroupByRecordCursorFactory::filterAndAggregate;
    private final RecordCursorFactory base;
    private final SCSequence collectSubSeq = new SCSequence();
    private final AsyncGroupByRecordCursor cursor;
    private final PageFrameSequence<AsyncGroupByAtom> frameSequence;
    private final ObjList<GroupByFunction> groupByFunctions;
    private final ObjList<Function> recordFunctions; // includes groupByFunctions
    private final int workerCount;

    public AsyncGroupByRecordCursorFactory(
            @Transient @NotNull BytecodeAssembler asm,
            @NotNull CairoConfiguration configuration,
            @NotNull MessageBus messageBus,
            @NotNull RecordCursorFactory base,
            @NotNull RecordMetadata groupByMetadata,
            @Transient @NotNull ListColumnFilter listColumnFilter,
            @Transient @NotNull ArrayColumnTypes keyTypes,
            @Transient @NotNull ArrayColumnTypes valueTypes,
            @NotNull ObjList<GroupByFunction> groupByFunctions,
            @Nullable ObjList<ObjList<GroupByFunction>> perWorkerGroupByFunctions,
            @NotNull ObjList<Function> keyFunctions,
            @Nullable ObjList<ObjList<Function>> perWorkerKeyFunctions,
            @NotNull ObjList<Function> recordFunctions,
            @Nullable CompiledFilter compiledFilter,
            @Nullable MemoryCARW bindVarMemory,
            @Nullable ObjList<Function> bindVarFunctions,
            @Nullable Function filter,
            @NotNull PageFrameReduceTaskFactory reduceTaskFactory,
            @Nullable ObjList<Function> perWorkerFilters,
            int workerCount
    ) {
        super(groupByMetadata);
        try {
            this.base = base;
            this.groupByFunctions = groupByFunctions;
            this.recordFunctions = recordFunctions;
            AsyncGroupByAtom atom = new AsyncGroupByAtom(
                    asm,
                    configuration,
                    base.getMetadata(),
                    keyTypes,
                    valueTypes,
                    listColumnFilter,
                    groupByFunctions,
                    perWorkerGroupByFunctions,
                    keyFunctions,
                    perWorkerKeyFunctions,
                    compiledFilter,
                    bindVarMemory,
                    bindVarFunctions,
                    filter,
                    perWorkerFilters,
                    workerCount
            );
            if (filter != null) {
                this.frameSequence = new PageFrameSequence<>(configuration, messageBus, atom, FILTER_AND_AGGREGATE, reduceTaskFactory, PageFrameReduceTask.TYPE_GROUP_BY);
            } else {
                this.frameSequence = new PageFrameSequence<>(configuration, messageBus, atom, AGGREGATE, reduceTaskFactory, PageFrameReduceTask.TYPE_GROUP_BY);
            }
            this.cursor = new AsyncGroupByRecordCursor(configuration, groupByFunctions, recordFunctions, messageBus);
            this.workerCount = workerCount;
        } catch (Throwable e) {
            close();
            throw e;
        }
    }

    @Override
    public PageFrameSequence<AsyncGroupByAtom> execute(SqlExecutionContext executionContext, SCSequence collectSubSeq, int order) throws SqlException {
        return frameSequence.of(base, executionContext, collectSubSeq, order);
    }

    @Override
    public RecordCursorFactory getBaseFactory() {
        return base;
    }

    @Override
    public RecordCursor getCursor(SqlExecutionContext executionContext) throws SqlException {
        final int order = base.getScanDirection() == SCAN_DIRECTION_BACKWARD ? ORDER_DESC : ORDER_ASC;
        cursor.of(execute(executionContext, collectSubSeq, order), executionContext);
        return cursor;
    }

    @Override
    public int getScanDirection() {
        return base.getScanDirection();
    }

    @Override
    public boolean recordCursorSupportsRandomAccess() {
        return true;
    }

    @Override
    public void toPlan(PlanSink sink) {
        if (usesCompiledFilter()) {
            sink.type("Async JIT Group By");
        } else {
            sink.type("Async Group By");
        }
        sink.meta("workers").val(workerCount);
        sink.optAttr("keys", GroupByRecordCursorFactory.getKeys(recordFunctions, getMetadata()));
        sink.optAttr("values", groupByFunctions, true);
        sink.optAttr("filter", frameSequence.getAtom(), true);
        sink.child(base);
    }

    @Override
    public boolean usesCompiledFilter() {
        return frameSequence.getAtom().getCompiledFilter() != null;
    }

    @Override
    public boolean usesIndex() {
        return base.usesIndex();
    }

    private static void aggregate(
            int workerId,
            @NotNull PageAddressCacheRecord record,
            @NotNull PageFrameReduceTask task,
            @NotNull SqlExecutionCircuitBreaker circuitBreaker,
            @Nullable PageFrameSequence<?> stealingFrameSequence
    ) {
        final long frameRowCount = task.getFrameRowCount();
        final AsyncGroupByAtom atom = task.getFrameSequence(AsyncGroupByAtom.class).getAtom();

        final boolean owner = stealingFrameSequence != null && stealingFrameSequence == task.getFrameSequence();
        final int slotId = atom.acquire(workerId, owner, circuitBreaker);
        final GroupByFunctionsUpdater functionUpdater = atom.getFunctionUpdater(slotId);
        final AsyncGroupByAtom.Particle particle = atom.getParticle(slotId);
        final RecordSink mapSink = atom.getMapSink(slotId);
        try {
            if (!particle.isSharded()) {
                aggregateNonSharded(record, frameRowCount, functionUpdater, particle, mapSink);
            } else {
                aggregateSharded(record, frameRowCount, functionUpdater, particle, mapSink);
            }
            atom.tryShard(particle);
        } finally {
            atom.release(slotId);
        }
    }

    private static void aggregateFilteredNonSharded(
            PageAddressCacheRecord record,
            DirectLongList rows,
            GroupByFunctionsUpdater functionUpdater,
            AsyncGroupByAtom.Particle particle,
            RecordSink mapSink
    ) {
        final Map map = particle.getMap();
        for (long p = 0, n = rows.size(); p < n; p++) {
            record.setRowIndex(rows.get(p));

            final MapKey key = map.withKey();
            mapSink.copy(record, key);
            MapValue value = key.createValue();
            if (value.isNew()) {
                functionUpdater.updateNew(value, record);
            } else {
                functionUpdater.updateExisting(value, record);
            }
        }
    }

    private static void aggregateFilteredSharded(
            PageAddressCacheRecord record,
            DirectLongList rows,
            GroupByFunctionsUpdater functionUpdater,
            AsyncGroupByAtom.Particle particle,
            RecordSink mapSink
    ) {
        // The first map is used to write keys.
        final Map lookupShard = particle.getShardMaps().getQuick(0);
        for (long p = 0, n = rows.size(); p < n; p++) {
            record.setRowIndex(rows.get(p));

            final MapKey lookupKey = lookupShard.withKey();
            mapSink.copy(record, lookupKey);
            lookupKey.commit();
            final int hashCode = lookupKey.hash();

            final Map shard = particle.getShardMap(hashCode);
            final MapKey shardKey;
            if (shard != lookupShard) {
                shardKey = shard.withKey();
                shardKey.copyFrom(lookupKey);
            } else {
                shardKey = lookupKey;
            }

            MapValue shardValue = shardKey.createValue(hashCode);
            if (shardValue.isNew()) {
                functionUpdater.updateNew(shardValue, record);
            } else {
                functionUpdater.updateExisting(shardValue, record);
            }
        }
    }

    private static void aggregateNonSharded(
            PageAddressCacheRecord record,
            long frameRowCount,
            GroupByFunctionsUpdater functionUpdater,
            AsyncGroupByAtom.Particle particle,
            RecordSink mapSink
    ) {
        final Map map = particle.getMap();
        for (long r = 0; r < frameRowCount; r++) {
            record.setRowIndex(r);

            final MapKey key = map.withKey();
            mapSink.copy(record, key);
            MapValue value = key.createValue();
            if (value.isNew()) {
                functionUpdater.updateNew(value, record);
            } else {
                functionUpdater.updateExisting(value, record);
            }
        }
    }

    private static void aggregateSharded(
            PageAddressCacheRecord record,
            long frameRowCount,
            GroupByFunctionsUpdater functionUpdater,
            AsyncGroupByAtom.Particle particle,
            RecordSink mapSink
    ) {
        // The first map is used to write keys.
        final Map lookupShard = particle.getShardMaps().getQuick(0);
        for (long r = 0; r < frameRowCount; r++) {
            record.setRowIndex(r);

            final MapKey lookupKey = lookupShard.withKey();
            mapSink.copy(record, lookupKey);
            lookupKey.commit();
            final int hashCode = lookupKey.hash();

            final Map shard = particle.getShardMap(hashCode);
            final MapKey shardKey;
            if (shard != lookupShard) {
                shardKey = shard.withKey();
                shardKey.copyFrom(lookupKey);
            } else {
                shardKey = lookupKey;
            }

            MapValue shardValue = shardKey.createValue(hashCode);
            if (shardValue.isNew()) {
                functionUpdater.updateNew(shardValue, record);
            } else {
                functionUpdater.updateExisting(shardValue, record);
            }
        }
    }

    private static void filterAndAggregate(
            int workerId,
            @NotNull PageAddressCacheRecord record,
            @NotNull PageFrameReduceTask task,
            @NotNull SqlExecutionCircuitBreaker circuitBreaker,
            @Nullable PageFrameSequence<?> stealingFrameSequence
    ) {
        final DirectLongList rows = task.getFilteredRows();
        final PageAddressCache pageAddressCache = task.getPageAddressCache();

        rows.clear();

        final AsyncGroupByAtom atom = task.getFrameSequence(AsyncGroupByAtom.class).getAtom();

        final boolean owner = stealingFrameSequence != null && stealingFrameSequence == task.getFrameSequence();
        final int slotId = atom.acquire(workerId, owner, circuitBreaker);
        final GroupByFunctionsUpdater functionUpdater = atom.getFunctionUpdater(slotId);
        final AsyncGroupByAtom.Particle particle = atom.getParticle(slotId);
        final CompiledFilter compiledFilter = atom.getCompiledFilter();
        final Function filter = atom.getFilter(slotId);
        final RecordSink mapSink = atom.getMapSink(slotId);
        try {
            if (compiledFilter == null || pageAddressCache.hasColumnTops(task.getFrameIndex())) {
                // Use Java-based filter when there is no compiled filter or in case of a page frame with column tops.
                applyFilter(filter, rows, record, task.getFrameRowCount());
            } else {
                applyCompiledFilter(compiledFilter, atom.getBindVarMemory(), atom.getBindVarFunctions(), task);
            }

            if (!particle.isSharded()) {
                aggregateFilteredNonSharded(record, rows, functionUpdater, particle, mapSink);
            } else {
                aggregateFilteredSharded(record, rows, functionUpdater, particle, mapSink);
            }
            atom.tryShard(particle);
        } finally {
            atom.release(slotId);
        }
    }

    @Override
    protected void _close() {
        Misc.free(base);
        Misc.free(cursor);
        Misc.freeObjList(recordFunctions); // groupByFunctions are included in recordFunctions
        Misc.free(frameSequence);
    }
}

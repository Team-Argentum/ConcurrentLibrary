package net.sixik.concurrent_library.long2int;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class Long2Int {
    private static final Unsafe U = unsafe();
    private static final long ONES = 0x0101010101010101L;
    private static final long HIGH = 0x8080808080808080L;
    private static final int EMPTY = 0;
    private static final int RESERVED = 1;
    private static final int LIVE = 2;
    private static final int DELETE = 3;
    private static final int MIN_CAPACITY = 8;
    private static final int DEFAULT_MISSING = 0;
    private static final int RESERVED_SPINS = 128;
    private static final int MIGRATION_CHUNK = 256;
    private static final long KEY_OFFSET = 0L;
    private static final long VALUE_OFFSET = 8L;
    private static final long STATE_OFFSET = 12L;
    private static final VarHandle DYNAMIC_VIEW;

    static {
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            throw new ExceptionInInitializerError("Long2Int direct implementation requires little-endian native order");
        }
        try {
            DYNAMIC_VIEW = MethodHandles.lookup().findVarHandle(AbstractDynamicMap.class, "view", View.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Long2Int() {
    }

    public static Long2IntLookup lookup(long[] keys, int[] values) {
        if (keys.length != values.length) {
            throw new IllegalArgumentException("keys and values lengths differ");
        }
        ImmutableDirectLong2IntLookup lookup = new ImmutableDirectLong2IntLookup(tableCapacity(keys.length));
        boolean ok = false;
        try {
            for (int i = 0; i < keys.length; i++) {
                lookup.putBuild(keys[i], values[i]);
            }
            U.storeFence();
            ok = true;
            return lookup;
        } finally {
            if (!ok) {
                lookup.close();
            }
        }
    }

    /**
     * Legacy single-threaded lookup. Not thread-safe; kept for completeness only.
     */
    @Deprecated(forRemoval = false)
    public static Long2IntLookup singleThreadedLookup(long[] keys, int[] values) {
        return singleThreadedBuilder(keys.length).loadFactor(0.82d).buildLookup(keys, values);
    }

    /** Legacy single-threaded heap lookup. Not thread-safe. */
    @Deprecated(forRemoval = false)
    public static Long2IntLookup singleThreadedHeapLookup(long[] keys, int[] values) {
        return singleThreadedBuilder(keys.length).backend(Long2IntBackend.HEAP).loadFactor(0.82d).buildLookup(keys, values);
    }

    /** Legacy single-threaded direct-memory lookup. Not thread-safe. */
    @Deprecated(forRemoval = false)
    public static Long2IntLookup singleThreadedDirectLookup(long[] keys, int[] values) {
        return singleThreadedBuilder(keys.length).backend(Long2IntBackend.DIRECT).loadFactor(0.82d).buildLookup(keys, values);
    }

    /** Legacy single-threaded Panama lookup. Not thread-safe. */
    @Deprecated(forRemoval = false)
    public static Long2IntLookup singleThreadedPanamaLookup(long[] keys, int[] values) {
        return singleThreadedBuilder(keys.length).backend(Long2IntBackend.PANAMA).loadFactor(0.82d).buildLookup(keys, values);
    }

    public static Long2IntLookupBuilder lookupBuilder(int expectedSize) {
        return new Long2IntLookupBuilder(expectedSize);
    }

    public static Long2IntAppendMap fixed(int expectedSize) {
        return new FixedDirectLong2IntAppendMap(tableCapacity(expectedSize));
    }

    /** Legacy single-threaded fixed map. Not thread-safe. */
    @Deprecated(forRemoval = false)
    public static Long2IntSingleThreadMap singleThreadedFixed(int expectedSize) {
        return singleThreadedBuilder(expectedSize).buildFixed();
    }

    /** Legacy single-threaded heap fixed map. Not thread-safe. */
    @Deprecated(forRemoval = false)
    public static Long2IntSingleThreadMap singleThreadedHeapFixed(int expectedSize) {
        return singleThreadedBuilder(expectedSize).backend(Long2IntBackend.HEAP).buildFixed();
    }

    /** Legacy single-threaded direct-memory fixed map. Not thread-safe. */
    @Deprecated(forRemoval = false)
    public static Long2IntSingleThreadMap singleThreadedDirectFixed(int expectedSize) {
        return singleThreadedBuilder(expectedSize).backend(Long2IntBackend.DIRECT).buildFixed();
    }

    /** Legacy single-threaded Panama fixed map. Not thread-safe. */
    @Deprecated(forRemoval = false)
    public static Long2IntSingleThreadMap singleThreadedPanamaFixed(int expectedSize) {
        return singleThreadedBuilder(expectedSize).backend(Long2IntBackend.PANAMA).buildFixed();
    }

    public static Long2IntAppendMapBuilder fixedBuilder(int expectedSize) {
        return new Long2IntAppendMapBuilder(expectedSize);
    }

    public static Long2IntMap concurrent(int expectedSize) {
        return new DynamicDirectLong2IntMap(expectedSize);
    }

    /** Legacy single-threaded mutable map. Not thread-safe. */
    @Deprecated(forRemoval = false)
    public static Long2IntMap singleThreadedMutable(int expectedSize) {
        return singleThreadedBuilder(expectedSize).loadFactor(0.65d).buildMutable();
    }

    /** Legacy single-threaded heap mutable map. Not thread-safe. */
    @Deprecated(forRemoval = false)
    public static Long2IntMap singleThreadedHeapMutable(int expectedSize) {
        return singleThreadedBuilder(expectedSize).backend(Long2IntBackend.HEAP).loadFactor(0.65d).buildMutable();
    }

    /** Legacy single-threaded direct-memory mutable map. Not thread-safe. */
    @Deprecated(forRemoval = false)
    public static Long2IntMap singleThreadedDirectMutable(int expectedSize) {
        return singleThreadedBuilder(expectedSize).backend(Long2IntBackend.DIRECT).loadFactor(0.65d).buildMutable();
    }

    /** Legacy single-threaded Panama mutable map. Not thread-safe. */
    @Deprecated(forRemoval = false)
    public static Long2IntMap singleThreadedPanamaMutable(int expectedSize) {
        return singleThreadedBuilder(expectedSize).backend(Long2IntBackend.PANAMA).loadFactor(0.65d).buildMutable();
    }

    /**
     * Legacy single-threaded builder. Not thread-safe; kept for completeness
     * only and intentionally excluded from the regular concurrent benchmarks.
     */
    @Deprecated(forRemoval = false)
    public static SingleThreadLong2IntBuilder singleThreadedBuilder(int expectedSize) {
        return new SingleThreadLong2IntBuilder(expectedSize);
    }

    public static Long2IntMap concurrentManaged(int expectedSize) {
        return new ManagedDynamicDirectLong2IntMap(expectedSize);
    }

    public static Long2IntInspector inspect(Object map) {
        if (map instanceof Long2IntInternalInspectable inspectable) {
            return inspectable.inspector();
        }
        if (map instanceof Inspectable inspectable) {
            return inspectable.inspector();
        }
        throw new IllegalArgumentException("Unsupported Long2Int object: " + map);
    }

    public static final class ImmutableDirectLong2IntLookup extends AbstractSingleTable implements Long2IntLookup {
        private ImmutableDirectLong2IntLookup(int capacity) {
            super(new Table(capacity, false));
        }

        @Override
        public int get(long key) {
            return getOrDefault(key, DEFAULT_MISSING);
        }

        @Override
        public int getOrDefault(long key, int defaultValue) {
            checkOpen();
            return table.getImmutable(key, defaultValue);
        }

        @Override
        public boolean containsKey(long key) {
            checkOpen();
            return table.findImmutable(key) >= 0L;
        }

        @Override
        public long size() {
            checkOpen();
            return size.get();
        }

        private void putBuild(long key, int value) {
            long existing = table.findImmutable(key);
            if (existing >= 0L) {
                table.putValue(existing, value);
                return;
            }
            if (!table.putBuild(key, value)) {
                throw new IllegalStateException("lookup table is full");
            }
            size.increment();
        }
    }

    public static final class FixedDirectLong2IntAppendMap extends AbstractSingleTable implements Long2IntAppendMap {
        private FixedDirectLong2IntAppendMap(int capacity) {
            super(new Table(capacity, false));
        }

        @Override
        public int get(long key) {
            return getOrDefault(key, DEFAULT_MISSING);
        }

        @Override
        public int getOrDefault(long key, int defaultValue) {
            checkOpen();
            return table.getImmutable(key, defaultValue);
        }

        @Override
        public boolean containsKey(long key) {
            checkOpen();
            return table.findImmutable(key) >= 0L;
        }

        @Override
        public long size() {
            checkOpen();
            return size.get();
        }

        @Override
        public boolean put(long key, int value) {
            checkOpen();
            for (;;) {
                PutResult result = table.putFixed(key, value, false, counters);
                if (result == PutResult.INSERTED) {
                    size.increment();
                    return true;
                }
                if (result == PutResult.UPDATED) {
                    return true;
                }
                if (result == PutResult.FULL || result == PutResult.PRESENT) {
                    return false;
                }
            }
        }

        @Override
        public boolean putIfAbsent(long key, int value) {
            checkOpen();
            for (;;) {
                PutResult result = table.putFixed(key, value, true, counters);
                if (result == PutResult.INSERTED) {
                    size.increment();
                    return true;
                }
                if (result == PutResult.PRESENT || result == PutResult.FULL) {
                    return false;
                }
            }
        }

        @Override
        public boolean compareAndSet(long key, int expected, int update) {
            checkOpen();
            long slot = table.findImmutable(key);
            return slot >= 0L && table.casValue(slot, expected, update);
        }

        @Override
        public boolean replace(long key, int expected, int update) {
            return compareAndSet(key, expected, update);
        }

        @Override
        public boolean addIfPresent(long key, int delta) {
            checkOpen();
            long slot = table.findImmutable(key);
            if (slot < 0L) {
                return false;
            }
            for (;;) {
                int old = table.valueVolatile(slot);
                if (table.casValue(slot, old, old + delta)) {
                    return true;
                }
                Thread.onSpinWait();
            }
        }

        @Override
        public int getAndAddIfPresent(long key, int delta, int missingReturn) {
            checkOpen();
            long slot = table.findImmutable(key);
            if (slot < 0L) {
                return missingReturn;
            }
            for (;;) {
                int old = table.valueVolatile(slot);
                if (table.casValue(slot, old, old + delta)) {
                    return old;
                }
                Thread.onSpinWait();
            }
        }

        @Override
        public long capacity() {
            checkOpen();
            return table.capacity;
        }
    }

    public static final class DynamicDirectLong2IntMap extends AbstractDynamicMap {
        public DynamicDirectLong2IntMap(int expectedSize) {
            super(tableCapacity(expectedSize), false);
        }

        @Override
        void retire(Table table) {
            synchronized (retired) {
                retired.add(table);
                retiredBytes.addAndGet(table.bytes());
            }
        }

        @Override
        View protect(View view) {
            return view;
        }

        @Override
        void unprotect() {
        }
    }

    public static final class ManagedDynamicDirectLong2IntMap extends AbstractDynamicMap {
        private final HazardRegistry hazards = new HazardRegistry(Math.max(64, Runtime.getRuntime().availableProcessors() * 8));

        public ManagedDynamicDirectLong2IntMap(int expectedSize) {
            super(tableCapacity(expectedSize), true);
        }

        @Override
        void retire(Table table) {
            synchronized (retired) {
                retired.add(table);
                retiredBytes.addAndGet(table.bytes());
                reclaimRetiredLocked();
            }
        }

        @Override
        View protect(View first) {
            HazardCell cell = hazards.cell();
            for (;;) {
                cell.view = first;
                View second = currentView();
                if (first == second) {
                    return first;
                }
                first = second;
            }
        }

        @Override
        void unprotect() {
            hazards.clear();
        }

        @Override
        public void close() {
            super.close();
            hazards.clear();
        }

        private void reclaimRetiredLocked() {
            for (int i = retired.size() - 1; i >= 0; i--) {
                Table table = retired.get(i);
                if (!hazards.protects(table)) {
                    retired.remove(i);
                    retiredBytes.addAndGet(-table.bytes());
                    table.close();
                }
            }
        }
    }

    private abstract static class AbstractSingleTable implements AutoCloseable, Inspectable {
        final Table table;
        final PaddedCounter size = new PaddedCounter();
        final Counters counters = new Counters();
        volatile boolean closed;

        AbstractSingleTable(Table table) {
            this.table = table;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                table.close();
            }
        }

        @Override
        public Long2IntInspector inspector() {
            return new Inspector(this);
        }

        final void checkOpen() {
            if (closed) {
                throw new IllegalStateException("Long2Int collection is closed");
            }
        }
    }

    private abstract static class AbstractDynamicMap implements Long2IntMap, Inspectable {
        final PaddedCounter size = new PaddedCounter();
        final Counters counters = new Counters();
        final List<Table> retired = new ArrayList<>();
        final AtomicLong retiredBytes = new AtomicLong();
        final AtomicLong resizeCount = new AtomicLong();
        final AtomicLong rebuildCount = new AtomicLong();
        final boolean managed;
        volatile View view;
        volatile boolean closed;

        AbstractDynamicMap(int capacity, boolean managed) {
            this.view = new View(new Table(capacity, true), null, new AtomicLong(), new AtomicLong(), new AtomicLong());
            this.managed = managed;
        }

        @Override
        public int get(long key) {
            return getOrDefault(key, DEFAULT_MISSING);
        }

        @Override
        public int getOrDefault(long key, int defaultValue) {
            checkOpen();
            View v = protect(currentView());
            try {
                SearchResult primary = v.primary.searchDynamic(key);
                if (primary.kind == SearchKind.LIVE) {
                    return v.primary.valueVolatile(primary.slot);
                }
                if (primary.kind == SearchKind.DELETE || v.old == null) {
                    return defaultValue;
                }
                long oldSlot = v.old.findDynamicLive(key);
                return oldSlot >= 0L ? v.old.valueVolatile(oldSlot) : defaultValue;
            } finally {
                unprotect();
            }
        }

        @Override
        public boolean containsKey(long key) {
            return findVisible(key).kind == SearchKind.LIVE;
        }

        @Override
        public long size() {
            checkOpen();
            return exactVisibleSize(currentView());
        }

        @Override
        public boolean put(long key, int value) {
            checkOpen();
            if (putExistingClean(key, value)) {
                return true;
            }
            for (;;) {
                PutResult result;
                MutationContext mutation = enterPrimaryMutation();
                View v = mutation.view;
                Table table = mutation.table;
                if (v.old != null) {
                    table.exitMutation();
                    assistCurrentResize(v);
                    continue;
                }
                try {
                    result = table.putDynamic(key, value, false, counters);
                } finally {
                    table.exitMutation();
                }
                if (v != currentView()) {
                    if (result == PutResult.INSERTED || result == PutResult.REINSERTED || result == PutResult.UPDATED || result == PutResult.PRESENT) {
                        put(key, value);
                        return true;
                    }
                    continue;
                }
                if (result == PutResult.INSERTED || result == PutResult.REINSERTED) {
                    size.increment();
                    if (result == PutResult.INSERTED && v.old != null && v.old.findDynamicLive(key) >= 0L) {
                        size.decrement();
                    }
                    maybeResize(v);
                    assistCurrentResize(v);
                    return true;
                }
                if (result == PutResult.UPDATED || result == PutResult.PRESENT) {
                    assistCurrentResize(v);
                    return true;
                }
                if (result == PutResult.RETRY) {
                    continue;
                }
                startResize(v, false);
            }
        }

        final boolean putExistingClean(long key, int value) {
            View first = currentView();
            if (first.old != null) {
                return false;
            }
            View v = protect(first);
            try {
                if (v != first || v.old != null || v != currentView()) {
                    return false;
                }
                SearchResult result = v.primary.searchDynamic(key);
                if (result.kind != SearchKind.LIVE) {
                    return false;
                }
                if (v != currentView()
                        || v.old != null
                        || v.primary.key(result.slot) != key
                        || v.primary.stateVolatile(result.slot) != LIVE) {
                    return false;
                }
                v.primary.putValueVolatile(result.slot, value);
                return v == currentView() && v.old == null;
            } finally {
                unprotect();
            }
        }

        @Override
        public boolean putIfAbsent(long key, int value) {
            checkOpen();
            for (;;) {
                VisibleResult visible = findVisible(key);
                if (visible.kind == SearchKind.LIVE) {
                    return false;
                }
                PutResult result;
                MutationContext mutation = enterPrimaryMutation();
                View v = mutation.view;
                Table table = mutation.table;
                if (v.old != null) {
                    table.exitMutation();
                    assistCurrentResize(v);
                    continue;
                }
                if (v != visible.view) {
                    table.exitMutation();
                    continue;
                }
                try {
                    result = table.putDynamic(key, value, true, counters);
                } finally {
                    table.exitMutation();
                }
                if (v != currentView()) {
                    if (result == PutResult.INSERTED || result == PutResult.REINSERTED) {
                        putIfAbsent(key, value);
                        return true;
                    }
                    continue;
                }
                if (result == PutResult.INSERTED || result == PutResult.REINSERTED) {
                    size.increment();
                    maybeResize(v);
                    assistCurrentResize(v);
                    return true;
                }
                if (result == PutResult.PRESENT || result == PutResult.UPDATED) {
                    return false;
                }
                if (result == PutResult.RETRY) {
                    continue;
                }
                startResize(v, false);
            }
        }

        @Override
        public boolean compareAndSet(long key, int expected, int update) {
            for (;;) {
                VisibleResult visible = findVisible(key);
                if (visible.kind != SearchKind.LIVE || visible.table == null) {
                    return false;
                }
                if (visible.table != visible.view.primary) {
                    assistCurrentResize(visible.view);
                    continue;
                }
                MutationContext mutation = enterPrimaryMutation();
                Table table = mutation.table;
                if (mutation.view != visible.view) {
                    table.exitMutation();
                    continue;
                }
                boolean retryAfterPublish = false;
                try {
                    if (table.key(visible.slot) != key || table.stateVolatile(visible.slot) != LIVE) {
                        continue;
                    }
                    int actual = table.valueVolatile(visible.slot);
                    if (actual != expected) {
                        return false;
                    }
                    if (!table.casValue(visible.slot, expected, update)) {
                        counters.failedCas.increment();
                        continue;
                    }
                    if (table.key(visible.slot) != key || table.stateVolatile(visible.slot) != LIVE) {
                        retryAfterPublish = true;
                    }
                } finally {
                    table.exitMutation();
                }
                publishIfViewMoved(key, update, visible.view);
                if (retryAfterPublish) {
                    continue;
                }
                return true;
            }
        }

        @Override
        public boolean replace(long key, int expected, int update) {
            return compareAndSet(key, expected, update);
        }

        @Override
        public boolean addIfPresent(long key, int delta) {
            for (;;) {
                VisibleResult visible = findVisible(key);
                if (visible.kind != SearchKind.LIVE || visible.table == null) {
                    return false;
                }
                if (visible.table != visible.view.primary) {
                    assistCurrentResize(visible.view);
                    continue;
                }
                MutationContext mutation = enterPrimaryMutation();
                Table table = mutation.table;
                if (mutation.view != visible.view) {
                    table.exitMutation();
                    continue;
                }
                for (;;) {
                    boolean applied = false;
                    boolean deletedAfterApply = false;
                    int update = 0;
                    try {
                        if (table.key(visible.slot) != key || table.stateVolatile(visible.slot) != LIVE) {
                            return false;
                        }
                        int old = table.valueVolatile(visible.slot);
                        update = old + delta;
                        if (table.casValue(visible.slot, old, update)) {
                            deletedAfterApply = table.stateVolatile(visible.slot) != LIVE;
                            applied = true;
                        }
                    } finally {
                        table.exitMutation();
                    }
                    if (applied) {
                        if (deletedAfterApply) {
                            return false;
                        }
                        publishIfViewMoved(key, update, visible.view);
                        return true;
                    }
                    Thread.onSpinWait();
                    mutation = enterPrimaryMutation();
                    table = mutation.table;
                    if (mutation.view != visible.view) {
                        table.exitMutation();
                        break;
                    }
                }
            }
        }

        @Override
        public int getAndAddIfPresent(long key, int delta, int missingReturn) {
            for (;;) {
                VisibleResult visible = findVisible(key);
                if (visible.kind != SearchKind.LIVE || visible.table == null) {
                    return missingReturn;
                }
                if (visible.table != visible.view.primary) {
                    assistCurrentResize(visible.view);
                    continue;
                }
                MutationContext mutation = enterPrimaryMutation();
                Table table = mutation.table;
                if (mutation.view != visible.view) {
                    table.exitMutation();
                    continue;
                }
                boolean applied = false;
                boolean deletedAfterApply = false;
                int old = missingReturn;
                int update = 0;
                try {
                    if (table.key(visible.slot) != key || table.stateVolatile(visible.slot) != LIVE) {
                        return missingReturn;
                    }
                    old = table.valueVolatile(visible.slot);
                    update = old + delta;
                    if (table.casValue(visible.slot, old, update)) {
                        deletedAfterApply = table.stateVolatile(visible.slot) != LIVE;
                        applied = true;
                    }
                } finally {
                    table.exitMutation();
                }
                if (applied) {
                    if (deletedAfterApply) {
                        return missingReturn;
                    }
                    publishIfViewMoved(key, update, visible.view);
                    return old;
                }
                Thread.onSpinWait();
            }
        }

        @Override
        public long capacity() {
            checkOpen();
            return currentView().primary.capacity;
        }

        @Override
        public boolean remove(long key) {
            VisibleResult before = findVisible(key);
            if (before.kind != SearchKind.LIVE) {
                return false;
            }
            removeAndGetOld(key, DEFAULT_MISSING);
            return true;
        }

        @Override
        public int removeAndGetOld(long key, int missingReturn) {
            checkOpen();
            for (;;) {
                View v = protect(currentView());
                SearchResult primary;
                try {
                    primary = v.primary.searchDynamic(key);
                } finally {
                    unprotect();
                }
                if (primary.kind == SearchKind.LIVE) {
                    MutationContext mutation = enterPrimaryMutation();
                    Table table = mutation.table;
                    if (mutation.view != v) {
                        table.exitMutation();
                        continue;
                    }
                    boolean removed = false;
                    boolean viewMoved = false;
                    int oldValue = missingReturn;
                    try {
                        if (table.key(primary.slot) != key || table.stateVolatile(primary.slot) != LIVE) {
                            continue;
                        }
                        oldValue = table.valueVolatile(primary.slot);
                        if (table.casState(primary.slot, LIVE, DELETE)) {
                            U.storeFence();
                            table.putCtrl(primary.index, table.deleteTagForKey(key));
                            table.deleteCount.increment();
                            size.decrement();
                            viewMoved = v != currentView();
                            removed = true;
                        }
                    } finally {
                        table.exitMutation();
                    }
                    if (removed) {
                        if (viewMoved) {
                            removeAndGetOld(key, missingReturn);
                        }
                        maybeResize(v);
                        assistCurrentResize(v);
                        return oldValue;
                    }
                    continue;
                }
                if (primary.kind == SearchKind.DELETE) {
                    return missingReturn;
                }
                if (v.old != null) {
                    assistCurrentResize(v);
                    continue;
                }
                return missingReturn;
            }
        }

        @Override
        public boolean resizeInProgress() {
            checkOpen();
            return currentView().old != null;
        }

        @Override
        public void completeResize() {
            checkOpen();
            for (;;) {
                View v = currentView();
                if (v.old == null) {
                    return;
                }
                assistResize(v);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            View v = currentView();
            v.primary.close();
            if (v.old != null) {
                v.old.close();
            }
            for (Table table : retired) {
                table.close();
            }
            retired.clear();
            retiredBytes.set(0L);
        }

        @Override
        public Long2IntInspector inspector() {
            return new Inspector(this);
        }

        abstract void retire(Table table);

        abstract View protect(View view);

        abstract void unprotect();

        final View currentView() {
            return view;
        }

        final MutationContext enterPrimaryMutation() {
            for (;;) {
                View v = currentView();
                Table table = v.primary;
                table.enterMutation();
                if (v == currentView()) {
                    return new MutationContext(v, table);
                }
                table.exitMutation();
            }
        }

        final void checkOpen() {
            if (closed) {
                throw new IllegalStateException("Long2Int collection is closed");
            }
        }

        final VisibleResult findVisible(long key) {
            checkOpen();
            View v = protect(currentView());
            try {
                SearchResult primary = v.primary.searchDynamic(key);
                if (primary.kind == SearchKind.LIVE || primary.kind == SearchKind.DELETE) {
                    return new VisibleResult(primary.kind, v, v.primary, primary.slot);
                }
                if (v.old != null) {
                    long oldSlot = v.old.findDynamicLive(key);
                    if (oldSlot >= 0L) {
                        return new VisibleResult(SearchKind.LIVE, v, v.old, oldSlot);
                    }
                }
                return new VisibleResult(SearchKind.ABSENT, v, null, -1L);
            } finally {
                unprotect();
            }
        }

        final void maybeResize(View v) {
            if (v != currentView() || v.old != null) {
                return;
            }
            Table primary = v.primary;
            if (primary.used.get() * 100L >= primary.capacity * 65L) {
                startResize(v, false);
            } else if (primary.deleteCount.get() * 100L >= primary.capacity * 15L) {
                startResize(v, true);
            }
        }

        final void startResize(View expected, boolean rebuild) {
            if (expected != currentView()) {
                return;
            }
            if (expected.old != null) {
                assistCurrentResize(expected);
                return;
            }
            Table nextTable = new Table(rebuild ? expected.primary.capacity : safeDouble(expected.primary.capacity), true);
            View next = new View(nextTable, expected.primary, new AtomicLong(), new AtomicLong(), new AtomicLong());
            if (DYNAMIC_VIEW.compareAndSet(this, expected, next)) {
                if (rebuild) {
                    rebuildCount.incrementAndGet();
                } else {
                    resizeCount.incrementAndGet();
                }
                assistCurrentResize(next);
            } else {
                nextTable.close();
            }
        }

        final void assistCurrentResize(View v) {
            if (v == currentView()) {
                assistResize(v);
            }
        }

        final void publishIfViewMoved(long key, int value, View operationView) {
            View now = currentView();
            if (now != operationView) {
                put(key, value);
            }
        }

        final void assistResize(View v) {
            if (v != currentView()) {
                return;
            }
            Table old = v.old;
            if (old == null) {
                return;
            }
            for (;;) {
                long start = v.migrationCursor.getAndAdd(MIGRATION_CHUNK);
                if (start >= old.capacity) {
                    finishResize(v);
                    return;
                }
                int end = (int) Math.min(old.capacity, start + MIGRATION_CHUNK);
                for (int i = (int) start; i < end; i++) {
                    long slot = old.slotAddress(i);
                    int state = old.stateVolatile(slot);
                    if (state == LIVE) {
                        copyStableLive(v.primary, old, slot);
                    } else if (state == RESERVED) {
                        if (old.activeMutations.get() != 0L) {
                            waitReserved(counters);
                            i--;
                        }
                    }
                }
                v.migrationDone.addAndGet(end - start);
                if (end == old.capacity) {
                    finishResize(v);
                    return;
                }
            }
        }

        final void copyStableLive(Table primary, Table old, long oldSlot) {
            for (;;) {
                primary.enterMutation();
                try {
                    if (old.stateVolatile(oldSlot) != LIVE) {
                        return;
                    }
                    long key = old.key(oldSlot);
                    int value = old.valueVolatile(oldSlot);
                    if (old.stateVolatile(oldSlot) != LIVE || old.key(oldSlot) != key) {
                        continue;
                    }
                    for (;;) {
                        PutResult copied = primary.copyLiveIfAbsent(key, value, counters);
                        if (copied == PutResult.FULL) {
                            throw new IllegalStateException("Long2Int migration target became full");
                        }
                        if (copied != PutResult.RETRY) {
                            break;
                        }
                    }
                    int afterState = old.stateVolatile(oldSlot);
                    if (afterState != LIVE || old.key(oldSlot) != key) {
                        continue;
                    }
                    int afterValue = old.valueVolatile(oldSlot);
                    if (afterValue == value) {
                        return;
                    }
                    for (;;) {
                        PutResult updated = primary.putDynamic(key, afterValue, false, counters);
                        if (updated == PutResult.FULL) {
                            throw new IllegalStateException("Long2Int migration target became full");
                        }
                        if (updated != PutResult.RETRY) {
                            return;
                        }
                    }
                } finally {
                    primary.exitMutation();
                }
            }
        }

        final void finishResize(View expected) {
            if (expected != currentView()) {
                return;
            }
            if (expected.old == null
                    || expected.migrationCursor.get() < expected.old.capacity
                    || expected.migrationDone.get() < expected.old.capacity) {
                return;
            }
            if (!expected.finishGate.compareAndSet(0L, 1L)) {
                return;
            }
            boolean releaseGate = true;
            try {
                if (expected != currentView() || expected.old == null) {
                    return;
                }
            if (expected.old.activeMutations.get() != 0L) {
                return;
            }
            long oldMutationEpoch = expected.old.mutationEpoch.get();
            reconcileResize(expected.primary, expected.old);
            if (expected.old.activeMutations.get() != 0L || expected.old.mutationEpoch.get() != oldMutationEpoch) {
                return;
            }
            View clean = new View(expected.primary, null, new AtomicLong(), new AtomicLong(), new AtomicLong());
            if (DYNAMIC_VIEW.compareAndSet(this, expected, clean)) {
                releaseGate = false;
                retire(expected.old);
            }
            } finally {
                if (releaseGate) {
                    expected.finishGate.set(0L);
                }
            }
        }

        final void reconcileResize(Table primary, Table old) {
            for (int i = 0; i < old.capacity; i++) {
                long slot = old.slotAddress(i);
                for (;;) {
                    int state = old.stateVolatile(slot);
                    if (state == LIVE) {
                        copyStableLive(primary, old, slot);
                        break;
                    }
                    if (state == RESERVED) {
                        if (old.activeMutations.get() != 0L) {
                            waitReserved(counters);
                            continue;
                        }
                        continue;
                    }
                    break;
                }
            }
        }

        final long exactVisibleSize(View v) {
            long count = 0L;
            for (int i = 0; i < v.primary.capacity; i++) {
                byte c = v.primary.ctrl(i);
                if ((c & 0x80) != 0 && (c & 0x40) == 0) {
                    long slot = v.primary.slotAddress(i);
                    if (v.primary.stateVolatile(slot) == LIVE) {
                        count++;
                    }
                }
            }
            if (v.old != null) {
                for (int i = 0; i < v.old.capacity; i++) {
                    byte c = v.old.ctrl(i);
                    if ((c & 0x80) != 0 && (c & 0x40) == 0) {
                        long slot = v.old.slotAddress(i);
                        if (v.old.stateVolatile(slot) == LIVE
                                && v.primary.searchDynamic(v.old.key(slot)).kind == SearchKind.ABSENT) {
                            count++;
                        }
                    }
                }
            }
            return count;
        }
    }

    private static final class Table implements AutoCloseable {
        final int capacity;
        final int mask;
        final boolean dynamic;
        final DirectMemory ctrl;
        final DirectMemory entries;
        final PaddedCounter used = new PaddedCounter();
        final PaddedCounter deleteCount = new PaddedCounter();
        final AtomicLong activeMutations = new AtomicLong();
        final AtomicLong mutationEpoch = new AtomicLong();
        volatile boolean closed;

        Table(int capacity, boolean dynamic) {
            this.capacity = normalizeCapacity(capacity);
            this.mask = this.capacity - 1;
            this.dynamic = dynamic;
            DirectMemory c = null;
            DirectMemory e = null;
            boolean ok = false;
            try {
                c = new DirectMemory(this.capacity);
                e = new DirectMemory((long) this.capacity << 4);
                c.clear();
                e.clear();
                c.preTouch();
                e.preTouch();
                ok = true;
            } finally {
                if (!ok) {
                    if (c != null) c.close();
                    if (e != null) e.close();
                }
            }
            this.ctrl = c;
            this.entries = e;
        }

        int getImmutable(long key, int missing) {
            long slot = findImmutable(key);
            return slot >= 0L ? valueVolatile(slot) : missing;
        }

        long findImmutable(long key) {
            long hash = mix(key);
            int tag = liveTag(hash);
            int home = (int) hash & mask;
            for (;;) {
                int group = home & ~7;
                int startLane = home & 7;
                int scanned = 0;
                while (scanned < capacity) {
                    long word = ctrlWord(group);
                    long liveMask = matchByte(word, tag);
                    long emptyMask = matchByte(word, 0);
                    int lanes = Math.min(8 - startLane, capacity - scanned);
                    long laneMask = laneMask(startLane, lanes);
                    liveMask &= laneMask;
                    emptyMask &= laneMask;
                    while (liveMask != 0L) {
                        int lane = firstLane(liveMask, startLane);
                        int index = (group + lane) & mask;
                        long slot = slotAddress(index);
                        if (key(slot) == key) {
                            int state = stateVolatile(slot);
                            if (state == LIVE) {
                                return slot;
                            }
                            if (state == RESERVED) {
                                waitReservedSlot();
                                break;
                            }
                        }
                        liveMask &= liveMask - 1L;
                    }
                    if (liveMask != 0L) {
                        break;
                    }
                    while (emptyMask != 0L) {
                        int lane = firstLane(emptyMask, startLane);
                        int index = (group + lane) & mask;
                        long slot = slotAddress(index);
                        if (stateVolatile(slot) == EMPTY) {
                            return -1L;
                        }
                        emptyMask &= emptyMask - 1L;
                    }
                    if (emptyMask != 0L) {
                        break;
                    }
                    scanned += lanes;
                    group = (group + 8) & mask;
                    startLane = 0;
                }
                if (scanned >= capacity) {
                    return -1L;
                }
            }
        }

        SearchResult searchDynamic(long key) {
            long hash = mix(key);
            int liveTag = liveTag(hash);
            int deleteTag = deleteTagFromHash(hash);
            int home = (int) hash & mask;
            for (;;) {
                int group = home & ~7;
                int startLane = home & 7;
                int scanned = 0;
                while (scanned < capacity) {
                    long word = ctrlWord(group);
                    long liveMask = matchByte(word, liveTag);
                    long deleteMask = matchByte(word, deleteTag);
                    long emptyMask = matchByte(word, 0);
                    int lanes = Math.min(8 - startLane, capacity - scanned);
                    long laneMask = laneMask(startLane, lanes);
                    liveMask &= laneMask;
                    deleteMask &= laneMask;
                    emptyMask &= laneMask;
                    long candidates = liveMask | deleteMask;
                    while (candidates != 0L) {
                        int lane = firstLane(candidates, startLane);
                        int index = (group + lane) & mask;
                        long slot = slotAddress(index);
                        if (key(slot) == key) {
                            int state = stateVolatile(slot);
                            if (state == LIVE) return new SearchResult(SearchKind.LIVE, slot, index);
                            if (state == DELETE) return new SearchResult(SearchKind.DELETE, slot, index);
                            if (state == RESERVED) {
                                waitReservedSlot();
                                break;
                            }
                        }
                        candidates &= candidates - 1L;
                    }
                    if (candidates != 0L) {
                        break;
                    }
                    while (emptyMask != 0L) {
                        int lane = firstLane(emptyMask, startLane);
                        int index = (group + lane) & mask;
                        long slot = slotAddress(index);
                        if (stateVolatile(slot) == EMPTY) {
                            return SearchResult.ABSENT;
                        }
                        emptyMask &= emptyMask - 1L;
                    }
                    if (emptyMask != 0L) {
                        break;
                    }
                    scanned += lanes;
                    group = (group + 8) & mask;
                    startLane = 0;
                }
                if (scanned >= capacity) {
                    return SearchResult.ABSENT;
                }
            }
        }

        long findDynamicLive(long key) {
            SearchResult result = searchDynamic(key);
            return result.kind == SearchKind.LIVE ? result.slot : -1L;
        }

        boolean putBuild(long key, int value) {
            long hash = mix(key);
            int tag = liveTag(hash);
            int home = (int) hash & mask;
            int group = home & ~7;
            int startLane = home & 7;
            int scanned = 0;
            while (scanned < capacity) {
                long word = ctrlWord(group);
                long emptyMask = matchByte(word, 0);
                int lanes = Math.min(8 - startLane, capacity - scanned);
                emptyMask &= laneMask(startLane, lanes);
                if (emptyMask != 0L) {
                    int lane = firstLane(emptyMask, startLane);
                    int index = (group + lane) & mask;
                    long slot = slotAddress(index);
                    putKey(slot, key);
                    putValue(slot, value);
                    putStateRelease(slot, LIVE);
                    U.storeFence();
                    putCtrl(index, tag);
                    used.increment();
                    return true;
                }
                scanned += lanes;
                group = (group + 8) & mask;
                startLane = 0;
            }
            return false;
        }

        PutResult putFixed(long key, int value, boolean absentOnly, Counters counters) {
            return putLive(key, value, absentOnly, false, counters);
        }

        PutResult putDynamic(long key, int value, boolean absentOnly, Counters counters) {
            return putLive(key, value, absentOnly, true, counters);
        }

        PutResult copyLiveIfAbsent(long key, int value, Counters counters) {
            SearchResult existing = searchDynamic(key);
            if (existing.kind == SearchKind.LIVE || existing.kind == SearchKind.DELETE) {
                return PutResult.PRESENT;
            }
            return putLive(key, value, true, true, counters);
        }

        PutResult putDeleteMarker(long key, Counters counters) {
            long hash = mix(key);
            int deleteTag = deleteTagFromHash(hash);
            int home = (int) hash & mask;
            int group = home & ~7;
            int startLane = home & 7;
            int scanned = 0;
            while (scanned < capacity) {
                long word = ctrlWord(group);
                long liveMask = matchByte(word, liveTag(hash));
                long deleteMask = matchByte(word, deleteTag);
                long emptyMask = matchByte(word, 0);
                int lanes = Math.min(8 - startLane, capacity - scanned);
                long laneMask = laneMask(startLane, lanes);
                liveMask &= laneMask;
                deleteMask &= laneMask;
                emptyMask &= laneMask;
                while (liveMask != 0L) {
                    int lane = firstLane(liveMask, startLane);
                    int index = (group + lane) & mask;
                    long slot = slotAddress(index);
                    if (key(slot) == key) {
                        int state = stateVolatile(slot);
                        if (state == LIVE) {
                            if (!casState(slot, LIVE, DELETE)) {
                                counters.failedCas.increment();
                                return PutResult.RETRY;
                            }
                            U.storeFence();
                            putCtrl(index, deleteTag);
                            deleteCount.increment();
                            return PutResult.INSERTED;
                        }
                        if (state == DELETE) {
                            return PutResult.PRESENT;
                        }
                        if (state == RESERVED) {
                            waitReserved(counters);
                            return PutResult.RETRY;
                        }
                    }
                    liveMask &= liveMask - 1L;
                }
                while (deleteMask != 0L) {
                    int lane = firstLane(deleteMask, startLane);
                    int index = (group + lane) & mask;
                    long slot = slotAddress(index);
                    if (key(slot) == key) {
                        int state = stateVolatile(slot);
                        if (state == DELETE) {
                            return PutResult.PRESENT;
                        }
                        if (state == LIVE) {
                            return PutResult.RETRY;
                        }
                        if (state == RESERVED) {
                            waitReserved(counters);
                            return PutResult.RETRY;
                        }
                    }
                    deleteMask &= deleteMask - 1L;
                }
                if (emptyMask != 0L) {
                    int lane = firstLane(emptyMask, startLane);
                    int index = (group + lane) & mask;
                    long slot = slotAddress(index);
                    if (casState(slot, EMPTY, RESERVED)) {
                        putKey(slot, key);
                        U.storeFence();
                        putCtrl(index, deleteTag);
                        U.storeFence();
                        putStateRelease(slot, DELETE);
                        used.increment();
                        deleteCount.increment();
                        return PutResult.INSERTED;
                    }
                    counters.failedCas.increment();
                    return PutResult.RETRY;
                }
                scanned += lanes;
                group = (group + 8) & mask;
                startLane = 0;
            }
            return PutResult.FULL;
        }

        private PutResult putLive(long key, int value, boolean absentOnly, boolean allowDelete, Counters counters) {
            long hash = mix(key);
            int liveTag = liveTag(hash);
            int deleteTag = deleteTagFromHash(hash);
            int home = (int) hash & mask;
            int group = home & ~7;
            int startLane = home & 7;
            int scanned = 0;
            while (scanned < capacity) {
                long word = ctrlWord(group);
                long liveMask = matchByte(word, liveTag);
                long deleteMask = allowDelete ? matchByte(word, deleteTag) : 0L;
                long emptyMask = matchByte(word, 0);
                int lanes = Math.min(8 - startLane, capacity - scanned);
                long laneMask = laneMask(startLane, lanes);
                liveMask &= laneMask;
                deleteMask &= laneMask;
                emptyMask &= laneMask;
                long candidates = liveMask | deleteMask;
                while (candidates != 0L) {
                    int lane = firstLane(candidates, startLane);
                    int index = (group + lane) & mask;
                    long slot = slotAddress(index);
                    if (key(slot) == key) {
                        int state = stateVolatile(slot);
                        if (state == LIVE) {
                            if (absentOnly) return PutResult.PRESENT;
                            putValue(slot, value);
                            return PutResult.UPDATED;
                        }
                        if (allowDelete && state == DELETE) {
                            if (!casState(slot, DELETE, RESERVED)) {
                                counters.failedCas.increment();
                                return PutResult.RETRY;
                            }
                            putValue(slot, value);
                            putStateRelease(slot, LIVE);
                            U.storeFence();
                            putCtrl(index, liveTag);
                            deleteCount.decrement();
                            return PutResult.REINSERTED;
                        }
                        if (state == RESERVED) {
                            waitReserved(counters);
                            return PutResult.RETRY;
                        }
                    }
                    candidates &= candidates - 1L;
                }
                if (emptyMask != 0L) {
                    int lane = firstLane(emptyMask, startLane);
                    int index = (group + lane) & mask;
                    long slot = slotAddress(index);
                    int state = stateVolatile(slot);
                    if (state == RESERVED) {
                        waitReserved(counters);
                        return PutResult.RETRY;
                    }
                    if (state != EMPTY) {
                        return PutResult.RETRY;
                    }
                    if (!casState(slot, EMPTY, RESERVED)) {
                        counters.failedCas.increment();
                        return PutResult.RETRY;
                    }
                    putKey(slot, key);
                    U.storeFence();
                    putCtrl(index, liveTag);
                    putValue(slot, value);
                    U.storeFence();
                    putStateRelease(slot, LIVE);
                    used.increment();
                    return PutResult.INSERTED;
                }
                scanned += lanes;
                group = (group + 8) & mask;
                startLane = 0;
            }
            return PutResult.FULL;
        }

        byte ctrl(int index) { return U.getByte(ctrl.address + index); }
        long ctrlWord(int group) { return U.getLong(ctrl.address + group); }
        void putCtrl(int index, int value) { U.putByte(ctrl.address + index, (byte) value); }
        long slotAddress(int index) { return entries.address + ((long) index << 4); }
        long key(long slot) { return U.getLong(slot + KEY_OFFSET); }
        void putKey(long slot, long key) { U.putLong(slot + KEY_OFFSET, key); }
        int valueVolatile(long slot) { return U.getIntVolatile(null, slot + VALUE_OFFSET); }
        void putValue(long slot, int value) { U.putInt(slot + VALUE_OFFSET, value); }
        void putValueVolatile(long slot, int value) { U.putIntVolatile(null, slot + VALUE_OFFSET, value); }
        boolean casValue(long slot, int expected, int update) { return U.compareAndSwapInt(null, slot + VALUE_OFFSET, expected, update); }
        int stateVolatile(long slot) { return U.getIntVolatile(null, slot + STATE_OFFSET); }
        void putStateRelease(long slot, int state) { U.putOrderedInt(null, slot + STATE_OFFSET, state); }
        boolean casState(long slot, int expected, int update) { return U.compareAndSwapInt(null, slot + STATE_OFFSET, expected, update); }
        void enterMutation() {
            activeMutations.incrementAndGet();
            mutationEpoch.incrementAndGet();
        }
        void exitMutation() { activeMutations.decrementAndGet(); }
        int liveTag(long hash) { return dynamic ? (0x80 | (int) ((hash >>> 56) & 0x3f)) : (0x80 | (int) ((hash >>> 57) & 0x7f)); }
        int deleteTagFromHash(long hash) { return 0xC0 | (int) ((hash >>> 56) & 0x3f); }
        int deleteTagForKey(long key) { return deleteTagFromHash(mix(key)); }
        long bytes() { return ctrl.bytes + entries.bytes; }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                ctrl.close();
                entries.close();
            }
        }
    }

    private record MutationContext(View view, Table table) {
    }

    private static final class DirectMemory implements AutoCloseable {
        final long raw;
        final long address;
        final long bytes;
        boolean closed;

        DirectMemory(long bytes) {
            this.bytes = bytes;
            this.raw = U.allocateMemory(bytes + 63L);
            this.address = (raw + 63L) & ~63L;
        }

        void clear() { U.setMemory(address, bytes, (byte) 0); }

        void preTouch() {
            for (long p = 0L; p < bytes; p += 4096L) {
                U.putByte(address + p, U.getByte(address + p));
            }
            if (bytes > 0L) {
                U.putByte(address + bytes - 1L, U.getByte(address + bytes - 1L));
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                U.freeMemory(raw);
            }
        }
    }

    private interface Inspectable { Long2IntInspector inspector(); }

    private enum PutResult { INSERTED, REINSERTED, UPDATED, PRESENT, FULL, RETRY }
    private enum SearchKind { LIVE, DELETE, ABSENT }
    private record SearchResult(SearchKind kind, long slot, int index) { static final SearchResult ABSENT = new SearchResult(SearchKind.ABSENT, -1L, -1); }
    private record VisibleResult(SearchKind kind, View view, Table table, long slot) { }
    private record View(Table primary, Table old, AtomicLong migrationCursor, AtomicLong migrationDone, AtomicLong finishGate) { }

    private static final class Counters {
        final PaddedCounter failedCas = new PaddedCounter();
        final PaddedCounter reservedWaits = new PaddedCounter();
        final PaddedCounter reservedParks = new PaddedCounter();
    }

    private static class PaddedCounter {
        @SuppressWarnings("unused") long p0, p1, p2, p3, p4, p5, p6;
        private final AtomicLong value = new AtomicLong();
        @SuppressWarnings("unused") long q0, q1, q2, q3, q4, q5, q6;
        long get() { return value.get(); }
        void increment() { value.incrementAndGet(); }
        void decrement() { value.decrementAndGet(); }
        void add(long delta) { value.addAndGet(delta); }
    }

    private static final class HazardRegistry {
        private final HazardCell[] cells;
        private final AtomicLong next = new AtomicLong();
        private final ThreadLocal<HazardCell> local = ThreadLocal.withInitial(this::register);

        HazardRegistry(int capacity) {
            cells = new HazardCell[capacity];
            for (int i = 0; i < cells.length; i++) cells[i] = new HazardCell();
        }

        HazardCell cell() { return local.get(); }
        void clear() { local.get().view = null; }

        boolean protects(Table table) {
            for (HazardCell cell : cells) {
                View v = cell.view;
                if (v != null && (v.primary == table || v.old == table)) return true;
            }
            return false;
        }

        private HazardCell register() {
            long id = next.getAndIncrement();
            if (id >= cells.length) {
                throw new IllegalStateException("Long2Int hazard registry capacity exceeded");
            }
            return cells[(int) id];
        }
    }

    private static final class HazardCell {
        @SuppressWarnings("unused") long p0, p1, p2, p3, p4, p5, p6;
        volatile View view;
        @SuppressWarnings("unused") long q0, q1, q2, q3, q4, q5, q6;
    }

    private static final class Inspector implements Long2IntInspector {
        private final Object target;
        Inspector(Object target) { this.target = target; }

        @Override
        public Long2IntStats stats() {
            if (target instanceof AbstractSingleTable single) {
                return stats(single.table, null, single.size.get(), single.counters, 0L, 0L, 0L, false);
            }
            AbstractDynamicMap map = (AbstractDynamicMap) target;
            View v = map.currentView();
            long capacity = v.primary.capacity + (v.old == null ? 0L : v.old.capacity);
            return stats(v.primary, v.old, map.exactVisibleSize(v), map.counters, map.retiredBytes.get(), map.resizeCount.get(), map.rebuildCount.get(), v.old != null, capacity);
        }

        private Long2IntStats stats(Table primary, Table old, long size, Counters counters, long retiredBytes, long resizeCount, long rebuildCount, boolean resizing) {
            long capacity = primary.capacity + (old == null ? 0L : old.capacity);
            return stats(primary, old, size, counters, retiredBytes, resizeCount, rebuildCount, resizing, capacity);
        }

        private Long2IntStats stats(Table primary, Table old, long size, Counters counters, long retiredBytes, long resizeCount, long rebuildCount, boolean resizing, long capacity) {
            long used = primary.used.get() + (old == null ? 0L : old.used.get());
            long deletes = primary.deleteCount.get() + (old == null ? 0L : old.deleteCount.get());
            long bytes = primary.bytes() + (old == null ? 0L : old.bytes());
            return new Long2IntStats(size, -1L, capacity, 1, bytes, retiredBytes, deletes, used,
                    capacity == 0L ? 0.0 : (double) used / (double) capacity, resizing, resizeCount, rebuildCount,
                    counters.reservedWaits.get(), counters.reservedParks.get(), counters.failedCas.get());
        }

        @Override
        public Long2IntHealth healthCheck() {
            List<String> problems = new ArrayList<>();
            if (target instanceof AbstractSingleTable single) {
                checkTable(single.table, problems);
            } else {
                View v = ((AbstractDynamicMap) target).currentView();
                checkTable(v.primary, problems);
                if (v.old != null) checkTable(v.old, problems);
            }
            return new Long2IntHealth(problems.isEmpty(), Collections.unmodifiableList(problems));
        }

        @Override
        public long exactSize() {
            if (target instanceof AbstractSingleTable single) {
                return exact(single.table, false);
            }
            View v = ((AbstractDynamicMap) target).currentView();
            long count = exact(v.primary, true);
            if (v.old != null) {
                for (int i = 0; i < v.old.capacity; i++) {
                    byte c = v.old.ctrl(i);
                    if ((c & 0x80) != 0 && (c & 0x40) == 0) {
                        long slot = v.old.slotAddress(i);
                        if (v.old.stateVolatile(slot) == LIVE && v.primary.searchDynamic(v.old.key(slot)).kind == SearchKind.ABSENT) count++;
                    }
                }
            }
            return count;
        }

        private static long exact(Table table, boolean dynamic) {
            long count = 0L;
            for (int i = 0; i < table.capacity; i++) {
                byte c = table.ctrl(i);
                if ((c & 0x80) != 0 && (!dynamic || (c & 0x40) == 0) && table.stateVolatile(table.slotAddress(i)) == LIVE) count++;
            }
            return count;
        }

        private static void checkTable(Table table, List<String> problems) {
            if ((table.ctrl.address & 63L) != 0L) problems.add("ctrl is not 64-byte aligned");
            if ((table.entries.address & 63L) != 0L) problems.add("entries are not 64-byte aligned");
            for (int i = 0; i < table.capacity; i++) {
                byte c = table.ctrl(i);
                int state = table.stateVolatile(table.slotAddress(i));
                if (c == 0 && state != EMPTY) problems.add("ctrl EMPTY with non-empty state at " + i);
                if ((c & 0x80) != 0 && state != LIVE && state != DELETE && state != RESERVED) problems.add("live/delete ctrl with invalid state at " + i);
            }
        }
    }

    private static void waitReservedSlot() {
        for (int i = 0; i < RESERVED_SPINS; i++) Thread.onSpinWait();
        Thread.yield();
    }

    private static void waitReserved(Counters counters) {
        counters.reservedWaits.increment();
        for (int i = 0; i < RESERVED_SPINS; i++) Thread.onSpinWait();
        if ((counters.reservedWaits.get() & 63L) == 0L) {
            counters.reservedParks.increment();
            Thread.yield();
        }
    }

    private static long zeroByteMask(long x) { return (x - ONES) & ~x & HIGH; }
    private static long matchByte(long word, int b) { return zeroByteMask(word ^ ((b & 0xffL) * ONES)); }
    private static long laneMask(int startLane, int lanes) {
        if (lanes <= 0) {
            return 0L;
        }
        long mask = lanes == 8 ? -1L : ((1L << (lanes << 3)) - 1L);
        return mask << (startLane << 3);
    }
    private static int firstLane(long mask, int startLane) {
        long ordered = Long.rotateRight(mask, startLane << 3);
        int logicalLane = Long.numberOfTrailingZeros(ordered) >>> 3;
        return (startLane + logicalLane) & 7;
    }
    private static long mix(long z) { z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L; z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL; return z ^ (z >>> 31); }

    private static int tableCapacity(int expectedSize) {
        if (expectedSize < 0) throw new IllegalArgumentException("expectedSize < 0");
        long needed = Math.max(MIN_CAPACITY, Math.multiplyExact((long) Math.max(1, expectedSize), 2L));
        return normalizeCapacity(needed);
    }

    private static int normalizeCapacity(long requested) {
        long cap = MIN_CAPACITY;
        while (cap < requested) cap <<= 1;
        if (cap > (1L << 30)) throw new IllegalArgumentException("capacity too large: " + requested);
        return (int) cap;
    }

    private static int safeDouble(int value) {
        if (value >= (1 << 30)) throw new IllegalStateException("Long2Int table reached maximum capacity");
        return value << 1;
    }

    private static Unsafe unsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

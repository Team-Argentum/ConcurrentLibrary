package net.sixik.concurrent_library.long2int;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SingleThreadLong2Int {
    private static final Unsafe U = unsafe();
    private static final long PHI = 0x9E3779B97F4A7C15L;
    private static final long ONES = 0x0101010101010101L;
    private static final long HIGH = 0x8080808080808080L;
    private static final int MIN_CAPACITY = 8;
    private static final int EMPTY = 0x00;
    private static final int DELETED = 0x40;
    private static final int DIRECT_ENTRY_SHIFT = 4;
    private static final long DIRECT_KEY_OFFSET = 0L;
    private static final long DIRECT_VALUE_OFFSET = 8L;

    private SingleThreadLong2Int() {
    }

    static Long2IntLookup lookup(long[] keys, int[] values, SingleThreadLong2IntBuilder builder) {
        if (keys.length != values.length) {
            throw new IllegalArgumentException("keys and values lengths differ");
        }
        SingleThreadLong2IntBuilder copy = builder.copy();
        Long2IntBackend backend = resolveBackend(copy, keys.length);
        AbstractLookup lookup = switch (backend) {
            case HEAP -> new SingleThreadHeapLong2IntLookup(capacity(keys.length, copy.loadFactor), copy);
            case DIRECT -> new SingleThreadDirectLong2IntLookup(capacity(keys.length, copy.loadFactor), copy);
            case PANAMA -> throw panamaUnavailable();
            case AUTO -> throw new AssertionError("AUTO backend was not resolved");
        };
        boolean ok = false;
        try {
            for (int i = 0; i < keys.length; i++) {
                lookup.putBuild(keys[i], values[i]);
            }
            ok = true;
            return lookup;
        } finally {
            if (!ok) {
                lookup.close();
            }
        }
    }

    static Long2IntSingleThreadMap fixed(SingleThreadLong2IntBuilder builder) {
        Long2IntBackend backend = resolveBackend(builder, builder.expectedSize);
        int capacity = capacity(builder.expectedSize, builder.loadFactor);
        return switch (backend) {
            case HEAP -> new SingleThreadHeapLong2IntFixedMap(capacity, builder);
            case DIRECT -> new SingleThreadDirectLong2IntFixedMap(capacity, builder);
            case PANAMA -> throw panamaUnavailable();
            case AUTO -> throw new AssertionError("AUTO backend was not resolved");
        };
    }

    static Long2IntMap mutable(SingleThreadLong2IntBuilder builder) {
        Long2IntBackend backend = resolveBackend(builder, builder.expectedSize);
        int capacity = capacity(builder.expectedSize, builder.loadFactor);
        return switch (backend) {
            case HEAP -> new SingleThreadHeapLong2IntMutableMap(capacity, builder);
            case DIRECT -> new SingleThreadDirectLong2IntMutableMap(capacity, builder);
            case PANAMA -> throw panamaUnavailable();
            case AUTO -> throw new AssertionError("AUTO backend was not resolved");
        };
    }

    private static Long2IntBackend resolveBackend(SingleThreadLong2IntBuilder builder, int expectedSize) {
        Long2IntBackend backend = builder.backend;
        if (backend == Long2IntBackend.AUTO) {
            backend = expectedSize < builder.heapDirectThreshold ? Long2IntBackend.HEAP : Long2IntBackend.DIRECT;
        }
        if ((backend == Long2IntBackend.DIRECT || backend == Long2IntBackend.PANAMA)
                && ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalStateException("SingleThreadLong2Int direct/Panama backends require little-endian native order");
        }
        return backend;
    }

    private static UnsupportedOperationException panamaUnavailable() {
        return new UnsupportedOperationException("SingleThreadLong2Int Panama backend requires a JDK 21 MemorySegment implementation");
    }

    private abstract static class AbstractLookup implements Long2IntLookup, Long2IntInternalInspectable {
        final int capacity;
        final int mask;
        final int groupCount;
        final int groupMask;
        final int shift;
        final int missingValue;
        final Long2IntHashing hashing;
        final boolean closeChecks;
        int size;
        boolean closed;

        AbstractLookup(int capacity, SingleThreadLong2IntBuilder builder) {
            this.capacity = normalizeCapacity(capacity);
            this.mask = this.capacity - 1;
            this.groupCount = this.capacity >>> 3;
            this.groupMask = this.groupCount - 1;
            this.shift = 64 - Integer.numberOfTrailingZeros(this.capacity);
            this.missingValue = builder.missingValue;
            this.hashing = builder.hashing;
            this.closeChecks = builder.closeChecks;
        }

        @Override
        public final int get(long key) {
            checkOpen();
            return getOrMissing(key, missingValue);
        }

        @Override
        public final int getOrDefault(long key, int defaultValue) {
            checkOpen();
            return getOrMissing(key, defaultValue);
        }

        @Override
        public final boolean containsKey(long key) {
            checkOpen();
            return findIndex(key) >= 0;
        }

        @Override
        public final long size() {
            checkOpen();
            return size;
        }

        final void checkOpen() {
            if (closeChecks && closed) {
                throw new IllegalStateException("Long2Int collection is closed");
            }
        }

        final long hash(long key) {
            return hashing == Long2IntHashing.FIBONACCI ? key * PHI : mix64(key);
        }

        final int home(long hash) {
            return hashing == Long2IntHashing.FIBONACCI ? (int) (hash >>> shift) : (int) hash & mask;
        }

        final int tag(long hash) {
            return 0x80 | (int) ((hash >>> 8) & 0x7f);
        }

        abstract int getOrMissing(long key, int missing);

        abstract int findIndex(long key);

        abstract void putBuild(long key, int value);

        public abstract long backendBytes();
    }

    private abstract static class AbstractFixed extends AbstractLookup implements Long2IntSingleThreadMap {
        private final Long2IntBackend backend;
        private final Cursor cachedCursor = new Cursor();

        AbstractFixed(int capacity, SingleThreadLong2IntBuilder builder, Long2IntBackend backend) {
            super(capacity, builder);
            this.backend = backend;
        }

        @Override
        public final boolean put(long key, int value) {
            checkOpen();
            return putFixed(key, value, false);
        }

        @Override
        public final boolean putIfAbsent(long key, int value) {
            checkOpen();
            return putFixed(key, value, true);
        }

        @Override
        public final boolean compareAndSet(long key, int expected, int update) {
            checkOpen();
            int index = findIndex(key);
            if (index < 0 || valueAt(index) != expected) {
                return false;
            }
            setValue(index, update);
            return true;
        }

        @Override
        public final boolean replace(long key, int expected, int update) {
            return compareAndSet(key, expected, update);
        }

        @Override
        public final boolean addIfPresent(long key, int delta) {
            checkOpen();
            int index = findIndex(key);
            if (index < 0) {
                return false;
            }
            setValue(index, valueAt(index) + delta);
            return true;
        }

        @Override
        public final int getAndAddIfPresent(long key, int delta, int missingReturn) {
            checkOpen();
            int index = findIndex(key);
            if (index < 0) {
                return missingReturn;
            }
            int old = valueAt(index);
            setValue(index, old + delta);
            return old;
        }

        @Override
        public final long capacity() {
            checkOpen();
            return capacity;
        }

        @Override
        public final Long2IntBackend backend() {
            return backend;
        }

        @Override
        public final LongIntCursor cursor() {
            return new Cursor();
        }

        @Override
        public final LongIntCursor cachedCursor() {
            return cachedCursor.reset();
        }

        @Override
        public final Long2IntInspector inspector() {
            return new SingleThreadInspector(this, false);
        }

        abstract boolean putFixed(long key, int value, boolean absentOnly);

        abstract long keyAt(int index);

        abstract int valueAt(int index);

        abstract void setValue(int index, int value);

        abstract boolean isLiveIndex(int index);

        abstract long liveMask(int group);

        private final class Cursor implements LongIntCursor {
            private int nextGroup;
            private long live;
            private int index = -1;

            @Override
            public LongIntCursor reset() {
                nextGroup = 0;
                live = 0L;
                index = -1;
                return this;
            }

            @Override
            public boolean next() {
                checkOpen();
                for (;;) {
                    if (live != 0L) {
                        int lane = Long.numberOfTrailingZeros(live) >>> 3;
                        index = ((nextGroup - 1) << 3) | lane;
                        live &= live - 1L;
                        return true;
                    }
                    if (nextGroup >= groupCount) {
                        return false;
                    }
                    live = liveMask(nextGroup);
                    nextGroup++;
                }
            }

            @Override
            public long key() {
                return keyAt(index);
            }

            @Override
            public int value() {
                return valueAt(index);
            }
        }
    }

    private abstract static class AbstractMutable extends AbstractFixed implements Long2IntMap {
        int used;
        int deleted;

        AbstractMutable(int capacity, SingleThreadLong2IntBuilder builder, Long2IntBackend backend) {
            super(capacity, builder, backend);
        }

        @Override
        public final boolean remove(long key) {
            checkOpen();
            int index = findIndex(key);
            if (index < 0) {
                return false;
            }
            markDeleted(index);
            size--;
            deleted++;
            return true;
        }

        @Override
        public final int removeAndGetOld(long key, int missingReturn) {
            checkOpen();
            int index = findIndex(key);
            if (index < 0) {
                return missingReturn;
            }
            int old = valueAt(index);
            markDeleted(index);
            size--;
            deleted++;
            return old;
        }

        @Override
        public final boolean resizeInProgress() {
            return false;
        }

        @Override
        public final void completeResize() {
            checkOpen();
        }

        abstract void markDeleted(int index);
    }

    static final class SingleThreadHeapLong2IntLookup extends AbstractLookup {
        private long[] ctrlWords;
        private long[] keys;
        private int[] values;

        SingleThreadHeapLong2IntLookup(int capacity, SingleThreadLong2IntBuilder builder) {
            super(capacity, builder);
            this.ctrlWords = new long[groupCount];
            this.keys = new long[this.capacity];
            this.values = new int[this.capacity];
        }

        @Override
        int getOrMissing(long key, int missing) {
            int index = findIndex(key);
            return index >= 0 ? values[index] : missing;
        }

        @Override
        int findIndex(long key) {
            long hash = hash(key);
            int home = home(hash);
            int endGroup = home >>> 3;
            int endLane = home & 7;
            int tag = tag(hash);
            int found = findNoDelete(key, tag, endGroup, endLane);
            return found;
        }

        @Override
        void putBuild(long key, int value) {
            int existing = findIndex(key);
            if (existing >= 0) {
                values[existing] = value;
                return;
            }
            if (!putNew(key, value)) {
                throw new IllegalStateException("lookup table is full");
            }
        }

        private boolean putNew(long key, int value) {
            long hash = hash(key);
            int home = home(hash);
            int endGroup = home >>> 3;
            int endLane = home & 7;
            int tag = tag(hash);
            return insertNoDelete(key, value, tag, endGroup, endLane);
        }

        private int findNoDelete(long key, int tag, int endGroup, int endLane) {
            int group = endGroup;
            long mask = endLane == 0 ? -1L : laneMask(endLane, 8 - endLane);
            while (group < groupCount) {
                long word = ctrlWords[group];
                long candidates = matchByte(word, tag) & mask;
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (group << 3) | lane;
                    if (keys[index] == key) {
                        return index;
                    }
                    candidates &= candidates - 1L;
                }
                if ((matchByte(word, EMPTY) & mask) != 0L) {
                    return -1;
                }
                group++;
                mask = -1L;
            }
            group = 0;
            while (group < endGroup) {
                long word = ctrlWords[group];
                long candidates = matchByte(word, tag);
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (group << 3) | lane;
                    if (keys[index] == key) {
                        return index;
                    }
                    candidates &= candidates - 1L;
                }
                if (matchByte(word, EMPTY) != 0L) {
                    return -1;
                }
                group++;
            }
            if (endLane > 0) {
                long word = ctrlWords[endGroup];
                long tailMask = laneMask(0, endLane);
                long candidates = matchByte(word, tag) & tailMask;
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (endGroup << 3) | lane;
                    if (keys[index] == key) {
                        return index;
                    }
                    candidates &= candidates - 1L;
                }
                if ((matchByte(word, EMPTY) & tailMask) != 0L) {
                    return -1;
                }
            }
            return -1;
        }

        private boolean insertNoDelete(long key, int value, int tag, int endGroup, int endLane) {
            int group = endGroup;
            long mask = endLane == 0 ? -1L : laneMask(endLane, 8 - endLane);
            while (group < groupCount) {
                long word = ctrlWords[group];
                long empty = matchByte(word, EMPTY) & mask;
                if (empty != 0L) {
                    int lane = firstLane(empty);
                    int index = (group << 3) | lane;
                    keys[index] = key;
                    values[index] = value;
                    ctrlWords[group] = setLane(word, lane, tag);
                    size++;
                    return true;
                }
                group++;
                mask = -1L;
            }
            group = 0;
            while (group < endGroup) {
                long word = ctrlWords[group];
                long empty = matchByte(word, EMPTY);
                if (empty != 0L) {
                    int lane = firstLane(empty);
                    int index = (group << 3) | lane;
                    keys[index] = key;
                    values[index] = value;
                    ctrlWords[group] = setLane(word, lane, tag);
                    size++;
                    return true;
                }
                group++;
            }
            if (endLane > 0) {
                long word = ctrlWords[endGroup];
                long empty = matchByte(word, EMPTY) & laneMask(0, endLane);
                if (empty != 0L) {
                    int lane = firstLane(empty);
                    int index = (endGroup << 3) | lane;
                    keys[index] = key;
                    values[index] = value;
                    ctrlWords[endGroup] = setLane(word, lane, tag);
                    size++;
                    return true;
                }
            }
            return false;
        }

        @Override
        public long backendBytes() {
            return (long) ctrlWords.length << 3 | 0L;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                ctrlWords = null;
                keys = null;
                values = null;
            }
        }

        @Override
        public Long2IntInspector inspector() {
            return new SingleThreadInspector(this, false);
        }
    }

    static final class SingleThreadHeapLong2IntFixedMap extends AbstractFixed {
        private long[] ctrlWords;
        private long[] keys;
        private int[] values;

        SingleThreadHeapLong2IntFixedMap(int capacity, SingleThreadLong2IntBuilder builder) {
            super(capacity, builder, Long2IntBackend.HEAP);
            this.ctrlWords = new long[groupCount];
            this.keys = new long[this.capacity];
            this.values = new int[this.capacity];
        }

        @Override
        int getOrMissing(long key, int missing) {
            int index = findIndex(key);
            return index >= 0 ? values[index] : missing;
        }

        @Override
        int findIndex(long key) {
            long hash = hash(key);
            int home = home(hash);
            return findNoDelete(key, tag(hash), home >>> 3, home & 7);
        }

        @Override
        void putBuild(long key, int value) {
            if (!putFixed(key, value, false)) {
                throw new IllegalStateException("fixed table is full");
            }
        }

        @Override
        boolean putFixed(long key, int value, boolean absentOnly) {
            long hash = hash(key);
            int home = home(hash);
            int endGroup = home >>> 3;
            int endLane = home & 7;
            int tag = tag(hash);
            int group = endGroup;
            long mask = endLane == 0 ? -1L : laneMask(endLane, 8 - endLane);
            while (group < groupCount) {
                long word = ctrlWords[group];
                long candidates = matchByte(word, tag) & mask;
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (group << 3) | lane;
                    if (keys[index] == key) {
                        if (!absentOnly) {
                            values[index] = value;
                        }
                        return !absentOnly;
                    }
                    candidates &= candidates - 1L;
                }
                long empty = matchByte(word, EMPTY) & mask;
                if (empty != 0L) {
                    int lane = firstLane(empty);
                    int index = (group << 3) | lane;
                    keys[index] = key;
                    values[index] = value;
                    ctrlWords[group] = setLane(word, lane, tag);
                    size++;
                    return true;
                }
                group++;
                mask = -1L;
            }
            group = 0;
            while (group < endGroup) {
                long word = ctrlWords[group];
                long candidates = matchByte(word, tag);
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (group << 3) | lane;
                    if (keys[index] == key) {
                        if (!absentOnly) {
                            values[index] = value;
                        }
                        return !absentOnly;
                    }
                    candidates &= candidates - 1L;
                }
                long empty = matchByte(word, EMPTY);
                if (empty != 0L) {
                    int lane = firstLane(empty);
                    int index = (group << 3) | lane;
                    keys[index] = key;
                    values[index] = value;
                    ctrlWords[group] = setLane(word, lane, tag);
                    size++;
                    return true;
                }
                group++;
            }
            if (endLane > 0) {
                long word = ctrlWords[endGroup];
                long tailMask = laneMask(0, endLane);
                long candidates = matchByte(word, tag) & tailMask;
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (endGroup << 3) | lane;
                    if (keys[index] == key) {
                        if (!absentOnly) {
                            values[index] = value;
                        }
                        return !absentOnly;
                    }
                    candidates &= candidates - 1L;
                }
                long empty = matchByte(word, EMPTY) & tailMask;
                if (empty != 0L) {
                    int lane = firstLane(empty);
                    int index = (endGroup << 3) | lane;
                    keys[index] = key;
                    values[index] = value;
                    ctrlWords[endGroup] = setLane(word, lane, tag);
                    size++;
                    return true;
                }
            }
            return false;
        }

        private int findNoDelete(long key, int tag, int endGroup, int endLane) {
            int group = endGroup;
            long mask = endLane == 0 ? -1L : laneMask(endLane, 8 - endLane);
            while (group < groupCount) {
                long word = ctrlWords[group];
                long candidates = matchByte(word, tag) & mask;
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (group << 3) | lane;
                    if (keys[index] == key) {
                        return index;
                    }
                    candidates &= candidates - 1L;
                }
                if ((matchByte(word, EMPTY) & mask) != 0L) {
                    return -1;
                }
                group++;
                mask = -1L;
            }
            group = 0;
            while (group < endGroup) {
                long word = ctrlWords[group];
                long candidates = matchByte(word, tag);
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (group << 3) | lane;
                    if (keys[index] == key) {
                        return index;
                    }
                    candidates &= candidates - 1L;
                }
                if (matchByte(word, EMPTY) != 0L) {
                    return -1;
                }
                group++;
            }
            if (endLane > 0) {
                long word = ctrlWords[endGroup];
                long tailMask = laneMask(0, endLane);
                long candidates = matchByte(word, tag) & tailMask;
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (endGroup << 3) | lane;
                    if (keys[index] == key) {
                        return index;
                    }
                    candidates &= candidates - 1L;
                }
            }
            return -1;
        }

        @Override long keyAt(int index) { return keys[index]; }
        @Override int valueAt(int index) { return values[index]; }
        @Override void setValue(int index, int value) { values[index] = value; }
        @Override boolean isLiveIndex(int index) { return ((ctrlWords[index >>> 3] >>> ((index & 7) << 3)) & 0x80L) != 0L; }
        @Override long liveMask(int group) { return ctrlWords[group] & HIGH; }

        @Override
        public void forEach(LongIntConsumer consumer) {
            checkOpen();
            for (int group = 0; group < groupCount; group++) {
                long live = ctrlWords[group] & HIGH;
                while (live != 0L) {
                    int lane = firstLane(live);
                    int index = (group << 3) | lane;
                    consumer.accept(keys[index], values[index]);
                    live &= live - 1L;
                }
            }
        }

        @Override public long backendBytes() { return 13L * capacity; }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                ctrlWords = null;
                keys = null;
                values = null;
            }
        }
    }

    static final class SingleThreadHeapLong2IntMutableMap extends AbstractMutable {
        private long[] ctrlWords;
        private long[] keys;
        private int[] values;

        SingleThreadHeapLong2IntMutableMap(int capacity, SingleThreadLong2IntBuilder builder) {
            super(capacity, builder, Long2IntBackend.HEAP);
            ctrlWords = new long[groupCount];
            keys = new long[this.capacity];
            values = new int[this.capacity];
        }

        @Override int getOrMissing(long key, int missing) { int index = findIndex(key); return index >= 0 ? values[index] : missing; }

        @Override
        int findIndex(long key) {
            long hash = hash(key);
            int home = home(hash);
            return findMutable(key, tag(hash), home >>> 3, home & 7);
        }

        @Override void putBuild(long key, int value) { if (!putFixed(key, value, false)) throw new IllegalStateException("mutable table is full"); }

        @Override
        boolean putFixed(long key, int value, boolean absentOnly) {
            long hash = hash(key);
            int home = home(hash);
            int endGroup = home >>> 3;
            int endLane = home & 7;
            int tag = tag(hash);
            int firstDeleted = -1;
            int group = endGroup;
            long mask = endLane == 0 ? -1L : laneMask(endLane, 8 - endLane);
            while (group < groupCount) {
                long word = ctrlWords[group];
                long candidates = matchByte(word, tag) & mask;
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (group << 3) | lane;
                    if (keys[index] == key) {
                        if (!absentOnly) values[index] = value;
                        return !absentOnly;
                    }
                    candidates &= candidates - 1L;
                }
                if (firstDeleted < 0) {
                    long del = matchByte(word, DELETED) & mask;
                    if (del != 0L) firstDeleted = (group << 3) | firstLane(del);
                }
                long empty = matchByte(word, EMPTY) & mask;
                if (empty != 0L) return insertMutable(firstDeleted >= 0 ? firstDeleted : ((group << 3) | firstLane(empty)), key, value, tag, firstDeleted >= 0);
                group++;
                mask = -1L;
            }
            group = 0;
            while (group < endGroup) {
                long word = ctrlWords[group];
                long candidates = matchByte(word, tag);
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (group << 3) | lane;
                    if (keys[index] == key) {
                        if (!absentOnly) values[index] = value;
                        return !absentOnly;
                    }
                    candidates &= candidates - 1L;
                }
                if (firstDeleted < 0) {
                    long del = matchByte(word, DELETED);
                    if (del != 0L) firstDeleted = (group << 3) | firstLane(del);
                }
                long empty = matchByte(word, EMPTY);
                if (empty != 0L) return insertMutable(firstDeleted >= 0 ? firstDeleted : ((group << 3) | firstLane(empty)), key, value, tag, firstDeleted >= 0);
                group++;
            }
            if (endLane > 0) {
                long word = ctrlWords[endGroup];
                long tailMask = laneMask(0, endLane);
                long candidates = matchByte(word, tag) & tailMask;
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (endGroup << 3) | lane;
                    if (keys[index] == key) {
                        if (!absentOnly) values[index] = value;
                        return !absentOnly;
                    }
                    candidates &= candidates - 1L;
                }
                if (firstDeleted < 0) {
                    long del = matchByte(word, DELETED) & tailMask;
                    if (del != 0L) firstDeleted = (endGroup << 3) | firstLane(del);
                }
                long empty = matchByte(word, EMPTY) & tailMask;
                if (empty != 0L) return insertMutable(firstDeleted >= 0 ? firstDeleted : ((endGroup << 3) | firstLane(empty)), key, value, tag, firstDeleted >= 0);
            }
            if (firstDeleted >= 0) return insertMutable(firstDeleted, key, value, tag, true);
            return false;
        }

        private boolean insertMutable(int index, long key, int value, int tag, boolean reuseDeleted) {
            keys[index] = key;
            values[index] = value;
            int group = index >>> 3;
            int lane = index & 7;
            ctrlWords[group] = setLane(ctrlWords[group], lane, tag);
            size++;
            if (reuseDeleted) deleted--; else used++;
            return true;
        }

        private int findMutable(long key, int tag, int endGroup, int endLane) {
            int group = endGroup;
            long mask = endLane == 0 ? -1L : laneMask(endLane, 8 - endLane);
            while (group < groupCount) {
                long word = ctrlWords[group];
                long candidates = matchByte(word, tag) & mask;
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (group << 3) | lane;
                    if (keys[index] == key) return index;
                    candidates &= candidates - 1L;
                }
                if ((matchByte(word, EMPTY) & mask) != 0L) return -1;
                group++;
                mask = -1L;
            }
            group = 0;
            while (group < endGroup) {
                long word = ctrlWords[group];
                long candidates = matchByte(word, tag);
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (group << 3) | lane;
                    if (keys[index] == key) return index;
                    candidates &= candidates - 1L;
                }
                if (matchByte(word, EMPTY) != 0L) return -1;
                group++;
            }
            if (endLane > 0) {
                long word = ctrlWords[endGroup];
                long candidates = matchByte(word, tag) & laneMask(0, endLane);
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (endGroup << 3) | lane;
                    if (keys[index] == key) return index;
                    candidates &= candidates - 1L;
                }
            }
            return -1;
        }

        @Override void markDeleted(int index) { ctrlWords[index >>> 3] = setLane(ctrlWords[index >>> 3], index & 7, DELETED); }
        @Override long keyAt(int index) { return keys[index]; }
        @Override int valueAt(int index) { return values[index]; }
        @Override void setValue(int index, int value) { values[index] = value; }
        @Override boolean isLiveIndex(int index) { return ((ctrlWords[index >>> 3] >>> ((index & 7) << 3)) & 0x80L) != 0L; }
        @Override long liveMask(int group) { return ctrlWords[group] & HIGH; }

        @Override
        public void forEach(LongIntConsumer consumer) {
            checkOpen();
            for (int group = 0; group < groupCount; group++) {
                long live = ctrlWords[group] & HIGH;
                while (live != 0L) {
                    int lane = firstLane(live);
                    int index = (group << 3) | lane;
                    consumer.accept(keys[index], values[index]);
                    live &= live - 1L;
                }
            }
        }

        @Override public long backendBytes() { return 13L * capacity; }

        @Override public void close() { if (!closed) { closed = true; ctrlWords = null; keys = null; values = null; } }
    }

    static final class SingleThreadDirectLong2IntLookup extends AbstractDirectLookup {
        SingleThreadDirectLong2IntLookup(int capacity, SingleThreadLong2IntBuilder builder) { super(capacity, builder); }
    }

    static final class SingleThreadDirectLong2IntFixedMap extends AbstractDirectFixed {
        SingleThreadDirectLong2IntFixedMap(int capacity, SingleThreadLong2IntBuilder builder) { super(capacity, builder, Long2IntBackend.DIRECT); }
    }

    static final class SingleThreadDirectLong2IntMutableMap extends AbstractDirectMutable {
        SingleThreadDirectLong2IntMutableMap(int capacity, SingleThreadLong2IntBuilder builder) { super(capacity, builder, Long2IntBackend.DIRECT); }
    }

    private abstract static class AbstractDirectLookup extends AbstractLookup {
        DirectMemory ctrlWords;
        DirectMemory entries;

        AbstractDirectLookup(int capacity, SingleThreadLong2IntBuilder builder) {
            super(capacity, builder);
            DirectMemory c = null;
            DirectMemory e = null;
            boolean ok = false;
            try {
                c = new DirectMemory((long) groupCount << 3);
                e = new DirectMemory((long) this.capacity << DIRECT_ENTRY_SHIFT);
                c.clear();
                if (builder.clearEntriesOnAllocate) e.clear();
                if (builder.preTouch) { c.preTouch(); e.preTouch(); }
                ok = true;
            } finally {
                if (!ok) {
                    if (c != null) c.close();
                    if (e != null) e.close();
                }
            }
            ctrlWords = c;
            entries = e;
        }

        @Override int getOrMissing(long key, int missing) { int index = findIndex(key); return index >= 0 ? valueAt(index) : missing; }

        @Override
        int findIndex(long key) {
            long hash = hash(key);
            int home = home(hash);
            return findNoDelete(key, tag(hash), home >>> 3, home & 7);
        }

        @Override
        void putBuild(long key, int value) {
            int existing = findIndex(key);
            if (existing >= 0) {
                setValue(existing, value);
                return;
            }
            long hash = hash(key);
            int home = home(hash);
            if (!insertNoDelete(key, value, tag(hash), home >>> 3, home & 7)) throw new IllegalStateException("lookup table is full");
        }

        int findNoDelete(long key, int tag, int endGroup, int endLane) {
            int group = endGroup;
            long mask = endLane == 0 ? -1L : laneMask(endLane, 8 - endLane);
            while (group < groupCount) {
                long word = ctrlWord(group);
                long candidates = matchByte(word, tag) & mask;
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (group << 3) | lane;
                    if (keyAt(index) == key) return index;
                    candidates &= candidates - 1L;
                }
                if ((matchByte(word, EMPTY) & mask) != 0L) return -1;
                group++;
                mask = -1L;
            }
            group = 0;
            while (group < endGroup) {
                long word = ctrlWord(group);
                long candidates = matchByte(word, tag);
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (group << 3) | lane;
                    if (keyAt(index) == key) return index;
                    candidates &= candidates - 1L;
                }
                if (matchByte(word, EMPTY) != 0L) return -1;
                group++;
            }
            if (endLane > 0) {
                long word = ctrlWord(endGroup);
                long candidates = matchByte(word, tag) & laneMask(0, endLane);
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (endGroup << 3) | lane;
                    if (keyAt(index) == key) return index;
                    candidates &= candidates - 1L;
                }
            }
            return -1;
        }

        boolean insertNoDelete(long key, int value, int tag, int endGroup, int endLane) {
            int group = endGroup;
            long mask = endLane == 0 ? -1L : laneMask(endLane, 8 - endLane);
            while (group < groupCount) {
                long word = ctrlWord(group);
                long empty = matchByte(word, EMPTY) & mask;
                if (empty != 0L) return insertAt(group, firstLane(empty), key, value, tag, word);
                group++;
                mask = -1L;
            }
            group = 0;
            while (group < endGroup) {
                long word = ctrlWord(group);
                long empty = matchByte(word, EMPTY);
                if (empty != 0L) return insertAt(group, firstLane(empty), key, value, tag, word);
                group++;
            }
            if (endLane > 0) {
                long word = ctrlWord(endGroup);
                long empty = matchByte(word, EMPTY) & laneMask(0, endLane);
                if (empty != 0L) return insertAt(endGroup, firstLane(empty), key, value, tag, word);
            }
            return false;
        }

        boolean insertAt(int group, int lane, long key, int value, int tag, long word) {
            int index = (group << 3) | lane;
            setKey(index, key);
            setValue(index, value);
            setCtrlWord(group, setLane(word, lane, tag));
            size++;
            return true;
        }

        long ctrlWord(int group) { return U.getLong(ctrlWords.address + ((long) group << 3)); }
        void setCtrlWord(int group, long word) { U.putLong(ctrlWords.address + ((long) group << 3), word); }
        long entryAddress(int index) { return entries.address + ((long) index << DIRECT_ENTRY_SHIFT); }
        long keyAt(int index) { return U.getLong(entryAddress(index) + DIRECT_KEY_OFFSET); }
        void setKey(int index, long key) { U.putLong(entryAddress(index) + DIRECT_KEY_OFFSET, key); }
        int valueAt(int index) { return U.getInt(entryAddress(index) + DIRECT_VALUE_OFFSET); }
        void setValue(int index, int value) { U.putInt(entryAddress(index) + DIRECT_VALUE_OFFSET, value); }

        @Override public long backendBytes() { return ctrlWords.bytes + entries.bytes; }

        @Override public void close() { if (!closed) { closed = true; ctrlWords.close(); entries.close(); } }

        @Override public Long2IntInspector inspector() { return new SingleThreadInspector(this, false); }
    }

    private abstract static class AbstractDirectFixed extends AbstractFixed {
        DirectMemory ctrlWords;
        DirectMemory entries;

        AbstractDirectFixed(int capacity, SingleThreadLong2IntBuilder builder, Long2IntBackend backend) {
            super(capacity, builder, backend);
            DirectMemory c = null;
            DirectMemory e = null;
            boolean ok = false;
            try {
                c = new DirectMemory((long) groupCount << 3);
                e = new DirectMemory((long) this.capacity << DIRECT_ENTRY_SHIFT);
                c.clear();
                if (builder.clearEntriesOnAllocate) e.clear();
                if (builder.preTouch) { c.preTouch(); e.preTouch(); }
                ok = true;
            } finally {
                if (!ok) { if (c != null) c.close(); if (e != null) e.close(); }
            }
            ctrlWords = c;
            entries = e;
        }

        @Override int getOrMissing(long key, int missing) { int index = findIndex(key); return index >= 0 ? valueAt(index) : missing; }
        @Override int findIndex(long key) { long hash = hash(key); int home = home(hash); return findNoDelete(key, tag(hash), home >>> 3, home & 7); }
        @Override void putBuild(long key, int value) { if (!putFixed(key, value, false)) throw new IllegalStateException("fixed table is full"); }

        int findNoDelete(long key, int tag, int endGroup, int endLane) {
            int group = endGroup; long mask = endLane == 0 ? -1L : laneMask(endLane, 8 - endLane);
            while (group < groupCount) { long word = ctrlWord(group); long candidates = matchByte(word, tag) & mask; while (candidates != 0L) { int lane = firstLane(candidates); int index = (group << 3) | lane; if (keyAt(index) == key) return index; candidates &= candidates - 1L; } if ((matchByte(word, EMPTY) & mask) != 0L) return -1; group++; mask = -1L; }
            group = 0; while (group < endGroup) { long word = ctrlWord(group); long candidates = matchByte(word, tag); while (candidates != 0L) { int lane = firstLane(candidates); int index = (group << 3) | lane; if (keyAt(index) == key) return index; candidates &= candidates - 1L; } if (matchByte(word, EMPTY) != 0L) return -1; group++; }
            if (endLane > 0) { long word = ctrlWord(endGroup); long candidates = matchByte(word, tag) & laneMask(0, endLane); while (candidates != 0L) { int lane = firstLane(candidates); int index = (endGroup << 3) | lane; if (keyAt(index) == key) return index; candidates &= candidates - 1L; } }
            return -1;
        }

        @Override
        boolean putFixed(long key, int value, boolean absentOnly) {
            long hash = hash(key); int home = home(hash); int endGroup = home >>> 3; int endLane = home & 7; int tag = tag(hash); int group = endGroup; long mask = endLane == 0 ? -1L : laneMask(endLane, 8 - endLane);
            while (group < groupCount) { long word = ctrlWord(group); long candidates = matchByte(word, tag) & mask; while (candidates != 0L) { int lane = firstLane(candidates); int index = (group << 3) | lane; if (keyAt(index) == key) { if (!absentOnly) setValue(index, value); return !absentOnly; } candidates &= candidates - 1L; } long empty = matchByte(word, EMPTY) & mask; if (empty != 0L) return insertAt(group, firstLane(empty), key, value, tag, word); group++; mask = -1L; }
            group = 0; while (group < endGroup) { long word = ctrlWord(group); long candidates = matchByte(word, tag); while (candidates != 0L) { int lane = firstLane(candidates); int index = (group << 3) | lane; if (keyAt(index) == key) { if (!absentOnly) setValue(index, value); return !absentOnly; } candidates &= candidates - 1L; } long empty = matchByte(word, EMPTY); if (empty != 0L) return insertAt(group, firstLane(empty), key, value, tag, word); group++; }
            if (endLane > 0) { long word = ctrlWord(endGroup); long tailMask = laneMask(0, endLane); long candidates = matchByte(word, tag) & tailMask; while (candidates != 0L) { int lane = firstLane(candidates); int index = (endGroup << 3) | lane; if (keyAt(index) == key) { if (!absentOnly) setValue(index, value); return !absentOnly; } candidates &= candidates - 1L; } long empty = matchByte(word, EMPTY) & tailMask; if (empty != 0L) return insertAt(endGroup, firstLane(empty), key, value, tag, word); }
            return false;
        }

        boolean insertAt(int group, int lane, long key, int value, int tag, long word) { int index = (group << 3) | lane; setKey(index, key); setValue(index, value); setCtrlWord(group, setLane(word, lane, tag)); size++; return true; }
        long ctrlWord(int group) { return U.getLong(ctrlWords.address + ((long) group << 3)); }
        void setCtrlWord(int group, long word) { U.putLong(ctrlWords.address + ((long) group << 3), word); }
        long entryAddress(int index) { return entries.address + ((long) index << DIRECT_ENTRY_SHIFT); }
        @Override long keyAt(int index) { return U.getLong(entryAddress(index) + DIRECT_KEY_OFFSET); }
        void setKey(int index, long key) { U.putLong(entryAddress(index) + DIRECT_KEY_OFFSET, key); }
        @Override int valueAt(int index) { return U.getInt(entryAddress(index) + DIRECT_VALUE_OFFSET); }
        @Override void setValue(int index, int value) { U.putInt(entryAddress(index) + DIRECT_VALUE_OFFSET, value); }
        @Override boolean isLiveIndex(int index) { return ((ctrlWord(index >>> 3) >>> ((index & 7) << 3)) & 0x80L) != 0L; }
        @Override long liveMask(int group) { return ctrlWord(group) & HIGH; }
        @Override public void forEach(LongIntConsumer consumer) { checkOpen(); for (int group = 0; group < groupCount; group++) { long live = ctrlWord(group) & HIGH; while (live != 0L) { int lane = firstLane(live); int index = (group << 3) | lane; consumer.accept(keyAt(index), valueAt(index)); live &= live - 1L; } } }
        @Override public long backendBytes() { return ctrlWords.bytes + entries.bytes; }
        @Override public void close() { if (!closed) { closed = true; ctrlWords.close(); entries.close(); } }
    }

    private abstract static class AbstractDirectMutable extends AbstractMutable {
        DirectMemory ctrlWords;
        DirectMemory entries;

        AbstractDirectMutable(int capacity, SingleThreadLong2IntBuilder builder, Long2IntBackend backend) {
            super(capacity, builder, backend);
            DirectMemory c = null;
            DirectMemory e = null;
            boolean ok = false;
            try {
                c = new DirectMemory((long) groupCount << 3);
                e = new DirectMemory((long) this.capacity << DIRECT_ENTRY_SHIFT);
                c.clear();
                if (builder.clearEntriesOnAllocate) e.clear();
                if (builder.preTouch) { c.preTouch(); e.preTouch(); }
                ok = true;
            } finally {
                if (!ok) { if (c != null) c.close(); if (e != null) e.close(); }
            }
            ctrlWords = c;
            entries = e;
        }

        @Override int getOrMissing(long key, int missing) { int index = findIndex(key); return index >= 0 ? valueAt(index) : missing; }

        @Override
        int findIndex(long key) {
            long hash = hash(key);
            int home = home(hash);
            return findMutable(key, tag(hash), home >>> 3, home & 7);
        }

        @Override void putBuild(long key, int value) { if (!putFixed(key, value, false)) throw new IllegalStateException("mutable table is full"); }

        @Override
        boolean putFixed(long key, int value, boolean absentOnly) {
            long hash = hash(key);
            int home = home(hash);
            int endGroup = home >>> 3;
            int endLane = home & 7;
            int tag = tag(hash);
            int firstDeleted = -1;
            int group = endGroup;
            long mask = endLane == 0 ? -1L : laneMask(endLane, 8 - endLane);
            while (group < groupCount) {
                long word = ctrlWord(group);
                long candidates = matchByte(word, tag) & mask;
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (group << 3) | lane;
                    if (keyAt(index) == key) {
                        if (!absentOnly) setValue(index, value);
                        return !absentOnly;
                    }
                    candidates &= candidates - 1L;
                }
                if (firstDeleted < 0) {
                    long del = matchByte(word, DELETED) & mask;
                    if (del != 0L) firstDeleted = (group << 3) | firstLane(del);
                }
                long empty = matchByte(word, EMPTY) & mask;
                if (empty != 0L) return insertMutable(firstDeleted >= 0 ? firstDeleted : ((group << 3) | firstLane(empty)), key, value, tag, firstDeleted >= 0);
                group++;
                mask = -1L;
            }
            group = 0;
            while (group < endGroup) {
                long word = ctrlWord(group);
                long candidates = matchByte(word, tag);
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (group << 3) | lane;
                    if (keyAt(index) == key) {
                        if (!absentOnly) setValue(index, value);
                        return !absentOnly;
                    }
                    candidates &= candidates - 1L;
                }
                if (firstDeleted < 0) {
                    long del = matchByte(word, DELETED);
                    if (del != 0L) firstDeleted = (group << 3) | firstLane(del);
                }
                long empty = matchByte(word, EMPTY);
                if (empty != 0L) return insertMutable(firstDeleted >= 0 ? firstDeleted : ((group << 3) | firstLane(empty)), key, value, tag, firstDeleted >= 0);
                group++;
            }
            if (endLane > 0) {
                long word = ctrlWord(endGroup);
                long tailMask = laneMask(0, endLane);
                long candidates = matchByte(word, tag) & tailMask;
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (endGroup << 3) | lane;
                    if (keyAt(index) == key) {
                        if (!absentOnly) setValue(index, value);
                        return !absentOnly;
                    }
                    candidates &= candidates - 1L;
                }
                if (firstDeleted < 0) {
                    long del = matchByte(word, DELETED) & tailMask;
                    if (del != 0L) firstDeleted = (endGroup << 3) | firstLane(del);
                }
                long empty = matchByte(word, EMPTY) & tailMask;
                if (empty != 0L) return insertMutable(firstDeleted >= 0 ? firstDeleted : ((endGroup << 3) | firstLane(empty)), key, value, tag, firstDeleted >= 0);
            }
            if (firstDeleted >= 0) return insertMutable(firstDeleted, key, value, tag, true);
            return false;
        }

        private boolean insertMutable(int index, long key, int value, int tag, boolean reuseDeleted) {
            setKey(index, key);
            setValue(index, value);
            int group = index >>> 3;
            int lane = index & 7;
            setCtrlWord(group, setLane(ctrlWord(group), lane, tag));
            size++;
            if (reuseDeleted) deleted--; else used++;
            return true;
        }

        private int findMutable(long key, int tag, int endGroup, int endLane) {
            int group = endGroup;
            long mask = endLane == 0 ? -1L : laneMask(endLane, 8 - endLane);
            while (group < groupCount) {
                long word = ctrlWord(group);
                long candidates = matchByte(word, tag) & mask;
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (group << 3) | lane;
                    if (keyAt(index) == key) return index;
                    candidates &= candidates - 1L;
                }
                if ((matchByte(word, EMPTY) & mask) != 0L) return -1;
                group++;
                mask = -1L;
            }
            group = 0;
            while (group < endGroup) {
                long word = ctrlWord(group);
                long candidates = matchByte(word, tag);
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (group << 3) | lane;
                    if (keyAt(index) == key) return index;
                    candidates &= candidates - 1L;
                }
                if (matchByte(word, EMPTY) != 0L) return -1;
                group++;
            }
            if (endLane > 0) {
                long word = ctrlWord(endGroup);
                long candidates = matchByte(word, tag) & laneMask(0, endLane);
                while (candidates != 0L) {
                    int lane = firstLane(candidates);
                    int index = (endGroup << 3) | lane;
                    if (keyAt(index) == key) return index;
                    candidates &= candidates - 1L;
                }
            }
            return -1;
        }

        @Override void markDeleted(int index) { int group = index >>> 3; setCtrlWord(group, setLane(ctrlWord(group), index & 7, DELETED)); }
        @Override long keyAt(int index) { return U.getLong(entryAddress(index) + DIRECT_KEY_OFFSET); }
        void setKey(int index, long key) { U.putLong(entryAddress(index) + DIRECT_KEY_OFFSET, key); }
        @Override int valueAt(int index) { return U.getInt(entryAddress(index) + DIRECT_VALUE_OFFSET); }
        @Override void setValue(int index, int value) { U.putInt(entryAddress(index) + DIRECT_VALUE_OFFSET, value); }
        @Override boolean isLiveIndex(int index) { return ((ctrlWord(index >>> 3) >>> ((index & 7) << 3)) & 0x80L) != 0L; }
        @Override long liveMask(int group) { return ctrlWord(group) & HIGH; }
        @Override public void forEach(LongIntConsumer consumer) { checkOpen(); for (int group = 0; group < groupCount; group++) { long live = ctrlWord(group) & HIGH; while (live != 0L) { int lane = firstLane(live); int index = (group << 3) | lane; consumer.accept(keyAt(index), valueAt(index)); live &= live - 1L; } } }
        @Override public long backendBytes() { return ctrlWords.bytes + entries.bytes; }
        @Override public void close() { if (!closed) { closed = true; ctrlWords.close(); entries.close(); } }
        long ctrlWord(int group) { return U.getLong(ctrlWords.address + ((long) group << 3)); }
        void setCtrlWord(int group, long word) { U.putLong(ctrlWords.address + ((long) group << 3), word); }
        long entryAddress(int index) { return entries.address + ((long) index << DIRECT_ENTRY_SHIFT); }
    }

    private static final class SingleThreadInspector implements Long2IntInspector {
        private final AbstractLookup lookup;
        private final boolean mutable;

        SingleThreadInspector(AbstractLookup lookup, boolean mutable) {
            this.lookup = lookup;
            this.mutable = mutable;
        }

        @Override
        public Long2IntStats stats() {
            long used = exactSize();
            long deleted = lookup instanceof AbstractMutable m ? m.deleted : 0L;
            long bytes = lookup.backendBytes();
            long offHeap = lookup instanceof AbstractDirectLookup || lookup instanceof AbstractDirectFixed || lookup instanceof AbstractDirectMutable ? bytes : 0L;
            return new Long2IntStats(lookup.size, used, lookup.capacity, 1, offHeap, 0L, deleted, used + deleted,
                    (double) (used + deleted) / (double) lookup.capacity, false, 0L, 0L, 0L, 0L, 0L);
        }

        @Override
        public Long2IntHealth healthCheck() {
            List<String> problems = new ArrayList<>();
            if ((lookup.capacity & (lookup.capacity - 1)) != 0) problems.add("capacity is not power of two");
            if ((lookup.capacity & 7) != 0) problems.add("capacity is not multiple of 8");
            long exact = exactSize();
            if (exact != lookup.size) problems.add("size mismatch: field=" + lookup.size + ", exact=" + exact);
            if (lookup instanceof AbstractFixed fixed) {
                for (int i = 0; i < lookup.capacity; i++) {
                    if (fixed.isLiveIndex(i) && fixed.findIndex(fixed.keyAt(i)) != i) {
                        problems.add("unreachable key at " + i);
                        break;
                    }
                }
            }
            return new Long2IntHealth(problems.isEmpty(), Collections.unmodifiableList(problems));
        }

        @Override
        public long exactSize() {
            if (lookup instanceof SingleThreadHeapLong2IntLookup heap) {
                long count = 0L;
                for (int group = 0; group < heap.groupCount; group++) count += Long.bitCount(heap.ctrlWords[group] & HIGH);
                return count;
            }
            if (lookup instanceof AbstractFixed fixed) {
                long count = 0L;
                for (int group = 0; group < fixed.groupCount; group++) count += Long.bitCount(fixed.liveMask(group));
                return count;
            }
            if (lookup instanceof AbstractDirectLookup direct) {
                long count = 0L;
                for (int group = 0; group < direct.groupCount; group++) count += Long.bitCount(direct.ctrlWord(group) & HIGH);
                return count;
            }
            return lookup.size;
        }
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
            for (long p = 0L; p < bytes; p += 4096L) U.putByte(address + p, U.getByte(address + p));
            if (bytes > 0L) U.putByte(address + bytes - 1L, U.getByte(address + bytes - 1L));
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                U.freeMemory(raw);
            }
        }
    }

    private static int capacity(int expectedSize, double loadFactor) {
        if (expectedSize < 0) throw new IllegalArgumentException("expectedSize < 0");
        long needed = Math.max(MIN_CAPACITY, (long) Math.ceil(Math.max(1, expectedSize) / loadFactor));
        return normalizeCapacity(needed);
    }

    private static int normalizeCapacity(long requested) {
        long capacity = MIN_CAPACITY;
        while (capacity < requested) capacity <<= 1;
        if (capacity > (1L << 30)) throw new IllegalArgumentException("capacity too large: " + requested);
        return (int) capacity;
    }

    private static long zeroByteMask(long x) { return (x - ONES) & ~x & HIGH; }
    private static long matchByte(long word, int b) { return zeroByteMask(word ^ ((b & 0xffL) * ONES)); }
    private static long laneMask(int startLane, int lanes) { if (lanes <= 0) return 0L; long mask = lanes == 8 ? -1L : ((1L << (lanes << 3)) - 1L); return mask << (startLane << 3); }
    private static long setLane(long word, int lane, int value) { int shift = lane << 3; return (word & ~(0xffL << shift)) | ((value & 0xffL) << shift); }
    private static int firstLane(long mask) { return Long.numberOfTrailingZeros(mask) >>> 3; }
    private static long mix64(long z) { z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L; z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL; return z ^ (z >>> 31); }

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

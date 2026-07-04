package net.sixik.concurrent_library.long2reference;

import com.trivago.fastutilconcurrentwrapper.ConcurrentLongLongMapBuilder;
import com.trivago.fastutilconcurrentwrapper.LongLongMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import org.jctools.maps.NonBlockingHashMapLong;
import org.jctools.util.UnsafeRefArrayAccess;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.HashMap;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
public class Long2ReferenceRealLoadBenchmark {
    private static final long BASE_KEY = 10_000_000L;
    private static final int KEY_SAMPLE_SIZE = 1 << 16;

    @State(Scope.Benchmark)
    public static class Long2ReferenceStateData {
        @Param({
                "denseChecked",
                "denseUnchecked",
                "pagedChecked",
                "pagedUnchecked",
                "paddedDenseChecked",
                "strictDenseChecked",
                "strictPagedChecked",
                "plainDenseChecked",
                "plainPagedChecked",
                "nullable",
                "counting",
                "stateful",
                "autoConcurrent"
        })
        public String implementation;

        @Param({"65536", "1048576", "4194304"})
        public int capacity;

        @Param({"0.25", "0.90"})
        public double fillRatio;

        Long2Reference<Payload> table;
        Payload[] values;
        Payload[] replacements;
        long[] existingKeys;
        long[] mixedKeys;
        long[] writeKeys;
        long[] insertKeys;
        long[] removeKeys;
        long[] absentKeys;

        @Setup(Level.Trial)
        public void setup() {
            int filled = filled();
            values = new Payload[capacity];
            replacements = new Payload[capacity];
            for (int slot = 0; slot < capacity; slot++) {
                long key = BASE_KEY + slot;
                values[slot] = new Payload(key, slot, slot * 31L, "value-" + slot);
                replacements[slot] = new Payload(key, slot, slot * 17L, "replacement-" + slot);
            }

            table = newTable(implementation, capacity);
            for (int slot = 0; slot < filled; slot++) {
                if (implementation.equals("nullable") && (slot & 31) == 0) {
                    table.put(BASE_KEY + slot, null);
                } else {
                    table.put(BASE_KEY + slot, values[slot]);
                }
            }

            existingKeys = sampleKeys(1L, filled, false);
            mixedKeys = sampleKeys(2L, capacity, true);
            writeKeys = sampleKeys(3L, capacity, false);
            insertKeys = sampleKeys(4L, capacity, false);
            removeKeys = sampleKeys(5L, filled, false);
            absentKeys = sampleAbsentKeys(6L);
        }

        int filled() {
            return Math.max(1, Math.min(capacity, (int) (capacity * fillRatio)));
        }

        Payload replacement(long key) {
            return replacements[(int) (key - BASE_KEY)];
        }

        Payload value(long key) {
            return values[(int) (key - BASE_KEY)];
        }

        private long[] sampleKeys(long seed, int bound, boolean includeMisses) {
            long[] keys = new long[KEY_SAMPLE_SIZE];
            SplittableRandom random = new SplittableRandom(seed + capacity * 31L);
            int safeBound = Math.max(1, bound);
            for (int i = 0; i < keys.length; i++) {
                if (includeMisses && (i & 3) == 0) {
                    keys[i] = BASE_KEY + filled() + random.nextInt(Math.max(1, capacity - filled()));
                } else {
                    keys[i] = BASE_KEY + random.nextInt(safeBound);
                }
            }
            return keys;
        }

        private long[] sampleAbsentKeys(long seed) {
            long[] keys = new long[KEY_SAMPLE_SIZE];
            SplittableRandom random = new SplittableRandom(seed + capacity * 17L);
            for (int i = 0; i < keys.length; i++) {
                int span = Math.max(1, capacity - filled());
                keys[i] = BASE_KEY + filled() + random.nextInt(span);
            }
            return keys;
        }
    }

    @State(Scope.Benchmark)
    public static class BaselineStateData {
        @Param({
                "long2ReferenceAuto",
                "atomicReferenceArray",
                "concurrentHashMap",
                "hashMap",
                "fastUtilOpenHashMap",
                "jctoolsNonBlockingHashMapLong",
                "jctoolsUnsafeRefArray",
                "trivagoLongLongBlocking",
                "trivagoLongLongBusyWaiting"
        })
        public String baseline;

        @Param({"65536", "1048576", "4194304"})
        public int capacity;

        @Param({"0.90"})
        public double fillRatio;

        Long2Reference<Payload> long2Reference;
        AtomicReferenceArray<Payload> atomicReferenceArray;
        Object[] unsafeRefArray;
        ConcurrentHashMap<Long, Payload> concurrentHashMap;
        HashMap<Long, Payload> hashMap;
        Long2ReferenceOpenHashMap<Payload> fastUtilOpenHashMap;
        NonBlockingHashMapLong<Payload> jctoolsNonBlockingHashMapLong;
        LongLongMap trivagoLongLongBlocking;
        LongLongMap trivagoLongLongBusyWaiting;
        Payload[] values;
        long[] primitiveValues;
        long[] existingKeys;
        long[] writeKeys;

        @Setup(Level.Trial)
        public void setup() {
            int filled = Math.max(1, Math.min(capacity, (int) (capacity * fillRatio)));
            values = new Payload[capacity];
            primitiveValues = new long[capacity];
            for (int slot = 0; slot < capacity; slot++) {
                long key = BASE_KEY + slot;
                values[slot] = new Payload(key, slot, slot * 31L, "value-" + slot);
                primitiveValues[slot] = slot * 31L;
            }

            long2Reference = Long2Reference.concurrent(BASE_KEY, capacity);
            atomicReferenceArray = new AtomicReferenceArray<>(capacity);
            unsafeRefArray = UnsafeRefArrayAccess.allocateRefArray(capacity);
            concurrentHashMap = new ConcurrentHashMap<>(capacity);
            hashMap = new HashMap<>(capacity);
            fastUtilOpenHashMap = new Long2ReferenceOpenHashMap<>(capacity);
            jctoolsNonBlockingHashMapLong = new NonBlockingHashMapLong<>(capacity);
            trivagoLongLongBlocking = trivago(ConcurrentLongLongMapBuilder.MapMode.BLOCKING, capacity);
            trivagoLongLongBusyWaiting = trivago(ConcurrentLongLongMapBuilder.MapMode.BUSY_WAITING, capacity);

            for (int slot = 0; slot < filled; slot++) {
                long key = BASE_KEY + slot;
                Payload value = values[slot];
                long2Reference.put(key, value);
                atomicReferenceArray.set(slot, value);
                UnsafeRefArrayAccess.soRefElement(unsafeRefArray, UnsafeRefArrayAccess.calcRefElementOffset(slot), value);
                concurrentHashMap.put(key, value);
                hashMap.put(key, value);
                fastUtilOpenHashMap.put(key, value);
                jctoolsNonBlockingHashMapLong.put(key, value);
                trivagoLongLongBlocking.put(key, primitiveValues[slot]);
                trivagoLongLongBusyWaiting.put(key, primitiveValues[slot]);
            }

            existingKeys = sampleKeys(11L, filled);
            writeKeys = sampleKeys(12L, capacity);
        }

        Payload replacement(long key) {
            return values[(int) (key - BASE_KEY)];
        }

        long primitiveReplacement(long key) {
            return primitiveValues[(int) (key - BASE_KEY)];
        }

        private long[] sampleKeys(long seed, int bound) {
            long[] keys = new long[KEY_SAMPLE_SIZE];
            SplittableRandom random = new SplittableRandom(seed + capacity * 13L);
            int safeBound = Math.max(1, bound);
            for (int i = 0; i < keys.length; i++) {
                keys[i] = BASE_KEY + random.nextInt(safeBound);
            }
            return keys;
        }

        private static LongLongMap trivago(ConcurrentLongLongMapBuilder.MapMode mode, int capacity) {
            return ConcurrentLongLongMapBuilder.newBuilder()
                    .withInitialCapacity(capacity)
                    .withBuckets(64)
                    .withMode(mode)
                    .withDefaultValue(Long.MIN_VALUE)
                    .build();
        }
    }

    @State(Scope.Benchmark)
    public static class ConcurrentBaselineStateData {
        @Param({
                "long2ReferenceAuto",
                "atomicReferenceArray",
                "concurrentHashMap",
                "jctoolsNonBlockingHashMapLong",
                "jctoolsUnsafeRefArray",
                "trivagoLongLongBlocking",
                "trivagoLongLongBusyWaiting"
        })
        public String baseline;

        @Param({"65536", "1048576", "4194304"})
        public int capacity;

        @Param({"0.90"})
        public double fillRatio;

        Long2Reference<Payload> long2Reference;
        AtomicReferenceArray<Payload> atomicReferenceArray;
        Object[] unsafeRefArray;
        ConcurrentHashMap<Long, Payload> concurrentHashMap;
        NonBlockingHashMapLong<Payload> jctoolsNonBlockingHashMapLong;
        LongLongMap trivagoLongLongBlocking;
        LongLongMap trivagoLongLongBusyWaiting;
        Payload[] values;
        long[] primitiveValues;
        long[] existingKeys;
        long[] writeKeys;

        @Setup(Level.Trial)
        public void setup() {
            int filled = Math.max(1, Math.min(capacity, (int) (capacity * fillRatio)));
            values = new Payload[capacity];
            primitiveValues = new long[capacity];
            for (int slot = 0; slot < capacity; slot++) {
                long key = BASE_KEY + slot;
                values[slot] = new Payload(key, slot, slot * 31L, "value-" + slot);
                primitiveValues[slot] = slot * 31L;
            }

            long2Reference = Long2Reference.concurrent(BASE_KEY, capacity);
            atomicReferenceArray = new AtomicReferenceArray<>(capacity);
            unsafeRefArray = UnsafeRefArrayAccess.allocateRefArray(capacity);
            concurrentHashMap = new ConcurrentHashMap<>(capacity);
            jctoolsNonBlockingHashMapLong = new NonBlockingHashMapLong<>(capacity);
            trivagoLongLongBlocking = BaselineStateData.trivago(ConcurrentLongLongMapBuilder.MapMode.BLOCKING, capacity);
            trivagoLongLongBusyWaiting = BaselineStateData.trivago(ConcurrentLongLongMapBuilder.MapMode.BUSY_WAITING, capacity);

            for (int slot = 0; slot < filled; slot++) {
                long key = BASE_KEY + slot;
                Payload value = values[slot];
                long2Reference.put(key, value);
                atomicReferenceArray.set(slot, value);
                UnsafeRefArrayAccess.soRefElement(unsafeRefArray, UnsafeRefArrayAccess.calcRefElementOffset(slot), value);
                concurrentHashMap.put(key, value);
                jctoolsNonBlockingHashMapLong.put(key, value);
                trivagoLongLongBlocking.put(key, primitiveValues[slot]);
                trivagoLongLongBusyWaiting.put(key, primitiveValues[slot]);
            }

            existingKeys = sampleKeys(21L, filled);
            writeKeys = sampleKeys(22L, capacity);
        }

        Payload replacement(long key) {
            return values[(int) (key - BASE_KEY)];
        }

        long primitiveReplacement(long key) {
            return primitiveValues[(int) (key - BASE_KEY)];
        }

        private long[] sampleKeys(long seed, int bound) {
            long[] keys = new long[KEY_SAMPLE_SIZE];
            SplittableRandom random = new SplittableRandom(seed + capacity * 19L);
            int safeBound = Math.max(1, bound);
            for (int i = 0; i < keys.length; i++) {
                keys[i] = BASE_KEY + random.nextInt(safeBound);
            }
            return keys;
        }
    }

    @State(Scope.Thread)
    public static class Cursor {
        int index;

        long next(long[] keys) {
            return keys[index++ & (keys.length - 1)];
        }
    }

    @State(Scope.Benchmark)
    public static class SharedCounter {
        private final AtomicInteger cursor = new AtomicInteger();

        long nextKey(int capacity) {
            return BASE_KEY + Math.floorMod(cursor.getAndIncrement(), capacity);
        }
    }

    @Benchmark
    public Object reference_get_existing(Long2ReferenceStateData data, Cursor cursor) {
        return data.table.get(cursor.next(data.existingKeys));
    }

    @Benchmark
    public Object reference_get_mixed_hit_miss(Long2ReferenceStateData data, Cursor cursor) {
        return data.table.get(cursor.next(data.mixedKeys));
    }

    @Benchmark
    public boolean reference_contains_existing(Long2ReferenceStateData data, Cursor cursor) {
        return data.table.containsKey(cursor.next(data.existingKeys));
    }

    @Benchmark
    public void reference_put_overwrite(Long2ReferenceStateData data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.writeKeys);
        data.table.put(key, data.replacement(key));
        blackhole.consume(key);
    }

    @Benchmark
    public boolean reference_put_if_absent_mixed(Long2ReferenceStateData data, Cursor cursor) {
        long key = cursor.next(data.insertKeys);
        return data.table.putIfAbsent(key, data.replacement(key));
    }

    @Benchmark
    public boolean reference_compare_and_set_existing(Long2ReferenceStateData data, Cursor cursor) {
        long key = cursor.next(data.existingKeys);
        Payload value = data.table.get(key);
        return data.table.compareAndSet(key, value, data.replacement(key));
    }

    @Benchmark
    public Object reference_remove_then_restore(Long2ReferenceStateData data, Cursor cursor) {
        long key = cursor.next(data.removeKeys);
        Payload removed = data.table.remove(key);
        data.table.put(key, data.value(key));
        return removed;
    }

    @Benchmark
    public void reference_delete_then_restore(Long2ReferenceStateData data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.removeKeys);
        data.table.delete(key);
        data.table.put(key, data.value(key));
        blackhole.consume(key);
    }

    @Benchmark
    public long reference_count_by_scan(Long2ReferenceStateData data) {
        return data.table.countByScan();
    }

    @Benchmark
    public boolean reference_is_empty_by_scan(Long2ReferenceStateData data) {
        return data.table.isEmptyByScan();
    }

    @Benchmark
    public Object reference_wrapper_specific_read(Long2ReferenceStateData data, Cursor cursor) {
        long key = cursor.next(data.existingKeys);
        if (data.table instanceof CountingLong2Reference<Payload> counting) {
            return counting.size();
        }
        if (data.table instanceof StatefulLong2Reference<Payload> stateful) {
            return stateful.state(key);
        }
        return data.table.get(key);
    }

    @Benchmark
    @Threads(4)
    public void reference_multithread_put(Long2ReferenceStateData data, SharedCounter counter, Blackhole blackhole) {
        long key = counter.nextKey(data.capacity);
        data.table.put(key, data.replacement(key));
        blackhole.consume(key);
    }

    @Benchmark
    @Threads(4)
    public Object reference_multithread_get(Long2ReferenceStateData data, Cursor cursor) {
        return data.table.get(cursor.next(data.existingKeys));
    }

    @Benchmark
    @Threads(4)
    public boolean reference_multithread_compare_and_set(Long2ReferenceStateData data, SharedCounter counter) {
        long key = counter.nextKey(data.capacity);
        Payload old = data.table.get(key);
        return data.table.compareAndSet(key, old, data.replacement(key));
    }

    @Benchmark
    public Object baseline_get_existing(BaselineStateData data, Cursor cursor) {
        long key = cursor.next(data.existingKeys);
        return switch (data.baseline) {
            case "long2ReferenceAuto" -> data.long2Reference.get(key);
            case "atomicReferenceArray" -> data.atomicReferenceArray.get((int) (key - BASE_KEY));
            case "concurrentHashMap" -> data.concurrentHashMap.get(key);
            case "hashMap" -> data.hashMap.get(key);
            case "fastUtilOpenHashMap" -> data.fastUtilOpenHashMap.get(key);
            case "jctoolsNonBlockingHashMapLong" -> data.jctoolsNonBlockingHashMapLong.get(key);
            case "jctoolsUnsafeRefArray" -> UnsafeRefArrayAccess.lvRefElement(
                    data.unsafeRefArray,
                    UnsafeRefArrayAccess.calcRefElementOffset(key - BASE_KEY)
            );
            case "trivagoLongLongBlocking" -> data.trivagoLongLongBlocking.get(key);
            case "trivagoLongLongBusyWaiting" -> data.trivagoLongLongBusyWaiting.get(key);
            default -> throw new IllegalStateException(data.baseline);
        };
    }

    @Benchmark
    public void baseline_put_overwrite(BaselineStateData data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.writeKeys);
        switch (data.baseline) {
            case "long2ReferenceAuto" -> data.long2Reference.put(key, data.replacement(key));
            case "atomicReferenceArray" -> data.atomicReferenceArray.set((int) (key - BASE_KEY), data.replacement(key));
            case "concurrentHashMap" -> data.concurrentHashMap.put(key, data.replacement(key));
            case "hashMap" -> data.hashMap.put(key, data.replacement(key));
            case "fastUtilOpenHashMap" -> data.fastUtilOpenHashMap.put(key, data.replacement(key));
            case "jctoolsNonBlockingHashMapLong" -> data.jctoolsNonBlockingHashMapLong.put(key, data.replacement(key));
            case "jctoolsUnsafeRefArray" -> UnsafeRefArrayAccess.soRefElement(
                    data.unsafeRefArray,
                    UnsafeRefArrayAccess.calcRefElementOffset(key - BASE_KEY),
                    data.replacement(key)
            );
            case "trivagoLongLongBlocking" -> data.trivagoLongLongBlocking.put(key, data.primitiveReplacement(key));
            case "trivagoLongLongBusyWaiting" -> data.trivagoLongLongBusyWaiting.put(key, data.primitiveReplacement(key));
            default -> throw new IllegalStateException(data.baseline);
        }
        blackhole.consume(key);
    }

    @Benchmark
    @Threads(4)
    public void baseline_multithread_put(ConcurrentBaselineStateData data, SharedCounter counter, Blackhole blackhole) {
        long key = counter.nextKey(data.capacity);
        switch (data.baseline) {
            case "long2ReferenceAuto" -> data.long2Reference.put(key, data.replacement(key));
            case "atomicReferenceArray" -> data.atomicReferenceArray.set((int) (key - BASE_KEY), data.replacement(key));
            case "concurrentHashMap" -> data.concurrentHashMap.put(key, data.replacement(key));
            case "jctoolsNonBlockingHashMapLong" -> data.jctoolsNonBlockingHashMapLong.put(key, data.replacement(key));
            case "jctoolsUnsafeRefArray" -> UnsafeRefArrayAccess.soRefElement(
                    data.unsafeRefArray,
                    UnsafeRefArrayAccess.calcRefElementOffset(key - BASE_KEY),
                    data.replacement(key)
            );
            case "trivagoLongLongBlocking" -> data.trivagoLongLongBlocking.put(key, data.primitiveReplacement(key));
            case "trivagoLongLongBusyWaiting" -> data.trivagoLongLongBusyWaiting.put(key, data.primitiveReplacement(key));
            default -> throw new IllegalStateException(data.baseline);
        }
        blackhole.consume(key);
    }

    @Benchmark
    @Threads(4)
    public Object baseline_multithread_get(ConcurrentBaselineStateData data, Cursor cursor) {
        long key = cursor.next(data.existingKeys);
        return switch (data.baseline) {
            case "long2ReferenceAuto" -> data.long2Reference.get(key);
            case "atomicReferenceArray" -> data.atomicReferenceArray.get((int) (key - BASE_KEY));
            case "concurrentHashMap" -> data.concurrentHashMap.get(key);
            case "jctoolsNonBlockingHashMapLong" -> data.jctoolsNonBlockingHashMapLong.get(key);
            case "jctoolsUnsafeRefArray" -> UnsafeRefArrayAccess.lvRefElement(
                    data.unsafeRefArray,
                    UnsafeRefArrayAccess.calcRefElementOffset(key - BASE_KEY)
            );
            case "trivagoLongLongBlocking" -> data.trivagoLongLongBlocking.get(key);
            case "trivagoLongLongBusyWaiting" -> data.trivagoLongLongBusyWaiting.get(key);
            default -> throw new IllegalStateException(data.baseline);
        };
    }

    private static Long2Reference<Payload> newTable(String implementation, int capacity) {
        return switch (implementation) {
            case "denseChecked" -> new DenseChecked<>(BASE_KEY, capacity);
            case "denseUnchecked" -> new DenseUnchecked<>(BASE_KEY, capacity);
            case "pagedChecked" -> new PagedChecked<>(BASE_KEY, capacity);
            case "pagedUnchecked" -> new PagedUnchecked<>(BASE_KEY, capacity);
            case "paddedDenseChecked" -> new PaddedDenseChecked<>(BASE_KEY, capacity);
            case "strictDenseChecked" -> new StrictDenseChecked<>(BASE_KEY, capacity);
            case "strictPagedChecked" -> new StrictPagedChecked<>(BASE_KEY, capacity);
            case "plainDenseChecked" -> new PlainDenseChecked<>(BASE_KEY, capacity);
            case "plainPagedChecked" -> new PlainPagedChecked<>(BASE_KEY, capacity);
            case "nullable" -> Long2Reference.nullable(BASE_KEY, capacity);
            case "counting" -> Long2Reference.counting(BASE_KEY, capacity);
            case "stateful" -> Long2Reference.stateful(BASE_KEY, capacity);
            case "autoConcurrent" -> Long2Reference.concurrent(BASE_KEY, capacity);
            default -> throw new IllegalStateException(implementation);
        };
    }

    private record Payload(long key, int slot, long stamp, String label) {
    }
}

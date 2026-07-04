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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
public class Long2ReferenceBenchmark {
    @State(Scope.Benchmark)
    public static class DataSet {
        @Param({"65536", "1048576", "4194304"})
        public int capacity;

        @Param({"0.90"})
        public double fillRatio;

        Object[] values;
long[] primitiveValues;
        long[] readKeys;
        long[] writeKeys;
        DenseChecked<Object> dense;
        Long2Reference<Object> concurrent;
        AtomicReferenceArray<Object> atomicArray;
Object[] jctoolsUnsafeArray;
        ConcurrentHashMap<Long, Object> concurrentHashMap;
NonBlockingHashMapLong<Object> jctoolsNonBlockingHashMapLong;
        HashMap<Long, Object> hashMap;
        Long2ReferenceOpenHashMap<Object> fastUtil;
LongLongMap trivagoLongLongBlocking;
LongLongMap trivagoLongLongBusyWaiting;

        @Setup(Level.Trial)
        public void setup() {
            int filled = Math.max(1, (int) (capacity * fillRatio));
            int keyCount = 1 << 16;
            values = new Object[capacity];
primitiveValues = new long[capacity];
            readKeys = new long[keyCount];
            writeKeys = new long[keyCount];

            dense = Long2Reference.dense(0L, capacity);
            concurrent = Long2Reference.concurrent(0L, capacity);
            atomicArray = new AtomicReferenceArray<>(capacity);
jctoolsUnsafeArray = UnsafeRefArrayAccess.allocateRefArray(capacity);
            concurrentHashMap = new ConcurrentHashMap<>(capacity);
jctoolsNonBlockingHashMapLong = new NonBlockingHashMapLong<>(capacity);
            hashMap = new HashMap<>(capacity);
            fastUtil = new Long2ReferenceOpenHashMap<>(capacity);
trivagoLongLongBlocking = ConcurrentLongLongMapBuilder.newBuilder()
                    .withInitialCapacity(capacity)
                    .withBuckets(16)
                    .withMode(ConcurrentLongLongMapBuilder.MapMode.BLOCKING)
                    .withDefaultValue(Long.MIN_VALUE)
                    .build();
trivagoLongLongBusyWaiting = ConcurrentLongLongMapBuilder.newBuilder()
                    .withInitialCapacity(capacity)
                    .withBuckets(16)
                    .withMode(ConcurrentLongLongMapBuilder.MapMode.BUSY_WAITING)
                    .withDefaultValue(Long.MIN_VALUE)
                    .build();

            for (int i = 0; i < capacity; i++) {
                values[i] = new Payload(i, i * 31L);
primitiveValues[i] = i * 31L;
            }
            for (int i = 0; i < filled; i++) {
                Object value = values[i];
                dense.put(i, value);
                concurrent.put(i, value);
                atomicArray.set(i, value);
UnsafeRefArrayAccess.soRefElement(
                        jctoolsUnsafeArray,
                        UnsafeRefArrayAccess.calcRefElementOffset(i),
                        value
                );
                concurrentHashMap.put((long) i, value);
jctoolsNonBlockingHashMapLong.put(i, value);
                hashMap.put((long) i, value);
                fastUtil.put(i, value);
trivagoLongLongBlocking.put(i, primitiveValues[i]);
trivagoLongLongBusyWaiting.put(i, primitiveValues[i]);
            }

            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < keyCount; i++) {
                readKeys[i] = random.nextInt(filled);
                writeKeys[i] = random.nextInt(capacity);
            }
        }
    }

    @State(Scope.Thread)
    public static class Cursor {
        int index;

        long next(long[] keys) {
            int i = index++ & (keys.length - 1);
            return keys[i];
        }
    }

    @Benchmark
    public Object dense_get(DataSet data, Cursor cursor) {
        return data.dense.get(cursor.next(data.readKeys));
    }

    @Benchmark
    public Object dense_getAt(DataSet data, Cursor cursor) {
        return data.dense.getAt((int) cursor.next(data.readKeys));
    }

    @Benchmark
    public Object concurrent_auto_get(DataSet data, Cursor cursor) {
        return data.concurrent.get(cursor.next(data.readKeys));
    }

    @Benchmark
    public Object atomicReferenceArray_get(DataSet data, Cursor cursor) {
        return data.atomicArray.get((int) cursor.next(data.readKeys));
    }

    @Benchmark
    public Object jctoolsUnsafeRefArray_get(DataSet data, Cursor cursor) {
        long key = cursor.next(data.readKeys);
        return UnsafeRefArrayAccess.lvRefElement(
                data.jctoolsUnsafeArray,
                UnsafeRefArrayAccess.calcRefElementOffset(key)
        );
    }

    @Benchmark
    public Object concurrentHashMap_get(DataSet data, Cursor cursor) {
        return data.concurrentHashMap.get(cursor.next(data.readKeys));
    }

    @Benchmark
    public Object jctoolsNonBlockingHashMapLong_get(DataSet data, Cursor cursor) {
        return data.jctoolsNonBlockingHashMapLong.get(cursor.next(data.readKeys));
    }

    @Benchmark
    public Object hashMap_get(DataSet data, Cursor cursor) {
        return data.hashMap.get(cursor.next(data.readKeys));
    }

    @Benchmark
    public Object fastUtil_get(DataSet data, Cursor cursor) {
        return data.fastUtil.get(cursor.next(data.readKeys));
    }

    @Benchmark
    public long trivagoLongLongBlocking_get(DataSet data, Cursor cursor) {
        return data.trivagoLongLongBlocking.get(cursor.next(data.readKeys));
    }

    @Benchmark
    public long trivagoLongLongBusyWaiting_get(DataSet data, Cursor cursor) {
        return data.trivagoLongLongBusyWaiting.get(cursor.next(data.readKeys));
    }

    @Benchmark
    public void dense_put(DataSet data, Cursor cursor, Blackhole blackhole) {
        int key = (int) cursor.next(data.writeKeys);
        data.dense.put(key, data.values[key]);
        blackhole.consume(key);
    }

    @Benchmark
    public void dense_putAt(DataSet data, Cursor cursor, Blackhole blackhole) {
        int key = (int) cursor.next(data.writeKeys);
        data.dense.putAt(key, data.values[key]);
        blackhole.consume(key);
    }

    @Benchmark
    public void atomicReferenceArray_set(DataSet data, Cursor cursor, Blackhole blackhole) {
        int key = (int) cursor.next(data.writeKeys);
        data.atomicArray.set(key, data.values[key]);
        blackhole.consume(key);
    }

    @Benchmark
    public void jctoolsUnsafeRefArray_set(DataSet data, Cursor cursor, Blackhole blackhole) {
        int key = (int) cursor.next(data.writeKeys);
        UnsafeRefArrayAccess.soRefElement(
                data.jctoolsUnsafeArray,
                UnsafeRefArrayAccess.calcRefElementOffset(key),
                data.values[key]
        );
        blackhole.consume(key);
    }

    @Benchmark
    public void concurrentHashMap_put(DataSet data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.writeKeys);
        data.concurrentHashMap.put(key, data.values[(int) key]);
        blackhole.consume(key);
    }

    @Benchmark
    public void jctoolsNonBlockingHashMapLong_put(DataSet data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.writeKeys);
        data.jctoolsNonBlockingHashMapLong.put(key, data.values[(int) key]);
        blackhole.consume(key);
    }

    @Benchmark
    public void fastUtil_put(DataSet data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.writeKeys);
        data.fastUtil.put(key, data.values[(int) key]);
        blackhole.consume(key);
    }

    @Benchmark
    public void trivagoLongLongBlocking_put(DataSet data, Cursor cursor, Blackhole blackhole) {
        int key = (int) cursor.next(data.writeKeys);
        data.trivagoLongLongBlocking.put(key, data.primitiveValues[key]);
        blackhole.consume(key);
    }

    @Benchmark
    public void trivagoLongLongBusyWaiting_put(DataSet data, Cursor cursor, Blackhole blackhole) {
        int key = (int) cursor.next(data.writeKeys);
        data.trivagoLongLongBusyWaiting.put(key, data.primitiveValues[key]);
        blackhole.consume(key);
    }

    @Benchmark
    @Threads(4)
    public void dense_multithread_put(DataSet data, Counter counter, Blackhole blackhole) {
        int key = counter.next(data.capacity);
        data.dense.put(key, data.values[key]);
        blackhole.consume(key);
    }

    @Benchmark
    @Threads(4)
    public void atomicReferenceArray_multithread_set(DataSet data, Counter counter, Blackhole blackhole) {
        int key = counter.next(data.capacity);
        data.atomicArray.set(key, data.values[key]);
        blackhole.consume(key);
    }

    @Benchmark
    @Threads(4)
    public void jctoolsUnsafeRefArray_multithread_set(DataSet data, Counter counter, Blackhole blackhole) {
        int key = counter.next(data.capacity);
        UnsafeRefArrayAccess.soRefElement(
                data.jctoolsUnsafeArray,
                UnsafeRefArrayAccess.calcRefElementOffset(key),
                data.values[key]
        );
        blackhole.consume(key);
    }

    @Benchmark
    @Threads(4)
    public void concurrentHashMap_multithread_put(DataSet data, Counter counter, Blackhole blackhole) {
        int key = counter.next(data.capacity);
        data.concurrentHashMap.put((long) key, data.values[key]);
        blackhole.consume(key);
    }

    @Benchmark
    @Threads(4)
    public void jctoolsNonBlockingHashMapLong_multithread_put(DataSet data, Counter counter, Blackhole blackhole) {
        int key = counter.next(data.capacity);
        data.jctoolsNonBlockingHashMapLong.put(key, data.values[key]);
        blackhole.consume(key);
    }

    @Benchmark
    @Threads(4)
    public void trivagoLongLongBlocking_multithread_put(DataSet data, Counter counter, Blackhole blackhole) {
        int key = counter.next(data.capacity);
        data.trivagoLongLongBlocking.put(key, data.primitiveValues[key]);
        blackhole.consume(key);
    }

    @Benchmark
    @Threads(4)
    public void trivagoLongLongBusyWaiting_multithread_put(DataSet data, Counter counter, Blackhole blackhole) {
        int key = counter.next(data.capacity);
        data.trivagoLongLongBusyWaiting.put(key, data.primitiveValues[key]);
        blackhole.consume(key);
    }

    @State(Scope.Benchmark)
    public static class Counter {
        private final AtomicInteger cursor = new AtomicInteger();

        int next(int capacity) {
            return Math.floorMod(cursor.getAndIncrement(), capacity);
        }
    }

    private record Payload(long id, long stamp) {
    }
}

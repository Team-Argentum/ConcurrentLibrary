package net.sixik.concurrent_library.long2int;

import com.trivago.fastutilconcurrentwrapper.ConcurrentLongIntMapBuilder;
import com.trivago.fastutilconcurrentwrapper.LongIntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.jctools.maps.NonBlockingHashMapLong;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 1)
public class Long2IntBenchmark {
    private static final int KEY_RING = 1 << 15;
    private static final int MISSING_VALUE = Integer.MIN_VALUE;

    @State(Scope.Benchmark)
    public static class DataSet {
        @Param({"synthetic", "large"})
        public String dataSet;

        @Param({"0.50", "0.90"})
        public double fillRatio;

        int capacity;
        int filled;
        long[] keys;
        int[] values;
        long[] readHits;
        long[] readMisses;
        long[] updateKeys;
        long[] insertKeys;
        Long2IntLookup lookup;
        Long2IntAppendMap fixed;
        Long2IntMap dynamic;
        Long2IntMap managed;
        Long2IntOpenHashMap fastutil;
        LongIntMap trivagoBlocking;
        LongIntMap trivagoBusyWaiting;
        NonBlockingHashMapLong<Integer> jctools;

        @Setup(Level.Trial)
        public void setup() {
            capacity = dataSet.equals("large") ? 1 << 20 : 1 << 16;
            filled = Math.max(1, (int) (capacity * fillRatio));
            keys = new long[filled];
            values = new int[filled];
            readHits = new long[KEY_RING];
            readMisses = new long[KEY_RING];
            updateKeys = new long[KEY_RING];
            insertKeys = new long[KEY_RING];

            for (int i = 0; i < filled; i++) {
                keys[i] = keyFor(i);
                values[i] = valueFor(i);
            }

            lookup = Long2Int.lookup(keys, values);
            fixed = Long2Int.fixedBuilder(filled).putAll(keys, values).build();
            dynamic = Long2Int.concurrent(filled);
            managed = Long2Int.concurrentManaged(filled);
            fastutil = new Long2IntOpenHashMap(filled);
            fastutil.defaultReturnValue(MISSING_VALUE);
            trivagoBlocking = trivago(ConcurrentLongIntMapBuilder.MapMode.BLOCKING, filled);
            trivagoBusyWaiting = trivago(ConcurrentLongIntMapBuilder.MapMode.BUSY_WAITING, filled);
            jctools = new NonBlockingHashMapLong<>(filled);

            for (int i = 0; i < filled; i++) {
                long key = keys[i];
                int value = values[i];
                dynamic.put(key, value);
                managed.put(key, value);
                fastutil.put(key, value);
                trivagoBlocking.put(key, value);
                trivagoBusyWaiting.put(key, value);
                jctools.put(key, Integer.valueOf(value));
            }
            dynamic.completeResize();
            managed.completeResize();

            ThreadLocalRandom random = ThreadLocalRandom.current();
            int insertBase = capacity * 4;
            for (int i = 0; i < KEY_RING; i++) {
                int hit = random.nextInt(filled);
                readHits[i] = keys[hit];
                updateKeys[i] = keys[random.nextInt(filled)];
                readMisses[i] = keyFor(insertBase + i + 1_000_000);
                insertKeys[i] = keyFor(insertBase + i);
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            close(lookup);
            close(fixed);
            close(dynamic);
            close(managed);
        }

        private static LongIntMap trivago(ConcurrentLongIntMapBuilder.MapMode mode, int capacity) {
            return ConcurrentLongIntMapBuilder.newBuilder()
                    .withInitialCapacity(capacity)
                    .withBuckets(Math.max(16, Runtime.getRuntime().availableProcessors() * 2))
                    .withMode(mode)
                    .withDefaultValue(MISSING_VALUE)
                    .build();
        }

        private static void close(AutoCloseable closeable) {
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @State(Scope.Thread)
    public static class Cursor {
        int cursor;

        long next(long[] keys) {
            return keys[cursor++ & (keys.length - 1)];
        }
    }

    @State(Scope.Benchmark)
    public static class SharedCursor {
        final AtomicInteger cursor = new AtomicInteger();

        long next(long[] keys) {
            return keys[cursor.getAndIncrement() & (keys.length - 1)];
        }
    }

    @Benchmark
    public int long2int_lookup_getHit(DataSet data, Cursor cursor) {
        return data.lookup.getOrDefault(cursor.next(data.readHits), MISSING_VALUE);
    }

    @Benchmark
    public int long2int_fixed_getHit(DataSet data, Cursor cursor) {
        return data.fixed.getOrDefault(cursor.next(data.readHits), MISSING_VALUE);
    }

    @Benchmark
    public int long2int_dynamic_getHit(DataSet data, Cursor cursor) {
        return data.dynamic.getOrDefault(cursor.next(data.readHits), MISSING_VALUE);
    }

    @Benchmark
    public int long2int_managed_getHit(DataSet data, Cursor cursor) {
        return data.managed.getOrDefault(cursor.next(data.readHits), MISSING_VALUE);
    }

    @Benchmark
    public int fastutil_getHit(DataSet data, Cursor cursor) {
        return data.fastutil.get(cursor.next(data.readHits));
    }

    @Benchmark
    public int trivagoBlocking_getHit(DataSet data, Cursor cursor) {
        return data.trivagoBlocking.get(cursor.next(data.readHits));
    }

    @Benchmark
    public int trivagoBusyWaiting_getHit(DataSet data, Cursor cursor) {
        return data.trivagoBusyWaiting.get(cursor.next(data.readHits));
    }

    @Benchmark
    public int jctools_getHit(DataSet data, Cursor cursor) {
        Integer value = data.jctools.get(cursor.next(data.readHits));
        return value == null ? MISSING_VALUE : value;
    }

    @Benchmark
    public int long2int_lookup_getMiss(DataSet data, Cursor cursor) {
        return data.lookup.getOrDefault(cursor.next(data.readMisses), MISSING_VALUE);
    }

    @Benchmark
    public int long2int_fixed_getMiss(DataSet data, Cursor cursor) {
        return data.fixed.getOrDefault(cursor.next(data.readMisses), MISSING_VALUE);
    }

    @Benchmark
    public int long2int_dynamic_getMiss(DataSet data, Cursor cursor) {
        return data.dynamic.getOrDefault(cursor.next(data.readMisses), MISSING_VALUE);
    }

    @Benchmark
    public int long2int_managed_getMiss(DataSet data, Cursor cursor) {
        return data.managed.getOrDefault(cursor.next(data.readMisses), MISSING_VALUE);
    }

    @Benchmark
    public int fastutil_getMiss(DataSet data, Cursor cursor) {
        return data.fastutil.get(cursor.next(data.readMisses));
    }

    @Benchmark
    public int trivagoBlocking_getMiss(DataSet data, Cursor cursor) {
        return data.trivagoBlocking.get(cursor.next(data.readMisses));
    }

    @Benchmark
    public int trivagoBusyWaiting_getMiss(DataSet data, Cursor cursor) {
        return data.trivagoBusyWaiting.get(cursor.next(data.readMisses));
    }

    @Benchmark
    public int jctools_getMiss(DataSet data, Cursor cursor) {
        Integer value = data.jctools.get(cursor.next(data.readMisses));
        return value == null ? MISSING_VALUE : value;
    }

    @Benchmark
    public void long2int_fixed_putUpdate(DataSet data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.updateKeys);
        blackhole.consume(data.fixed.put(key, (int) key));
    }

    @Benchmark
    public void long2int_dynamic_putUpdate(DataSet data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.updateKeys);
        blackhole.consume(data.dynamic.put(key, (int) key));
    }

    @Benchmark
    public void long2int_managed_putUpdate(DataSet data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.updateKeys);
        blackhole.consume(data.managed.put(key, (int) key));
    }

    @Benchmark
    public void fastutil_putUpdate(DataSet data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.updateKeys);
        blackhole.consume(data.fastutil.put(key, (int) key));
    }

    @Benchmark
    public void trivagoBlocking_putUpdate(DataSet data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.updateKeys);
        blackhole.consume(data.trivagoBlocking.put(key, (int) key));
    }

    @Benchmark
    public void trivagoBusyWaiting_putUpdate(DataSet data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.updateKeys);
        blackhole.consume(data.trivagoBusyWaiting.put(key, (int) key));
    }

    @Benchmark
    public void jctools_putUpdate(DataSet data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.updateKeys);
        blackhole.consume(data.jctools.put(key, Integer.valueOf((int) key)));
    }

    @Benchmark
    @Threads(4)
    public void long2int_dynamic_multithreadGetHit(DataSet data, SharedCursor cursor, Blackhole blackhole) {
        blackhole.consume(data.dynamic.getOrDefault(cursor.next(data.readHits), MISSING_VALUE));
    }

    @Benchmark
    @Threads(4)
    public void long2int_managed_multithreadGetHit(DataSet data, SharedCursor cursor, Blackhole blackhole) {
        blackhole.consume(data.managed.getOrDefault(cursor.next(data.readHits), MISSING_VALUE));
    }

    @Benchmark
    @Threads(4)
    public void trivagoBlocking_multithreadGetHit(DataSet data, SharedCursor cursor, Blackhole blackhole) {
        blackhole.consume(data.trivagoBlocking.get(cursor.next(data.readHits)));
    }

    @Benchmark
    @Threads(4)
    public void trivagoBusyWaiting_multithreadGetHit(DataSet data, SharedCursor cursor, Blackhole blackhole) {
        blackhole.consume(data.trivagoBusyWaiting.get(cursor.next(data.readHits)));
    }

    @Benchmark
    @Threads(4)
    public void jctools_multithreadGetHit(DataSet data, SharedCursor cursor, Blackhole blackhole) {
        blackhole.consume(data.jctools.get(cursor.next(data.readHits)));
    }

    @Benchmark
    @Threads(4)
    public void long2int_dynamic_multithreadPutUpdate(DataSet data, SharedCursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.updateKeys);
        blackhole.consume(data.dynamic.put(key, (int) key));
    }

    @Benchmark
    @Threads(4)
    public void long2int_managed_multithreadPutUpdate(DataSet data, SharedCursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.updateKeys);
        blackhole.consume(data.managed.put(key, (int) key));
    }

    @Benchmark
    @Threads(4)
    public void trivagoBlocking_multithreadPutUpdate(DataSet data, SharedCursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.updateKeys);
        blackhole.consume(data.trivagoBlocking.put(key, (int) key));
    }

    @Benchmark
    @Threads(4)
    public void trivagoBusyWaiting_multithreadPutUpdate(DataSet data, SharedCursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.updateKeys);
        blackhole.consume(data.trivagoBusyWaiting.put(key, (int) key));
    }

    @Benchmark
    @Threads(4)
    public void jctools_multithreadPutUpdate(DataSet data, SharedCursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.updateKeys);
        blackhole.consume(data.jctools.put(key, Integer.valueOf((int) key)));
    }

    private static long keyFor(int index) {
        return ((long) index * 0x9E3779B97F4A7C15L) ^ 0xD1B54A32D192ED03L;
    }

    private static int valueFor(int index) {
        return (index * 31) ^ (index >>> 3);
    }
}

package net.sixik.concurrent_library.collections.maps.long2int;

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

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 1)
public class Long2IntStressBenchmark {
    private static final int KEY_RING = 1 << 16;
    private static final int MISSING = Integer.MIN_VALUE;

    @State(Scope.Benchmark)
    public static class Data {
        @Param({"plain", "managed"})
        public String implementation;

        @Param({"1024", "65536"})
        public int initialSize;

        Long2IntMap map;
        long[] hotKeys;
        long[] insertKeys;
        long[] deleteKeys;

        @Setup(Level.Trial)
        public void setup() {
            map = implementation.equals("managed") ? new ManagedConcurrentLong2IntMap(initialSize) : new ConcurrentLong2IntMap(initialSize);
            hotKeys = new long[KEY_RING];
            insertKeys = new long[KEY_RING];
            deleteKeys = new long[KEY_RING];
            SplittableRandom random = new SplittableRandom(0x57E55_BEEFL + initialSize);
            int preload = Math.max(1, initialSize);
            for (int i = 0; i < preload; i++) {
                map.put(keyFor(i), valueFor(i));
            }
            map.completeResize();
            for (int i = 0; i < KEY_RING; i++) {
                hotKeys[i] = keyFor(random.nextInt(preload));
                insertKeys[i] = keyFor(preload + i);
                deleteKeys[i] = keyFor(random.nextInt(preload));
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            map.close();
        }
    }

    @State(Scope.Thread)
    public static class Cursor {
        final AtomicInteger index = new AtomicInteger();

        long next(long[] keys) {
            return keys[index.getAndIncrement() & (keys.length - 1)];
        }
    }

    @Benchmark
    @Threads(8)
    public void mt_mixed_read_update_resize_assist(Data data, Cursor cursor, Blackhole blackhole) {
        int index = cursor.index.getAndIncrement();
        int operation = index & 15;
        if (operation < 8) {
            blackhole.consume(data.map.getOrDefault(data.hotKeys[index & (data.hotKeys.length - 1)], MISSING));
        } else if (operation < 12) {
            long key = data.hotKeys[index & (data.hotKeys.length - 1)];
            blackhole.consume(data.map.put(key, valueFor(index)));
        } else if (operation < 14) {
            long key = data.insertKeys[index & (data.insertKeys.length - 1)];
            blackhole.consume(data.map.putIfAbsent(key, valueFor(index)));
        } else if (operation == 14) {
            long key = data.deleteKeys[index & (data.deleteKeys.length - 1)];
            blackhole.consume(data.map.removeAndGetOld(key, MISSING));
        } else {
            data.map.completeResize();
        }
    }

    @Benchmark
    @Threads(8)
    public void mt_add_if_present_contention(Data data, Cursor cursor, Blackhole blackhole) {
        blackhole.consume(data.map.addIfPresent(cursor.next(data.hotKeys), 1));
    }

    @Benchmark
    @Threads(8)
    public void mt_delete_reinsert_same_key_ring(Data data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.deleteKeys);
        data.map.remove(key);
        blackhole.consume(data.map.put(key, (int) key));
    }

    private static long keyFor(int index) {
        return ((long) index * 0x9E3779B97F4A7C15L) ^ 0xD1B54A32D192ED03L;
    }

    private static int valueFor(int index) {
        return (index * 31) ^ (index >>> 3);
    }
}

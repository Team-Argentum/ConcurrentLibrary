package net.sixik.concurrent_library.collections.maps.long2reference;

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

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 1)
public class Long2ReferenceStressBenchmark {
    private static final int KEY_RING = 1 << 16;
    private static final long BASE_KEY = 1_000_000L;

    @State(Scope.Benchmark)
    public static class Data {
        @Param({"hot", "cold"})
        public String hotRange;

        @Param({"4", "12"})
        public int pageBits;

        ConcurrentLong2ReferenceMap<Payload> map;
        Payload[] values;
        long[] existingKeys;
        long[] insertKeys;

        @Setup(Level.Trial)
        public void setup() {
            int capacity = 1 << 16;
            long hotBase = hotRange.equals("hot") ? BASE_KEY : BASE_KEY + capacity * 8L;
            map = new ConcurrentLong2ReferenceMap<>(hotBase, capacity, pageBits);
            values = new Payload[capacity * 2];
            for (int i = 0; i < values.length; i++) {
                values[i] = new Payload(BASE_KEY + i, i, "payload-" + i);
            }
            for (int i = 0; i < capacity; i++) {
                map.put(BASE_KEY + i, values[i]);
            }
            existingKeys = sampleKeys(1L, capacity, 0);
            insertKeys = sampleKeys(2L, capacity, capacity);
        }

        Payload valueFor(long key) {
            int index = Math.floorMod((int) (key - BASE_KEY), values.length);
            return values[index];
        }

        private long[] sampleKeys(long seed, int bound, int offset) {
            long[] keys = new long[KEY_RING];
            SplittableRandom random = new SplittableRandom(seed + pageBits * 31L);
            for (int i = 0; i < keys.length; i++) {
                keys[i] = BASE_KEY + offset + random.nextInt(bound);
            }
            return keys;
        }
    }

    @State(Scope.Thread)
    public static class Cursor {
        final AtomicInteger index = new AtomicInteger();

        int nextIndex() {
            return index.getAndIncrement();
        }
    }

    @Benchmark
    @Threads(8)
    public void mt_mixed_put_remove_vacuum_resize(Data data, Cursor cursor, Blackhole blackhole) {
        int index = cursor.nextIndex();
        int operation = index & 15;
        if (operation < 8) {
            blackhole.consume(data.map.get(data.existingKeys[index & (data.existingKeys.length - 1)]));
        } else if (operation < 11) {
            long key = data.existingKeys[index & (data.existingKeys.length - 1)];
            blackhole.consume(data.map.put(key, data.valueFor(key)));
        } else if (operation < 13) {
            long key = data.insertKeys[index & (data.insertKeys.length - 1)];
            blackhole.consume(data.map.putIfAbsent(key, data.valueFor(key)));
        } else if (operation == 13) {
            blackhole.consume(data.map.remove(data.insertKeys[index & (data.insertKeys.length - 1)]));
        } else if (operation == 14) {
            data.map.resize(BASE_KEY + (index & 1023), 1 << 15);
        } else {
            blackhole.consume(data.map.vacuum());
        }
    }

    @Benchmark
    @Threads(8)
    public void mt_compute_existing(Data data, Cursor cursor, Blackhole blackhole) {
        long key = data.existingKeys[cursor.nextIndex() & (data.existingKeys.length - 1)];
        blackhole.consume(data.map.computeIfPresent(key, (ignored, value) -> data.valueFor(key)));
    }

    public record Payload(long key, int value, String text) {
    }
}

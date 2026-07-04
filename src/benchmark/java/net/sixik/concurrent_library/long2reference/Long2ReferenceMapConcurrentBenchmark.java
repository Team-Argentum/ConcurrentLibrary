package net.sixik.concurrent_library.long2reference;

import com.trivago.fastutilconcurrentwrapper.ConcurrentLongLongMapBuilder;
import com.trivago.fastutilconcurrentwrapper.LongLongMap;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
public class Long2ReferenceMapConcurrentBenchmark {
    private static final long BASE_KEY = 10_000_000L;
    private static final int KEY_SAMPLE_SIZE = 1 << 16;
    private static final int BENCHMARK_THREADS = 8;

    @State(Scope.Benchmark)
    public static class Data {
        @Param({
                "long2ReferenceMapHot",
                "long2ReferenceMapCold",
                "concurrentHashMap",
                "jctoolsNonBlockingHashMapLong",
                "trivagoLongLongBlocking",
                "trivagoLongLongBusyWaiting"
        })
        public String implementation;

        @Param({"262144"})
        public int capacity;

        @Param({"0.90"})
        public double fillRatio;

        int filled;
        Long2ReferenceMap<Payload> long2ReferenceMap;
        ConcurrentHashMap<Long, Payload> concurrentHashMap;
        NonBlockingHashMapLong<Payload> jctoolsNonBlockingHashMapLong;
        LongLongMap trivagoLongLongMap;
        Payload[] values;
        Payload[] replacements;
        long[] primitiveValues;
        long[] primitiveReplacements;
        long[] existingKeys;
        long[] writeKeys;

        @Setup(Level.Trial)
        public void setup() {
            filled = Math.max(1, Math.min(capacity, (int) (capacity * fillRatio)));
            values = new Payload[capacity];
            replacements = new Payload[capacity];
            primitiveValues = new long[capacity];
            primitiveReplacements = new long[capacity];
            for (int slot = 0; slot < capacity; slot++) {
                long key = BASE_KEY + slot;
                values[slot] = new Payload(key, slot, slot * 31L, "value-" + slot);
                replacements[slot] = new Payload(key, slot, slot * 17L, "replacement-" + slot);
                primitiveValues[slot] = slot * 31L;
                primitiveReplacements[slot] = slot * 17L;
            }

            switch (implementation) {
                case "long2ReferenceMapHot" -> {
                    long2ReferenceMap = Long2ReferenceMap.<Payload>builder()
                            .hotRange(BASE_KEY, capacity)
                            .pageBits(12)
                            .build();
                    fillLong2ReferenceMap();
                }
                case "long2ReferenceMapCold" -> {
                    long2ReferenceMap = Long2ReferenceMap.<Payload>builder()
                            .hotRange(BASE_KEY + capacity * 4L, capacity)
                            .pageBits(12)
                            .build();
                    fillLong2ReferenceMap();
                }
                case "concurrentHashMap" -> {
                    concurrentHashMap = new ConcurrentHashMap<>(capacity);
                    for (int slot = 0; slot < filled; slot++) {
                        concurrentHashMap.put(BASE_KEY + slot, values[slot]);
                    }
                }
                case "jctoolsNonBlockingHashMapLong" -> {
                    jctoolsNonBlockingHashMapLong = new NonBlockingHashMapLong<>(capacity);
                    for (int slot = 0; slot < filled; slot++) {
                        jctoolsNonBlockingHashMapLong.put(BASE_KEY + slot, values[slot]);
                    }
                }
                case "trivagoLongLongBlocking" -> {
                    trivagoLongLongMap = trivago(ConcurrentLongLongMapBuilder.MapMode.BLOCKING, capacity);
                    fillTrivago();
                }
                case "trivagoLongLongBusyWaiting" -> {
                    trivagoLongLongMap = trivago(ConcurrentLongLongMapBuilder.MapMode.BUSY_WAITING, capacity);
                    fillTrivago();
                }
                default -> throw new IllegalStateException(implementation);
            }

            existingKeys = sampleKeys(1L, filled);
            writeKeys = sampleKeys(2L, filled);
        }

        Payload replacement(long key) {
            return replacements[(int) (key - BASE_KEY)];
        }

        long primitiveReplacement(long key) {
            return primitiveReplacements[(int) (key - BASE_KEY)];
        }

        private void fillLong2ReferenceMap() {
            for (int slot = 0; slot < filled; slot++) {
                long2ReferenceMap.put(BASE_KEY + slot, values[slot]);
            }
        }

        private void fillTrivago() {
            for (int slot = 0; slot < filled; slot++) {
                trivagoLongLongMap.put(BASE_KEY + slot, primitiveValues[slot]);
            }
        }

        private long[] sampleKeys(long seed, int bound) {
            long[] keys = new long[KEY_SAMPLE_SIZE];
            SplittableRandom random = new SplittableRandom(seed + capacity * 31L);
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

    @State(Scope.Thread)
    public static class Cursor {
        private static final AtomicInteger NEXT_THREAD = new AtomicInteger();

        int index;
        int stripe;

        @Setup(Level.Trial)
        public void setup() {
            stripe = Math.floorMod(NEXT_THREAD.getAndIncrement(), BENCHMARK_THREADS);
        }

        int nextIndex() {
            return index++;
        }

        long next(long[] keys) {
            return keys[nextIndex() & (keys.length - 1)];
        }

        long nextStripedKey(Data data) {
            int stripeSpan = Math.max(1, data.filled / BENCHMARK_THREADS);
            return BASE_KEY + (long) stripe * stripeSpan + Math.floorMod(nextIndex(), stripeSpan);
        }
    }

    @Benchmark
    @Threads(BENCHMARK_THREADS)
    public void mt_get_existing(Data data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.existingKeys);
        switch (data.implementation) {
            case "long2ReferenceMapHot", "long2ReferenceMapCold" -> blackhole.consume(data.long2ReferenceMap.get(key));
            case "concurrentHashMap" -> blackhole.consume(data.concurrentHashMap.get(key));
            case "jctoolsNonBlockingHashMapLong" -> blackhole.consume(data.jctoolsNonBlockingHashMapLong.get(key));
            case "trivagoLongLongBlocking", "trivagoLongLongBusyWaiting" -> blackhole.consume(data.trivagoLongLongMap.get(key));
            default -> throw new IllegalStateException(data.implementation);
        }
    }

    @Benchmark
    @Threads(BENCHMARK_THREADS)
    public void mt_put_existing(Data data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.next(data.writeKeys);
        switch (data.implementation) {
            case "long2ReferenceMapHot", "long2ReferenceMapCold" -> blackhole.consume(data.long2ReferenceMap.put(key, data.replacement(key)));
            case "concurrentHashMap" -> blackhole.consume(data.concurrentHashMap.put(key, data.replacement(key)));
            case "jctoolsNonBlockingHashMapLong" -> blackhole.consume(data.jctoolsNonBlockingHashMapLong.put(key, data.replacement(key)));
            case "trivagoLongLongBlocking", "trivagoLongLongBusyWaiting" -> blackhole.consume(data.trivagoLongLongMap.put(key, data.primitiveReplacement(key)));
            default -> throw new IllegalStateException(data.implementation);
        }
    }

    @Benchmark
    @Threads(BENCHMARK_THREADS)
    public void mt_put_existing_striped(Data data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.nextStripedKey(data);
        switch (data.implementation) {
            case "long2ReferenceMapHot", "long2ReferenceMapCold" -> blackhole.consume(data.long2ReferenceMap.put(key, data.replacement(key)));
            case "concurrentHashMap" -> blackhole.consume(data.concurrentHashMap.put(key, data.replacement(key)));
            case "jctoolsNonBlockingHashMapLong" -> blackhole.consume(data.jctoolsNonBlockingHashMapLong.put(key, data.replacement(key)));
            case "trivagoLongLongBlocking", "trivagoLongLongBusyWaiting" -> blackhole.consume(data.trivagoLongLongMap.put(key, data.primitiveReplacement(key)));
            default -> throw new IllegalStateException(data.implementation);
        }
    }

    @Benchmark
    @Threads(BENCHMARK_THREADS)
    public void mt_set_existing_striped(Data data, Cursor cursor, Blackhole blackhole) {
        long key = cursor.nextStripedKey(data);
        switch (data.implementation) {
            case "long2ReferenceMapHot", "long2ReferenceMapCold" -> blackhole.consume(data.long2ReferenceMap.setExisting(key, data.replacement(key)));
            case "concurrentHashMap" -> blackhole.consume(data.concurrentHashMap.replace(key, data.replacement(key)));
            case "jctoolsNonBlockingHashMapLong" -> blackhole.consume(data.jctoolsNonBlockingHashMapLong.replace(key, data.replacement(key)));
            case "trivagoLongLongBlocking", "trivagoLongLongBusyWaiting" -> blackhole.consume(data.trivagoLongLongMap.put(key, data.primitiveReplacement(key)));
            default -> throw new IllegalStateException(data.implementation);
        }
    }

    @Benchmark
    @Threads(BENCHMARK_THREADS)
    public void mt_mixed_90_read_10_write(Data data, Cursor cursor, Blackhole blackhole) {
        int index = cursor.nextIndex();
        boolean read = (index & 1023) < 922;
        long key = read
                ? data.existingKeys[index & (data.existingKeys.length - 1)]
                : data.writeKeys[index & (data.writeKeys.length - 1)];

        switch (data.implementation) {
            case "long2ReferenceMapHot", "long2ReferenceMapCold" -> {
                if (read) {
                    blackhole.consume(data.long2ReferenceMap.get(key));
                } else {
                    blackhole.consume(data.long2ReferenceMap.put(key, data.replacement(key)));
                }
            }
            case "concurrentHashMap" -> {
                if (read) {
                    blackhole.consume(data.concurrentHashMap.get(key));
                } else {
                    blackhole.consume(data.concurrentHashMap.put(key, data.replacement(key)));
                }
            }
            case "jctoolsNonBlockingHashMapLong" -> {
                if (read) {
                    blackhole.consume(data.jctoolsNonBlockingHashMapLong.get(key));
                } else {
                    blackhole.consume(data.jctoolsNonBlockingHashMapLong.put(key, data.replacement(key)));
                }
            }
            case "trivagoLongLongBlocking", "trivagoLongLongBusyWaiting" -> {
                if (read) {
                    blackhole.consume(data.trivagoLongLongMap.get(key));
                } else {
                    blackhole.consume(data.trivagoLongLongMap.put(key, data.primitiveReplacement(key)));
                }
            }
            default -> throw new IllegalStateException(data.implementation);
        }
    }

    public record Payload(long key, int slot, long checksum, String text) {
    }
}

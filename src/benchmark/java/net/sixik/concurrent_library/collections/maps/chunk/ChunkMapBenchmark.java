package net.sixik.concurrent_library.collections.maps.chunk;

import net.sixik.concurrent_library.collections.maps.long2reference.ConcurrentLong2ReferenceMap;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 1)
public class ChunkMapBenchmark {
    private static final int KEY_RING = 1 << 16;

    @State(Scope.Benchmark)
    public static class Data {
        @Param({"5", "6"})
        public int regionBits;

        @Param({"128", "256"})
        public int squareSize;

        long[] keys;
        int[] chunkXs;
        int[] chunkZs;
        Payload[] values;
        long[] primitiveValues;
        ChunkLong2ReferenceMap<Payload> chunkReference;
        ConcurrentLong2ReferenceMap<Payload> genericReference;
        NonBlockingHashMapLong<Payload> jctoolsReference;
        ChunkLong2LongMap chunkLong;
        NonBlockingHashMapLong<Long> jctoolsLong;

        @Setup(Level.Trial)
        public void setup() {
            int mappings = squareSize * squareSize;
            keys = new long[KEY_RING];
            chunkXs = new int[KEY_RING];
            chunkZs = new int[KEY_RING];
            values = new Payload[mappings];
            primitiveValues = new long[mappings];

            chunkReference = new ChunkLong2ReferenceMap<>(regionBits);
            genericReference = new ConcurrentLong2ReferenceMap<>();
            jctoolsReference = new NonBlockingHashMapLong<>(mappings);
            chunkLong = new ChunkLong2LongMap(regionBits, Long.MIN_VALUE);
            jctoolsLong = new NonBlockingHashMapLong<>(mappings);

            int half = squareSize >>> 1;
            for (int i = 0; i < mappings; i++) {
                int x = (i & (squareSize - 1)) - half;
                int z = (i >>> Integer.numberOfTrailingZeros(squareSize)) - half;
                long key = ChunkKey.pack(x, z);
                Payload value = new Payload(x, z, i, "chunk-" + i);
                long primitive = (((long) x) << 32) ^ (z & 0xffff_ffffL) ^ i;
                values[i] = value;
                primitiveValues[i] = primitive;
                chunkReference.put(x, z, value);
                genericReference.put(key, value);
                jctoolsReference.put(key, value);
                chunkLong.put(x, z, primitive);
                jctoolsLong.put(key, Long.valueOf(primitive));
            }

            SplittableRandom random = new SplittableRandom(0xC0FFEE1234L + regionBits * 31L + squareSize);
            for (int i = 0; i < KEY_RING; i++) {
                int index = random.nextInt(mappings);
                int x = (index & (squareSize - 1)) - half;
                int z = (index >>> Integer.numberOfTrailingZeros(squareSize)) - half;
                chunkXs[i] = x;
                chunkZs[i] = z;
                keys[i] = ChunkKey.pack(x, z);
            }
        }

        Payload replacement(int index) {
            return values[index & (values.length - 1)];
        }

        long primitiveReplacement(int index) {
            return primitiveValues[index & (primitiveValues.length - 1)];
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
    public Object chunkReference_getHit(Data data, Cursor cursor) {
        int index = cursor.nextIndex() & (KEY_RING - 1);
        return data.chunkReference.get(data.chunkXs[index], data.chunkZs[index]);
    }

    @Benchmark
    @Threads(8)
    public Object genericReference_getHit(Data data, Cursor cursor) {
        return data.genericReference.get(data.keys[cursor.nextIndex() & (KEY_RING - 1)]);
    }

    @Benchmark
    @Threads(8)
    public Object jctoolsReference_getHit(Data data, Cursor cursor) {
        return data.jctoolsReference.get(data.keys[cursor.nextIndex() & (KEY_RING - 1)]);
    }

    @Benchmark
    @Threads(8)
    public void chunkReference_putExisting(Data data, Cursor cursor, Blackhole blackhole) {
        int raw = cursor.nextIndex();
        int index = raw & (KEY_RING - 1);
        blackhole.consume(data.chunkReference.put(data.chunkXs[index], data.chunkZs[index], data.replacement(raw)));
    }

    @Benchmark
    @Threads(8)
    public void genericReference_putExisting(Data data, Cursor cursor, Blackhole blackhole) {
        int raw = cursor.nextIndex();
        blackhole.consume(data.genericReference.put(data.keys[raw & (KEY_RING - 1)], data.replacement(raw)));
    }

    @Benchmark
    @Threads(8)
    public void jctoolsReference_putExisting(Data data, Cursor cursor, Blackhole blackhole) {
        int raw = cursor.nextIndex();
        blackhole.consume(data.jctoolsReference.put(data.keys[raw & (KEY_RING - 1)], data.replacement(raw)));
    }

    @Benchmark
    @Threads(8)
    public long chunkLong_getHit(Data data, Cursor cursor) {
        int index = cursor.nextIndex() & (KEY_RING - 1);
        return data.chunkLong.get(data.chunkXs[index], data.chunkZs[index]);
    }

    @Benchmark
    @Threads(8)
    public long jctoolsLong_getHit(Data data, Cursor cursor) {
        Long value = data.jctoolsLong.get(data.keys[cursor.nextIndex() & (KEY_RING - 1)]);
        return value == null ? Long.MIN_VALUE : value.longValue();
    }

    @Benchmark
    @Threads(8)
    public void chunkLong_putExisting(Data data, Cursor cursor, Blackhole blackhole) {
        int raw = cursor.nextIndex();
        int index = raw & (KEY_RING - 1);
        blackhole.consume(data.chunkLong.put(data.chunkXs[index], data.chunkZs[index], data.primitiveReplacement(raw)));
    }

    @Benchmark
    @Threads(8)
    public void jctoolsLong_putExisting(Data data, Cursor cursor, Blackhole blackhole) {
        int raw = cursor.nextIndex();
        blackhole.consume(data.jctoolsLong.put(data.keys[raw & (KEY_RING - 1)], Long.valueOf(data.primitiveReplacement(raw))));
    }

    public record Payload(int chunkX, int chunkZ, int index, String label) {
    }
}

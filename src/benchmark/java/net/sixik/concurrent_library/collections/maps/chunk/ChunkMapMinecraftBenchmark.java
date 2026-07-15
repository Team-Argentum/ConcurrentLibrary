package net.sixik.concurrent_library.collections.maps.chunk;

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

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 1)
public class ChunkMapMinecraftBenchmark {
    private static final int PATH_LENGTH = 1 << 10;
    private static final int PATH_MASK = PATH_LENGTH - 1;
    private static final int REGION_SPAN = 8;
    private static final int VALUE_RING = 1 << 12;

    @State(Scope.Benchmark)
    public static class SharedWorld {
        @Param({"5", "6"})
        public int regionBits;

        @Param({"8", "12"})
        public int viewDistance;

        int[] centerXs;
        int[] centerZs;
        ChunkLong2ReferenceMap<Payload> chunkReference;
        NonBlockingHashMapLong<Payload> jctoolsReference;
        ChunkLong2LongMap chunkLong;
        NonBlockingHashMapLong<Long> jctoolsLong;

        @Setup(Level.Trial)
        public void setup() {
            int regionSize = 1 << regionBits;
            int worldSize = regionSize * REGION_SPAN;
            int worldHalf = worldSize >>> 1;
            int mappings = worldSize * worldSize;
            chunkReference = new ChunkLong2ReferenceMap<>(regionBits);
            jctoolsReference = new NonBlockingHashMapLong<>(mappings);
            chunkLong = new ChunkLong2LongMap(regionBits, Long.MIN_VALUE);
            jctoolsLong = new NonBlockingHashMapLong<>(mappings);

            int index = 0;
            for (int x = -worldHalf; x < worldHalf; x++) {
                for (int z = -worldHalf; z < worldHalf; z++) {
                    Payload payload = new Payload(x, z, index);
                    long primitive = primitiveValue(x, z, index);
                    long key = ChunkKey.pack(x, z);
                    chunkReference.put(x, z, payload);
                    jctoolsReference.put(key, payload);
                    chunkLong.put(x, z, primitive);
                    jctoolsLong.put(key, Long.valueOf(primitive));
                    index++;
                }
            }

            int margin = viewDistance + 2;
            int minCenter = -worldHalf + margin;
            int span = worldSize - margin * 2;
            centerXs = new int[PATH_LENGTH];
            centerZs = new int[PATH_LENGTH];
            for (int i = 0; i < PATH_LENGTH; i++) {
                centerXs[i] = minCenter + (i % span);
                centerZs[i] = minCenter + ((i * 31) % span);
            }
        }
    }

    @State(Scope.Thread)
    public static class Cursor {
        int index;

        int nextIndex() {
            return index++;
        }
    }

    @State(Scope.Thread)
    public static class PayloadAccumulator implements ChunkObjConsumer<Payload> {
        long sum;

        @Override
        public void accept(int chunkX, int chunkZ, Payload value) {
            sum += value.index();
        }
    }

    @State(Scope.Thread)
    public static class LongAccumulator implements ChunkLongConsumer {
        long sum;

        @Override
        public void accept(int chunkX, int chunkZ, long value) {
            sum += value;
        }
    }

    @State(Scope.Thread)
    public static class MovingWindow {
        @Param({"5", "6"})
        public int regionBits;

        @Param({"8", "12"})
        public int viewDistance;

        int step;
        Payload[] values;
        Payload[][] payloadColumns;
        long[][] primitiveColumns;
        ChunkLong2ReferenceMap<Payload> chunkReference;
        NonBlockingHashMapLong<Payload> jctoolsReference;
        ChunkLong2LongMap chunkLong;
        NonBlockingHashMapLong<Long> jctoolsLong;

        @Setup(Level.Iteration)
        public void setup() {
            values = payloadRing();
            int diameter = viewDistance * 2 + 1;
            payloadColumns = new Payload[VALUE_RING][diameter];
            primitiveColumns = new long[VALUE_RING][diameter];
            for (int i = 0; i < VALUE_RING; i++) {
                for (int z = 0; z < diameter; z++) {
                    int chunkZ = z - viewDistance;
                    payloadColumns[i][z] = values[(i + z) & (VALUE_RING - 1)];
                    primitiveColumns[i][z] = primitiveValue(i, chunkZ, i + z);
                }
            }
            int mappings = (diameter + 1) * diameter;
            chunkReference = new ChunkLong2ReferenceMap<>(regionBits);
            jctoolsReference = new NonBlockingHashMapLong<>(mappings);
            chunkLong = new ChunkLong2LongMap(regionBits, Long.MIN_VALUE);
            jctoolsLong = new NonBlockingHashMapLong<>(mappings);

            int index = 0;
            for (int x = -viewDistance; x <= viewDistance; x++) {
                for (int z = -viewDistance; z <= viewDistance; z++) {
                    putAll(x, z, values[index & (VALUE_RING - 1)], primitiveValue(x, z, index));
                    index++;
                }
            }
            step = 0;
        }

        void slideChunkReference() {
            boolean east = (step++ & 1) == 0;
            int removeX = east ? -viewDistance : viewDistance + 1;
            int addX = east ? viewDistance + 1 : -viewDistance;
            Payload payload = values[step & (VALUE_RING - 1)];
            for (int z = -viewDistance; z <= viewDistance; z++) {
                chunkReference.remove(removeX, z);
                chunkReference.put(addX, z, payload);
            }
        }

        void slideChunkReferenceBulk() {
            boolean east = (step++ & 1) == 0;
            int removeX = east ? -viewDistance : viewDistance + 1;
            int addX = east ? viewDistance + 1 : -viewDistance;
            Payload payload = values[step & (VALUE_RING - 1)];
            chunkReference.removeColumn(removeX, -viewDistance, viewDistance);
            chunkReference.putColumn(addX, -viewDistance, viewDistance, (x, z) -> payload);
        }

        void slideChunkReferenceArray() {
            boolean east = (step++ & 1) == 0;
            int removeX = east ? -viewDistance : viewDistance + 1;
            int addX = east ? viewDistance + 1 : -viewDistance;
            chunkReference.removeColumn(removeX, -viewDistance, viewDistance);
            chunkReference.putColumn(addX, -viewDistance, payloadColumns[step & (VALUE_RING - 1)]);
        }

        void slideJctoolsReference() {
            boolean east = (step++ & 1) == 0;
            int removeX = east ? -viewDistance : viewDistance + 1;
            int addX = east ? viewDistance + 1 : -viewDistance;
            Payload payload = values[step & (VALUE_RING - 1)];
            for (int z = -viewDistance; z <= viewDistance; z++) {
                jctoolsReference.remove(ChunkKey.pack(removeX, z));
                jctoolsReference.put(ChunkKey.pack(addX, z), payload);
            }
        }

        void slideChunkLong() {
            boolean east = (step++ & 1) == 0;
            int removeX = east ? -viewDistance : viewDistance + 1;
            int addX = east ? viewDistance + 1 : -viewDistance;
            for (int z = -viewDistance; z <= viewDistance; z++) {
                chunkLong.remove(removeX, z);
                chunkLong.put(addX, z, primitiveValue(addX, z, step));
            }
        }

        void slideChunkLongBulk() {
            boolean east = (step++ & 1) == 0;
            int removeX = east ? -viewDistance : viewDistance + 1;
            int addX = east ? viewDistance + 1 : -viewDistance;
            chunkLong.removeColumn(removeX, -viewDistance, viewDistance);
            chunkLong.putColumn(addX, -viewDistance, viewDistance, (x, z) -> primitiveValue(x, z, step));
        }

        void slideChunkLongArray() {
            boolean east = (step++ & 1) == 0;
            int removeX = east ? -viewDistance : viewDistance + 1;
            int addX = east ? viewDistance + 1 : -viewDistance;
            chunkLong.removeColumn(removeX, -viewDistance, viewDistance);
            chunkLong.putColumn(addX, -viewDistance, primitiveColumns[step & (VALUE_RING - 1)]);
        }

        void slideJctoolsLong() {
            boolean east = (step++ & 1) == 0;
            int removeX = east ? -viewDistance : viewDistance + 1;
            int addX = east ? viewDistance + 1 : -viewDistance;
            for (int z = -viewDistance; z <= viewDistance; z++) {
                jctoolsLong.remove(ChunkKey.pack(removeX, z));
                jctoolsLong.put(ChunkKey.pack(addX, z), Long.valueOf(primitiveValue(addX, z, step)));
            }
        }

        private void putAll(int x, int z, Payload payload, long primitive) {
            long key = ChunkKey.pack(x, z);
            chunkReference.put(x, z, payload);
            jctoolsReference.put(key, payload);
            chunkLong.put(x, z, primitive);
            jctoolsLong.put(key, Long.valueOf(primitive));
        }
    }

    @State(Scope.Thread)
    public static class RegionMaintenance {
        @Param({"5", "6"})
        public int regionBits;

        Payload[] values;
        ChunkLong2ReferenceMap<Payload> chunkReference;
        NonBlockingHashMapLong<Payload> jctoolsReference;
        ChunkLong2LongMap chunkLong;
        NonBlockingHashMapLong<Long> jctoolsLong;

        @Setup(Level.Iteration)
        public void setup() {
            values = payloadRing();
            int slots = 1 << (regionBits * 2);
            chunkReference = new ChunkLong2ReferenceMap<>(regionBits);
            jctoolsReference = new NonBlockingHashMapLong<>(slots);
            chunkLong = new ChunkLong2LongMap(regionBits, Long.MIN_VALUE);
            jctoolsLong = new NonBlockingHashMapLong<>(slots);
            fillAll();
        }

        long chunkReferenceRemoveReload() {
            long removed = chunkReference.removeRegion(0, 0);
            fillChunkReference();
            return removed;
        }

        long jctoolsReferenceRemoveReload() {
            long removed = removeJctoolsReferenceRegion();
            fillJctoolsReference();
            return removed;
        }

        long chunkLongRemoveReload() {
            long removed = chunkLong.removeRegion(0, 0);
            fillChunkLong();
            return removed;
        }

        long jctoolsLongRemoveReload() {
            long removed = removeJctoolsLongRegion();
            fillJctoolsLong();
            return removed;
        }

        private void fillAll() {
            fillChunkReference();
            fillJctoolsReference();
            fillChunkLong();
            fillJctoolsLong();
        }

        private void fillChunkReference() {
            int regionSize = 1 << regionBits;
            int index = 0;
            for (int x = 0; x < regionSize; x++) {
                for (int z = 0; z < regionSize; z++) {
                    chunkReference.put(x, z, values[index & (VALUE_RING - 1)]);
                    index++;
                }
            }
        }

        private void fillJctoolsReference() {
            int regionSize = 1 << regionBits;
            int index = 0;
            for (int x = 0; x < regionSize; x++) {
                for (int z = 0; z < regionSize; z++) {
                    jctoolsReference.put(ChunkKey.pack(x, z), values[index & (VALUE_RING - 1)]);
                    index++;
                }
            }
        }

        private void fillChunkLong() {
            int regionSize = 1 << regionBits;
            int index = 0;
            for (int x = 0; x < regionSize; x++) {
                for (int z = 0; z < regionSize; z++) {
                    chunkLong.put(x, z, primitiveValue(x, z, index));
                    index++;
                }
            }
        }

        private void fillJctoolsLong() {
            int regionSize = 1 << regionBits;
            int index = 0;
            for (int x = 0; x < regionSize; x++) {
                for (int z = 0; z < regionSize; z++) {
                    jctoolsLong.put(ChunkKey.pack(x, z), Long.valueOf(primitiveValue(x, z, index)));
                    index++;
                }
            }
        }

        private long removeJctoolsReferenceRegion() {
            int regionSize = 1 << regionBits;
            long removed = 0L;
            for (int x = 0; x < regionSize; x++) {
                for (int z = 0; z < regionSize; z++) {
                    if (jctoolsReference.remove(ChunkKey.pack(x, z)) != null) {
                        removed++;
                    }
                }
            }
            return removed;
        }

        private long removeJctoolsLongRegion() {
            int regionSize = 1 << regionBits;
            long removed = 0L;
            for (int x = 0; x < regionSize; x++) {
                for (int z = 0; z < regionSize; z++) {
                    if (jctoolsLong.remove(ChunkKey.pack(x, z)) != null) {
                        removed++;
                    }
                }
            }
            return removed;
        }
    }

    @Benchmark
    @Threads(8)
    public long chunkReference_viewDistanceScan(SharedWorld data, Cursor cursor) {
        int index = cursor.nextIndex() & PATH_MASK;
        int centerX = data.centerXs[index];
        int centerZ = data.centerZs[index];
        long sum = 0L;
        for (int dx = -data.viewDistance; dx <= data.viewDistance; dx++) {
            for (int dz = -data.viewDistance; dz <= data.viewDistance; dz++) {
                Payload payload = data.chunkReference.get(centerX + dx, centerZ + dz);
                if (payload != null) {
                    sum += payload.index();
                }
            }
        }
        return sum;
    }

    @Benchmark
    @Threads(8)
    public long jctoolsReference_viewDistanceScan(SharedWorld data, Cursor cursor) {
        int index = cursor.nextIndex() & PATH_MASK;
        int centerX = data.centerXs[index];
        int centerZ = data.centerZs[index];
        long sum = 0L;
        for (int dx = -data.viewDistance; dx <= data.viewDistance; dx++) {
            for (int dz = -data.viewDistance; dz <= data.viewDistance; dz++) {
                Payload payload = data.jctoolsReference.get(ChunkKey.pack(centerX + dx, centerZ + dz));
                if (payload != null) {
                    sum += payload.index();
                }
            }
        }
        return sum;
    }

    @Benchmark
    @Threads(8)
    public long chunkReference_viewDistanceForEach(SharedWorld data, Cursor cursor, PayloadAccumulator accumulator) {
        int index = cursor.nextIndex() & PATH_MASK;
        accumulator.sum = 0L;
        data.chunkReference.forEachInSquare(data.centerXs[index], data.centerZs[index], data.viewDistance, accumulator);
        return accumulator.sum;
    }

    @Benchmark
    @Threads(8)
    public long chunkLong_viewDistanceScan(SharedWorld data, Cursor cursor) {
        int index = cursor.nextIndex() & PATH_MASK;
        int centerX = data.centerXs[index];
        int centerZ = data.centerZs[index];
        long sum = 0L;
        for (int dx = -data.viewDistance; dx <= data.viewDistance; dx++) {
            for (int dz = -data.viewDistance; dz <= data.viewDistance; dz++) {
                sum += data.chunkLong.get(centerX + dx, centerZ + dz);
            }
        }
        return sum;
    }

    @Benchmark
    @Threads(8)
    public long chunkLong_viewDistanceForEach(SharedWorld data, Cursor cursor, LongAccumulator accumulator) {
        int index = cursor.nextIndex() & PATH_MASK;
        accumulator.sum = 0L;
        data.chunkLong.forEachInSquare(data.centerXs[index], data.centerZs[index], data.viewDistance, accumulator);
        return accumulator.sum;
    }

    @Benchmark
    @Threads(8)
    public long jctoolsLong_viewDistanceScan(SharedWorld data, Cursor cursor) {
        int index = cursor.nextIndex() & PATH_MASK;
        int centerX = data.centerXs[index];
        int centerZ = data.centerZs[index];
        long sum = 0L;
        for (int dx = -data.viewDistance; dx <= data.viewDistance; dx++) {
            for (int dz = -data.viewDistance; dz <= data.viewDistance; dz++) {
                Long value = data.jctoolsLong.get(ChunkKey.pack(centerX + dx, centerZ + dz));
                if (value != null) {
                    sum += value.longValue();
                }
            }
        }
        return sum;
    }

    @Benchmark
    @Threads(8)
    public void chunkReference_windowSlide(MovingWindow data) {
        data.slideChunkReference();
    }

    @Benchmark
    @Threads(8)
    public void chunkReference_windowSlideBulk(MovingWindow data) {
        data.slideChunkReferenceBulk();
    }

    @Benchmark
    @Threads(8)
    public void chunkReference_windowSlideArray(MovingWindow data) {
        data.slideChunkReferenceArray();
    }

    @Benchmark
    @Threads(8)
    public void jctoolsReference_windowSlide(MovingWindow data) {
        data.slideJctoolsReference();
    }

    @Benchmark
    @Threads(8)
    public void chunkLong_windowSlide(MovingWindow data) {
        data.slideChunkLong();
    }

    @Benchmark
    @Threads(8)
    public void chunkLong_windowSlideBulk(MovingWindow data) {
        data.slideChunkLongBulk();
    }

    @Benchmark
    @Threads(8)
    public void chunkLong_windowSlideArray(MovingWindow data) {
        data.slideChunkLongArray();
    }

    @Benchmark
    @Threads(8)
    public void jctoolsLong_windowSlide(MovingWindow data) {
        data.slideJctoolsLong();
    }

    @Benchmark
    @Threads(1)
    public long chunkReference_regionUnloadReload(RegionMaintenance data) {
        return data.chunkReferenceRemoveReload();
    }

    @Benchmark
    @Threads(1)
    public long jctoolsReference_regionUnloadReload(RegionMaintenance data) {
        return data.jctoolsReferenceRemoveReload();
    }

    @Benchmark
    @Threads(1)
    public long chunkLong_regionUnloadReload(RegionMaintenance data) {
        return data.chunkLongRemoveReload();
    }

    @Benchmark
    @Threads(1)
    public long jctoolsLong_regionUnloadReload(RegionMaintenance data) {
        return data.jctoolsLongRemoveReload();
    }

    private static Payload[] payloadRing() {
        Payload[] payloads = new Payload[VALUE_RING];
        for (int i = 0; i < payloads.length; i++) {
            payloads[i] = new Payload(i, -i, i);
        }
        return payloads;
    }

    private static long primitiveValue(int x, int z, int index) {
        return (((long) x) << 32) ^ (z & 0xffff_ffffL) ^ index;
    }

    public record Payload(int chunkX, int chunkZ, int index) {
    }
}

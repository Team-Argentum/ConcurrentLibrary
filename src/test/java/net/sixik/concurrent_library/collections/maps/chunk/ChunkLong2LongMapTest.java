package net.sixik.concurrent_library.collections.maps.chunk;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ChunkLong2LongMapTest {
    @Test
    void storesPrimitiveValuesIncludingMissingSentinel() {
        ChunkLong2LongMap map = new ChunkLong2LongMap(5, Long.MIN_VALUE);

        assertEquals(Long.MIN_VALUE, map.put(0, 0, Long.MIN_VALUE));
        assertTrue(map.containsKey(0, 0));
        assertEquals(Long.MIN_VALUE, map.get(0, 0));
        assertEquals(123L, map.getOrDefault(1, 1, 123L));

        assertEquals(Long.MIN_VALUE, map.put(0, 0, 42L));
        assertEquals(42L, map.get(0, 0));
        assertEquals(42L, map.remove(0, 0));
        assertFalse(map.containsKey(0, 0));
    }

    @Test
    void putIfAbsentCasAddSetExistingAndRegionClearWork() {
        ChunkLong2LongMap map = new ChunkLong2LongMap(4, -1L);

        assertEquals(-1L, map.putIfAbsent(10, 10, 5L));
        assertEquals(5L, map.putIfAbsent(10, 10, 6L));
        assertTrue(map.compareAndSet(10, 10, 5L, 7L));
        assertFalse(map.compareAndSet(10, 10, 5L, 8L));
        assertTrue(map.addIfPresent(10, 10, 3L));
        assertEquals(10L, map.get(10, 10));
        assertTrue(map.setExisting(10, 10, 11L));
        assertEquals(11L, map.get(10, 10));

        map.put(16, 16, 1L);
        map.put(17, 16, 2L);
        map.put(32, 16, 3L);
        assertEquals(2L, map.removeRegion(1, 1));
        assertFalse(map.containsKey(16, 16));
        assertFalse(map.containsKey(17, 16));
        assertEquals(3L, map.get(32, 16));
    }

    @Test
    void removedRegionCanBeCreatedAgainWithoutServingCachedValues() {
        ChunkLong2LongMap map = new ChunkLong2LongMap(4, -1L);

        assertEquals(-1L, map.put(16, 16, 10L));
        assertEquals(10L, map.get(16, 16));
        assertEquals(1L, map.removeRegion(1, 1));
        assertEquals(-1L, map.get(16, 16));

        assertEquals(-1L, map.put(16, 16, 20L));
        assertEquals(20L, map.get(16, 16));
        assertEquals(1L, map.mappingCount());
    }

    @Test
    void forEachSeesChunkCoordinatesAndValues() {
        ChunkLong2LongMap map = new ChunkLong2LongMap(3, -1L);
        map.put(-9, 7, 10L);
        map.put(8, -8, 20L);

        Map<Long, Long> seen = new HashMap<>();
        map.forEach((x, z, value) -> seen.put(ChunkKey.pack(x, z), value));

        assertEquals(Map.of(ChunkKey.pack(-9, 7), 10L, ChunkKey.pack(8, -8), 20L), seen);
    }

    @Test
    void forEachInAreaScansOnlyRequestedRectangleAcrossRegions() {
        ChunkLong2LongMap map = new ChunkLong2LongMap(3, -1L);
        map.put(-9, 7, 1L);
        map.put(-8, 7, 2L);
        map.put(0, 0, 3L);
        map.put(7, 7, 4L);
        map.put(8, -8, 5L);

        Map<Long, Long> area = new HashMap<>();
        map.forEachInArea(-8, 0, 7, 7, (x, z, value) -> area.put(ChunkKey.pack(x, z), value));
        assertEquals(Map.of(
                ChunkKey.pack(-8, 7), 2L,
                ChunkKey.pack(0, 0), 3L,
                ChunkKey.pack(7, 7), 4L
        ), area);

        Map<Long, Long> square = new HashMap<>();
        map.forEachInSquare(0, 0, 8, (x, z, value) -> square.put(ChunkKey.pack(x, z), value));
        assertEquals(Map.of(
                ChunkKey.pack(-8, 7), 2L,
                ChunkKey.pack(0, 0), 3L,
                ChunkKey.pack(7, 7), 4L,
                ChunkKey.pack(8, -8), 5L
        ), square);

        Map<Long, Long> inverted = new HashMap<>();
        map.forEachInArea(5, 5, 4, 4, (x, z, value) -> inverted.put(ChunkKey.pack(x, z), value));
        assertTrue(inverted.isEmpty());
    }

    @Test
    void bulkPutAndRemoveAreaColumnRowWorkAcrossRegions() {
        ChunkLong2LongMap map = new ChunkLong2LongMap(2, -1L);

        assertEquals(9L, map.putArea(-1, -1, 1, 1, (x, z) -> x * 31L + z));
        assertEquals(0L, map.putArea(-1, -1, 1, 1, (x, z) -> x * 101L + z));
        assertEquals(0L, map.get(0, 0));

        assertEquals(3L, map.removeColumn(0, -1, 1));
        assertFalse(map.containsKey(0, -1));
        assertFalse(map.containsKey(0, 0));
        assertFalse(map.containsKey(0, 1));

        assertEquals(2L, map.removeRow(-1, 1, 1));
        assertFalse(map.containsKey(-1, 1));
        assertFalse(map.containsKey(1, 1));

        assertEquals(4L, map.removeArea(-1, -1, 1, 1));
        assertTrue(map.isEmpty());
        assertEquals(0L, map.putArea(5, 5, 4, 4, (x, z) -> 42L));
        assertEquals(0L, map.removeArea(5, 5, 4, 4));

        assertEquals(3L, map.putColumn(4, -1, new long[]{-1L, 0L, 1L}));
        assertEquals(0L, map.get(4, 0));

        assertEquals(3L, map.putRow(-1, 4, new long[]{99L, -1L, 0L, 1L, 99L}, 1, 3));
        assertEquals(0L, map.get(0, 4));

        assertEquals(4L, map.putAreaRowMajor(-1, -1, 2, 2, new long[]{10L, 20L, 30L, 40L}));
        assertEquals(10L, map.get(-1, -1));
        assertEquals(20L, map.get(0, -1));
        assertEquals(30L, map.get(-1, 0));
        assertEquals(40L, map.get(0, 0));
        assertEquals(0L, map.putAreaRowMajor(-1, -1, 2, 2, new long[]{11L, 21L, 31L, 41L}));
        assertEquals(41L, map.get(0, 0));

        assertEquals(0L, map.putColumn(10, 10, new long[]{1L}, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> map.putColumn(10, 10, new long[]{1L}, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> map.putAreaRowMajor(0, 0, -1, 1, new long[]{1L}));
    }

    @Test
    void concurrentWritesToClusteredChunksKeepExactCount() throws Exception {
        ChunkLong2LongMap map = new ChunkLong2LongMap(5, -1L);
        int threads = 4;
        int perThread = 512;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            int thread = t;
            executor.submit(() -> {
                await(start);
                int baseX = thread * 64;
                for (int i = 0; i < perThread; i++) {
                    int x = baseX + (i & 63);
                    int z = i >>> 6;
                    map.putIfAbsent(x, z, ((long) thread << 32) | i);
                    map.addIfPresent(x, z, 1L);
                }
            });
        }

        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals((long) threads * perThread, map.mappingCount());
        for (int t = 0; t < threads; t++) {
            int baseX = t * 64;
            for (int i = 0; i < perThread; i++) {
                assertTrue(map.containsKey(baseX + (i & 63), i >>> 6));
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}

package net.sixik.concurrent_library.collections.maps.chunk;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ChunkLong2ReferenceMapTest {
    @Test
    void storesChunkReferencesAcrossRegionBoundaries() {
        ChunkLong2ReferenceMap<String> map = new ChunkLong2ReferenceMap<>(5);

        assertNull(map.put(0, 0, "origin"));
        assertNull(map.put(31, 31, "edge"));
        assertNull(map.put(32, 32, "next"));
        assertNull(map.put(-1, -1, "negative"));

        assertEquals("origin", map.get(ChunkKey.pack(0, 0)));
        assertEquals("edge", map.get(31, 31));
        assertEquals("next", map.get(32, 32));
        assertEquals("negative", map.get(-1, -1));
        assertEquals(4L, map.mappingCount());
        assertEquals(4L, map.stats().mappingCount());
        assertTrue(map.stats().currentRegions() >= 3L);
    }

    @Test
    void putIfAbsentSetExistingReplaceRemoveAndRegionClearWork() {
        ChunkLong2ReferenceMap<String> map = new ChunkLong2ReferenceMap<>(4);

        assertNull(map.putIfAbsent(7, 8, "first"));
        assertEquals("first", map.putIfAbsent(7, 8, "ignored"));
        assertTrue(map.setExisting(7, 8, "second"));
        assertEquals("second", map.get(7, 8));
        assertTrue(map.replace(7, 8, "second", "third"));
        assertEquals("third", map.remove(7, 8));
        assertNull(map.remove(7, 8));

        map.put(16, 16, "a");
        map.put(17, 16, "b");
        map.put(32, 16, "c");
        assertEquals(2L, map.removeRegion(1, 1));
        assertNull(map.get(16, 16));
        assertNull(map.get(17, 16));
        assertEquals("c", map.get(32, 16));
    }

    @Test
    void removedRegionCanBeCreatedAgainWithoutServingCachedValues() {
        ChunkLong2ReferenceMap<String> map = new ChunkLong2ReferenceMap<>(4);

        map.put(16, 16, "old");
        assertEquals("old", map.get(16, 16));
        assertEquals(1L, map.removeRegion(1, 1));
        assertNull(map.get(16, 16));

        assertNull(map.put(16, 16, "new"));
        assertEquals("new", map.get(16, 16));
        assertEquals(1L, map.mappingCount());
    }

    @Test
    void forEachSeesChunkCoordinates() {
        ChunkLong2ReferenceMap<String> map = new ChunkLong2ReferenceMap<>(3);
        map.put(-9, 7, "a");
        map.put(8, -8, "b");

        Map<Long, String> seen = new HashMap<>();
        map.forEach((x, z, value) -> seen.put(ChunkKey.pack(x, z), value));

        assertEquals(Map.of(ChunkKey.pack(-9, 7), "a", ChunkKey.pack(8, -8), "b"), seen);
    }

    @Test
    void forEachInAreaScansOnlyRequestedRectangleAcrossRegions() {
        ChunkLong2ReferenceMap<String> map = new ChunkLong2ReferenceMap<>(3);
        map.put(-9, 7, "outside-x");
        map.put(-8, 7, "left-edge");
        map.put(0, 0, "origin");
        map.put(7, 7, "right-edge");
        map.put(8, -8, "outside-z");

        Map<Long, String> area = new HashMap<>();
        map.forEachInArea(-8, 0, 7, 7, (x, z, value) -> area.put(ChunkKey.pack(x, z), value));
        assertEquals(Map.of(
                ChunkKey.pack(-8, 7), "left-edge",
                ChunkKey.pack(0, 0), "origin",
                ChunkKey.pack(7, 7), "right-edge"
        ), area);

        Map<Long, String> square = new HashMap<>();
        map.forEachInSquare(0, 0, 8, (x, z, value) -> square.put(ChunkKey.pack(x, z), value));
        assertEquals(Map.of(
                ChunkKey.pack(-8, 7), "left-edge",
                ChunkKey.pack(0, 0), "origin",
                ChunkKey.pack(7, 7), "right-edge",
                ChunkKey.pack(8, -8), "outside-z"
        ), square);

        Map<Long, String> inverted = new HashMap<>();
        map.forEachInArea(5, 5, 4, 4, (x, z, value) -> inverted.put(ChunkKey.pack(x, z), value));
        assertTrue(inverted.isEmpty());
    }

    @Test
    void bulkPutAndRemoveAreaColumnRowWorkAcrossRegions() {
        ChunkLong2ReferenceMap<String> map = new ChunkLong2ReferenceMap<>(2);

        assertEquals(9L, map.putArea(-1, -1, 1, 1, (x, z) -> x + ":" + z));
        assertEquals(0L, map.putArea(-1, -1, 1, 1, (x, z) -> "r" + x + ":" + z));
        assertEquals("r0:0", map.get(0, 0));

        assertEquals(3L, map.removeColumn(0, -1, 1));
        assertNull(map.get(0, -1));
        assertNull(map.get(0, 0));
        assertNull(map.get(0, 1));

        assertEquals(2L, map.removeRow(-1, 1, 1));
        assertNull(map.get(-1, 1));
        assertNull(map.get(1, 1));

        assertEquals(4L, map.removeArea(-1, -1, 1, 1));
        assertTrue(map.isEmpty());
        assertEquals(0L, map.putArea(5, 5, 4, 4, (x, z) -> "ignored"));
        assertEquals(0L, map.removeArea(5, 5, 4, 4));

        assertEquals(3L, map.putColumn(4, -1, new String[]{"c-1", "c0", "c1"}));
        assertEquals("c0", map.get(4, 0));

        assertEquals(3L, map.putRow(-1, 4, new String[]{"skip", "r-1", "r0", "r1", "skip"}, 1, 3));
        assertEquals("r0", map.get(0, 4));

        assertEquals(4L, map.putAreaRowMajor(-1, -1, 2, 2, new String[]{"a00", "a10", "a01", "a11"}));
        assertEquals("a00", map.get(-1, -1));
        assertEquals("a10", map.get(0, -1));
        assertEquals("a01", map.get(-1, 0));
        assertEquals("a11", map.get(0, 0));
        assertEquals(0L, map.putAreaRowMajor(-1, -1, 2, 2, new String[]{"b00", "b10", "b01", "b11"}));
        assertEquals("b11", map.get(0, 0));

        assertEquals(0L, map.putColumn(10, 10, new String[]{"unused"}, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> map.putColumn(10, 10, new String[]{"x"}, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> map.putAreaRowMajor(0, 0, -1, 1, new String[]{"x"}));
    }

    @Test
    void concurrentWritesToClusteredChunksKeepExactCount() throws Exception {
        ChunkLong2ReferenceMap<String> map = new ChunkLong2ReferenceMap<>(5);
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
                    map.putIfAbsent(x, z, thread + ":" + i);
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
                assertNotNull(map.get(baseX + (i & 63), i >>> 6));
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

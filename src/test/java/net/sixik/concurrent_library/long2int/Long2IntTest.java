package net.sixik.concurrent_library.long2int;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class Long2IntTest {
    @Test
    void dynamicImplementationsExposePublicConstructors() {
        try (Long2IntMap map = new Long2Int.DynamicDirectLong2IntMap(4)) {
            assertTrue(map.put(1L, 10));
            assertTrue(map.put(2L, 20));
            assertEquals(10, map.getOrDefault(1L, -1));
            assertEquals(20, map.removeAndGetOld(2L, -1));
            map.completeResize();
            assertTrue(Long2Int.inspect(map).healthCheck().healthy());
        }

        try (Long2IntMap map = new Long2Int.ManagedDynamicDirectLong2IntMap(4)) {
            for (long i = 0; i < 64; i++) {
                assertTrue(map.put(i, (int) i));
            }
            map.completeResize();
            assertFalse(map.resizeInProgress());
            assertEquals(63, map.getOrDefault(63L, -1));
            assertTrue(Long2Int.inspect(map).healthCheck().healthy());
        }

        assertThrows(IllegalArgumentException.class, () -> new Long2Int.DynamicDirectLong2IntMap(-1));
        assertThrows(IllegalArgumentException.class, () -> new Long2Int.ManagedDynamicDirectLong2IntMap(-1));
    }

    @Test
    void immutableLookupUsesLastValueForDuplicateKeys() {
        try (Long2IntLookup lookup = Long2Int.lookup(
                new long[]{1L, 2L, 1L, -7L},
                new int[]{10, 20, 30, 70})) {
            assertEquals(30, lookup.getOrDefault(1L, -1));
            assertEquals(20, lookup.getOrDefault(2L, -1));
            assertEquals(70, lookup.getOrDefault(-7L, -1));
            assertEquals(-1, lookup.getOrDefault(3L, -1));
            assertTrue(lookup.containsKey(1L));
            assertFalse(lookup.containsKey(3L));
            assertEquals(3L, lookup.size());
            assertEquals(3L, Long2Int.inspect(lookup).exactSize());
            assertTrue(Long2Int.inspect(lookup).healthCheck().healthy());
        }
    }

    @Test
    void fixedMapSupportsAppendUpdateCasAndNoImplicitZero() {
        try (Long2IntAppendMap map = Long2Int.fixed(8)) {
            assertEquals(-1, map.getOrDefault(42L, -1));
            assertTrue(map.put(42L, 7));
            assertEquals(7, map.getOrDefault(42L, -1));
            assertFalse(map.putIfAbsent(42L, 99));
            assertEquals(7, map.getOrDefault(42L, -1));
            assertTrue(map.compareAndSet(42L, 7, 8));
            assertFalse(map.compareAndSet(42L, 7, 9));
            assertTrue(map.replace(42L, 8, 10));
            assertTrue(map.addIfPresent(42L, 5));
            assertEquals(15, map.getOrDefault(42L, -1));
            assertEquals(15, map.getAndAddIfPresent(42L, 2, -1));
            assertEquals(17, map.getOrDefault(42L, -1));
            assertFalse(map.addIfPresent(43L, 1));
            assertEquals(-1, map.getAndAddIfPresent(43L, 1, -1));
            assertFalse(map.containsKey(43L));
            assertEquals(1L, map.size());
            assertTrue(Long2Int.inspect(map).healthCheck().healthy());
        }
    }

    @Test
    void dynamicMapDeletesReinsertsSameKeyAndDoesNotReuseDeletedForOthers() {
        try (Long2IntMap map = Long2Int.concurrent(4)) {
            assertTrue(map.put(1L, 10));
            assertTrue(map.put(2L, 20));
            assertEquals(10, map.removeAndGetOld(1L, -1));
            assertFalse(map.containsKey(1L));
            assertEquals(-1, map.getOrDefault(1L, -1));
            assertTrue(map.put(1L, 11));
            assertEquals(11, map.getOrDefault(1L, -1));
            assertTrue(map.remove(1L));
            assertFalse(map.remove(1L));

            for (long i = 3L; i < 64L; i++) {
                assertTrue(map.put(i, (int) i));
            }
            map.completeResize();
            assertEquals(20, map.getOrDefault(2L, -1));
            assertEquals(63, map.getOrDefault(63L, -1));
            assertFalse(map.containsKey(1L));
            assertTrue(Long2Int.inspect(map).healthCheck().healthy());
        }
    }

    @Test
    void dynamicResizeKeepsPrimaryOverOldAndDeleteSuppressesOld() {
        try (Long2IntMap map = Long2Int.concurrent(2)) {
            assertTrue(map.put(10L, 10));
            assertTrue(map.put(20L, 20));
            assertTrue(map.put(30L, 30));

            assertTrue(map.put(10L, 100));
            assertEquals(100, map.getOrDefault(10L, -1));
            assertEquals(20, map.removeAndGetOld(20L, -1));
            assertEquals(-1, map.getOrDefault(20L, -1));

            map.completeResize();
            assertFalse(map.resizeInProgress());
            assertEquals(100, map.getOrDefault(10L, -1));
            assertEquals(30, map.getOrDefault(30L, -1));
            assertEquals(-1, map.getOrDefault(20L, -1));
            assertEquals(Long2Int.inspect(map).exactSize(), map.size());
            assertTrue(Long2Int.inspect(map).healthCheck().healthy());
        }
    }

    @Test
    void managedDynamicMapReclaimsRetiredTablesAfterResizeCompletion() {
        try (Long2IntMap map = Long2Int.concurrentManaged(2)) {
            for (long i = 0; i < 128; i++) {
                assertTrue(map.put(i, (int) i));
            }
            map.completeResize();
            assertFalse(map.resizeInProgress());
            for (long i = 0; i < 128; i++) {
                assertEquals((int) i, map.getOrDefault(i, -1));
            }
            Long2IntStats stats = Long2Int.inspect(map).stats();
            assertEquals(0L, stats.retiredBytes());
            assertTrue(Long2Int.inspect(map).healthCheck().healthy());
        }
    }

    @Test
    void concurrentWritesAndAddsMatchExpectedCountersAfterQuiescence() throws Exception {
        try (Long2IntMap map = Long2Int.concurrent(128)) {
            int threads = 4;
            int perThread = 512;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            Map<Long, Integer> expected = new ConcurrentHashMap<>();

            for (int t = 0; t < threads; t++) {
                int thread = t;
                executor.submit(() -> {
                    await(start);
                    long base = (long) thread * perThread;
                    for (int i = 0; i < perThread; i++) {
                        long key = base + i;
                        map.put(key, i);
                        map.addIfPresent(key, 1);
                        expected.put(key, i + 1);
                    }
                });
            }

            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
            map.completeResize();

            for (Map.Entry<Long, Integer> entry : expected.entrySet()) {
                assertEquals(entry.getValue(), map.getOrDefault(entry.getKey(), -1));
            }
            assertEquals(expected.size(), Long2Int.inspect(map).exactSize());
            assertTrue(Long2Int.inspect(map).healthCheck().healthy());
        }
    }

    @Test
    void closeMakesOperationsFailFast() {
        Long2IntAppendMap map = Long2Int.fixed(4);
        assertTrue(map.put(1L, 1));
        map.close();
        assertThrows(IllegalStateException.class, () -> map.get(1L));
    }

    @Test
    void singleThreadedLookupUsesLastValueFullLongDomainAndMissingValue() {
        long[] keys = {0L, Long.MIN_VALUE, 42L, Long.MAX_VALUE, 42L};
        int[] values = {1, 2, 3, 4, 5};
        try (Long2IntLookup lookup = Long2Int.singleThreadedBuilder(keys.length)
                .missingValue(Integer.MIN_VALUE)
                .buildLookup(keys, values)) {
            assertEquals(1, lookup.get(0L));
            assertEquals(2, lookup.get(Long.MIN_VALUE));
            assertEquals(5, lookup.get(42L));
            assertEquals(4, lookup.get(Long.MAX_VALUE));
            assertEquals(Integer.MIN_VALUE, lookup.get(-1L));
            assertEquals(99, lookup.getOrDefault(-1L, 99));
            assertEquals(4L, lookup.size());
            assertEquals(4L, Long2Int.inspect(lookup).exactSize());
            assertTrue(Long2Int.inspect(lookup).healthCheck().healthy());
        }
    }

    @Test
    void singleThreadedHeapFixedSupportsPlainUpdatesAndCursorReuse() {
        try (Long2IntSingleThreadMap map = Long2Int.singleThreadedHeapFixed(8)) {
            assertEquals(Long2IntBackend.HEAP, map.backend());
            assertTrue(map.put(10L, 1));
            assertTrue(map.put(20L, 2));
            assertTrue(map.put(10L, 3));
            assertFalse(map.putIfAbsent(10L, 4));
            assertEquals(3, map.get(10L));
            assertTrue(map.compareAndSet(10L, 3, 5));
            assertTrue(map.addIfPresent(10L, 7));
            assertEquals(12, map.getAndAddIfPresent(10L, 1, -1));
            assertEquals(13, map.get(10L));
            assertEquals(-1, map.getAndAddIfPresent(99L, 1, -1));

            LongIntCursor cursor = map.cachedCursor();
            long keySum = 0L;
            int valueSum = 0;
            while (cursor.next()) {
                keySum += cursor.key();
                valueSum += cursor.value();
            }
            assertEquals(30L, keySum);
            assertEquals(15, valueSum);
            assertSame(cursor, map.cachedCursor());
            assertTrue(Long2Int.inspect(map).healthCheck().healthy());
        }
    }

    @Test
    void singleThreadedDirectFixedUsesOffHeapBackend() {
        try (Long2IntSingleThreadMap map = Long2Int.singleThreadedDirectFixed(8)) {
            assertEquals(Long2IntBackend.DIRECT, map.backend());
            assertTrue(map.backendBytes() > 0L);
            assertTrue(map.put(Long.MIN_VALUE, 11));
            assertTrue(map.put(Long.MAX_VALUE, 12));
            assertEquals(11, map.get(Long.MIN_VALUE));
            assertEquals(12, map.get(Long.MAX_VALUE));
            assertTrue(Long2Int.inspect(map).stats().offHeapBytes() > 0L);
            assertTrue(Long2Int.inspect(map).healthCheck().healthy());
        }
    }

    @Test
    void singleThreadedMutableRemovesMissingValuePayloadAndReusesDeletedSlot() {
        try (Long2IntMap map = Long2Int.singleThreadedBuilder(8)
                .missingValue(Integer.MIN_VALUE)
                .buildMutable()) {
            assertTrue(map.put(1L, Integer.MIN_VALUE));
            assertTrue(map.put(2L, 20));
            assertTrue(map.remove(1L));
            assertFalse(map.containsKey(1L));
            assertEquals(20, map.removeAndGetOld(2L, -1));
            assertTrue(map.put(3L, 30));
            assertEquals(30, map.get(3L));
            assertFalse(map.resizeInProgress());
            map.completeResize();
            assertTrue(Long2Int.inspect(map).healthCheck().healthy());
        }
    }

    @Test
    void singleThreadedDirectMutableUsesOffHeapBackendAndDeletes() {
        try (Long2IntMap map = Long2Int.singleThreadedBuilder(8)
                .backend(Long2IntBackend.DIRECT)
                .missingValue(Integer.MIN_VALUE)
                .buildMutable()) {
            Long2IntSingleThreadMap single = (Long2IntSingleThreadMap) map;
            assertEquals(Long2IntBackend.DIRECT, single.backend());
            assertTrue(single.backendBytes() > 0L);
            assertTrue(map.put(Long.MIN_VALUE, Integer.MIN_VALUE));
            assertTrue(map.put(Long.MAX_VALUE, 99));
            assertTrue(map.remove(Long.MIN_VALUE));
            assertFalse(map.containsKey(Long.MIN_VALUE));
            assertEquals(99, map.removeAndGetOld(Long.MAX_VALUE, -1));
            assertTrue(map.put(7L, 70));
            assertEquals(70, map.get(7L));
            assertTrue(Long2Int.inspect(map).stats().offHeapBytes() > 0L);
            assertTrue(Long2Int.inspect(map).healthCheck().healthy());
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

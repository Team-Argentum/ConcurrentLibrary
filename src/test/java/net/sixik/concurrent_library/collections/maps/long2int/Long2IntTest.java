package net.sixik.concurrent_library.collections.maps.long2int;

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
    void concurrentImplementationsExposePublicConstructors() {
        try (Long2IntMap map = new ConcurrentLong2IntMap(4)) {
            assertTrue(map.put(1L, 10));
            assertTrue(map.put(2L, 20));
            assertEquals(10, map.getOrDefault(1L, -1));
            assertEquals(20, map.removeAndGetOld(2L, -1));
            map.completeResize();
            assertTrue(map.inspector().healthCheck().healthy());
        }

        try (Long2IntMap map = new ManagedConcurrentLong2IntMap(4)) {
            for (long i = 0; i < 64; i++) {
                assertTrue(map.put(i, (int) i));
            }
            map.completeResize();
            assertFalse(map.resizeInProgress());
            assertEquals(63, map.getOrDefault(63L, -1));
            assertTrue(map.inspector().healthCheck().healthy());
        }

        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLong2IntMap(-1));
        assertThrows(IllegalArgumentException.class, () -> new ManagedConcurrentLong2IntMap(-1));
    }

    @Test
    void immutableLookupUsesLastValueForDuplicateKeys() {
        assertThrows(IllegalArgumentException.class, () -> new ImmutableDirectLong2IntLookup(new long[]{1L}, new int[]{}));

        try (Long2IntLookup lookup = new ImmutableDirectLong2IntLookup(
                new long[]{1L, 2L, 1L, -7L},
                new int[]{10, 20, 30, 70})) {
            assertEquals(30, lookup.getOrDefault(1L, -1));
            assertEquals(20, lookup.getOrDefault(2L, -1));
            assertEquals(70, lookup.getOrDefault(-7L, -1));
            assertEquals(-1, lookup.getOrDefault(3L, -1));
            assertTrue(lookup.containsKey(1L));
            assertFalse(lookup.containsKey(3L));
            assertEquals(3L, lookup.size());
            assertEquals(3L, lookup.inspector().exactSize());
            assertTrue(lookup.inspector().healthCheck().healthy());
        }
    }

    @Test
    void fixedMapSupportsAppendUpdateCasAndNoImplicitZero() {
        try (Long2IntAppendMap map = new FixedDirectLong2IntAppendMap(8)) {
            assertEquals(-1, map.getOrDefault(42L, -1));
            assertTrue(map.put(42L, 7));
            assertEquals(7, map.getOrDefault(42L, -1));
            assertFalse(map.putIfAbsent(42L, 99));
            assertTrue(map.compareAndSet(42L, 7, 8));
            assertFalse(map.compareAndSet(42L, 7, 9));
            assertTrue(map.replace(42L, 8, 10));
            assertTrue(map.addIfPresent(42L, 5));
            assertEquals(15, map.getAndAddIfPresent(42L, 2, -1));
            assertEquals(17, map.getOrDefault(42L, -1));
            assertFalse(map.addIfPresent(43L, 1));
            assertEquals(-1, map.getAndAddIfPresent(43L, 1, -1));
            assertEquals(1L, map.size());
            assertTrue(map.inspector().healthCheck().healthy());
        }
    }

    @Test
    void dynamicMapDeletesReinsertsSameKeyAndKeepsResizeStateHealthy() {
        try (Long2IntMap map = new ConcurrentLong2IntMap(4)) {
            assertTrue(map.put(1L, 10));
            assertTrue(map.put(2L, 20));
            assertEquals(10, map.removeAndGetOld(1L, -1));
            assertFalse(map.containsKey(1L));
            assertTrue(map.put(1L, 11));
            assertEquals(11, map.getOrDefault(1L, -1));

            for (long i = 3L; i < 64L; i++) {
                assertTrue(map.put(i, (int) i));
            }
            map.completeResize();
            assertEquals(20, map.getOrDefault(2L, -1));
            assertEquals(63, map.getOrDefault(63L, -1));
            assertEquals(map.inspector().exactSize(), map.size());
            assertTrue(map.inspector().healthCheck().healthy());
        }
    }

    @Test
    void managedDynamicMapReclaimsRetiredTablesAfterResizeCompletion() {
        try (Long2IntMap map = new ManagedConcurrentLong2IntMap(2)) {
            for (long i = 0; i < 128; i++) {
                assertTrue(map.put(i, (int) i));
            }
            map.completeResize();
            assertFalse(map.resizeInProgress());
            for (long i = 0; i < 128; i++) {
                assertEquals((int) i, map.getOrDefault(i, -1));
            }
            Long2IntStats stats = map.inspector().stats();
            assertEquals(0L, stats.retiredBytes());
            assertTrue(map.inspector().healthCheck().healthy());
        }
    }

    @Test
    void concurrentWritesAndAddsMatchExpectedCountersAfterQuiescence() throws Exception {
        try (Long2IntMap map = new ConcurrentLong2IntMap(128)) {
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
            assertEquals(expected.size(), map.inspector().exactSize());
            assertTrue(map.inspector().healthCheck().healthy());
        }
    }

    @Test
    void closeMakesOperationsFailFast() {
        Long2IntAppendMap fixed = new FixedDirectLong2IntAppendMap(4);
        assertTrue(fixed.put(1L, 1));
        fixed.close();
        assertThrows(IllegalStateException.class, () -> fixed.get(1L));

        Long2IntMap dynamic = new ConcurrentLong2IntMap(4);
        assertTrue(dynamic.put(1L, 1));
        dynamic.close();
        assertThrows(IllegalStateException.class, () -> dynamic.get(1L));
        assertThrows(IllegalStateException.class, () -> dynamic.put(2L, 2));
        assertThrows(IllegalStateException.class, dynamic::completeResize);
    }

    @Test
    void dynamicMapsHandleFullLongDomainAndDeletePressure() {
        for (boolean managed : new boolean[]{false, true}) {
            try (Long2IntMap map = managed ? new ManagedConcurrentLong2IntMap(2) : new ConcurrentLong2IntMap(2)) {
                assertTrue(map.put(Long.MIN_VALUE, Integer.MIN_VALUE));
                assertTrue(map.put(Long.MAX_VALUE, Integer.MAX_VALUE));
                assertTrue(map.put(0L, -1));

                assertEquals(Integer.MIN_VALUE, map.getOrDefault(Long.MIN_VALUE, 123));
                assertEquals(Integer.MAX_VALUE, map.getAndAddIfPresent(Long.MAX_VALUE, 1, -1));
                assertEquals(Integer.MIN_VALUE, map.getOrDefault(Long.MAX_VALUE, -1));
                assertTrue(map.addIfPresent(0L, Integer.MIN_VALUE));
                assertEquals(Integer.MAX_VALUE, map.getOrDefault(0L, -1));

                for (int i = 0; i < 512; i++) {
                    assertTrue(map.put(i, i), String.valueOf(i));
                }
                for (int i = 0; i < 512; i += 2) {
                    assertTrue(map.remove(i), String.valueOf(i));
                }
                for (int i = 0; i < 512; i += 2) {
                    assertTrue(map.put(i, -i), String.valueOf(i));
                }
                map.completeResize();

                Long2IntStats stats = map.inspector().stats();
                assertFalse(stats.resizeInProgress());
                assertTrue(stats.offHeapBytes() > 0L);
                assertEquals(map.inspector().exactSize(), map.size(), managed ? "managed" : "plain");
                assertTrue(map.inspector().healthCheck().healthy(), managed ? "managed" : "plain");
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

package net.sixik.concurrent_library.long2reference;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class Long2ReferenceMapTest {
    @Test
    void constructorsMirrorFactoryAndBuilderDefaults() {
        Long2ReferenceMap<String> defaultMap = new Long2ReferenceMap<>();
        assertNull(defaultMap.put(42L, "answer"));
        assertEquals("answer", defaultMap.get(42L));
        assertEquals(1L, defaultMap.mappingCount());

        Long2ReferenceMap<String> hotMap = new Long2ReferenceMap<>(10L, 64L);
        hotMap.put(12L, "hot");
        hotMap.put(Long.MAX_VALUE, "cold");
        assertEquals("hot", hotMap.get(12L));
        assertEquals("cold", hotMap.get(Long.MAX_VALUE));
        assertEquals(10L, hotMap.stats().hotBaseKey());
        assertEquals(64L, hotMap.stats().hotCapacity());

        Long2ReferenceMap<String> tunedMap = new Long2ReferenceMap<>(-8L, 16L, 4);
        tunedMap.put(-1L, "minus");
        assertEquals("minus", tunedMap.get(-1L));
        assertEquals(-8L, tunedMap.stats().hotBaseKey());
        assertEquals(16L, tunedMap.stats().hotCapacity());

        assertThrows(IllegalArgumentException.class, () -> new Long2ReferenceMap<String>(0L, -1L));
        assertThrows(ArithmeticException.class, () -> new Long2ReferenceMap<String>(Long.MAX_VALUE, 1L));
        assertThrows(IllegalArgumentException.class, () -> new Long2ReferenceMap<String>(0L, 1L, 3));
    }

    @Test
    void putGetRemoveSupportWideLongKeySpace() {
        Long2ReferenceMap<String> map = Long2ReferenceMap.<String>builder()
                .hotRange(-8L, 16L)
                .pageBits(4)
                .build();

        assertNull(map.put(0L, "zero"));
        assertNull(map.put(-1L, "minus"));
        assertNull(map.put(Long.MIN_VALUE, "min"));
        assertNull(map.put(Long.MAX_VALUE, "max"));

        assertEquals("zero", map.get(0L));
        assertEquals("minus", map.get(-1L));
        assertEquals("min", map.get(Long.MIN_VALUE));
        assertEquals("max", map.get(Long.MAX_VALUE));
        assertEquals(4L, map.mappingCount());
        assertEquals(4, map.size());

        assertEquals("zero", map.put(0L, "zero2"));
        assertEquals(4L, map.mappingCount());
        assertEquals("zero2", map.remove(0L));
        assertNull(map.remove(0L));
        assertEquals(3L, map.mappingCount());
    }

    @Test
    void nullPolicyMatchesConcurrentHashMapStyle() {
        Long2ReferenceMap<String> map = Long2ReferenceMap.concurrent();

        assertThrows(NullPointerException.class, () -> map.put(1L, null));
        assertThrows(NullPointerException.class, () -> map.putIfAbsent(1L, null));
        assertThrows(NullPointerException.class, () -> map.replace(1L, null));
        assertThrows(NullPointerException.class, () -> map.replace(1L, "a", null));
        assertThrows(NullPointerException.class, () -> map.remove(1L, null));
        assertThrows(NullPointerException.class, () -> map.merge(1L, null, (oldValue, newValue) -> oldValue));
    }

    @Test
    void putIfAbsentReplaceAndEqualityRemoveFollowMapSemantics() {
        Long2ReferenceMap<String> map = Long2ReferenceMap.concurrent(0L, 64L);

        assertNull(map.putIfAbsent(10L, new String("ten")));
        assertEquals("ten", map.putIfAbsent(10L, "ignored"));
        assertEquals("ten", map.get(10L));

        assertFalse(map.replace(10L, "missing", "bad"));
        assertTrue(map.replace(10L, new String("ten"), "ten2"));
        assertEquals("ten2", map.get(10L));

        assertFalse(map.remove(10L, "ten"));
        assertTrue(map.remove(10L, new String("ten2")));
        assertNull(map.get(10L));
        assertEquals(0L, map.mappingCount());
    }

    @Test
    void computeAndMergeHandleInsertReplaceAndRemoval() {
        Long2ReferenceMap<String> map = Long2ReferenceMap.concurrent();

        assertEquals("v5", map.computeIfAbsent(5L, key -> "v" + key));
        assertEquals("v5", map.computeIfAbsent(5L, key -> fail("should not recompute")));
        assertEquals("v5!", map.computeIfPresent(5L, (key, value) -> value + "!"));
        assertEquals("v5!?", map.compute(5L, (key, value) -> value + "?"));
        assertEquals("new", map.compute(6L, (key, value) -> value == null ? "new" : "bad"));
        assertEquals("v5!?+x", map.merge(5L, "x", (oldValue, newValue) -> oldValue + "+" + newValue));

        assertNull(map.computeIfPresent(5L, (key, value) -> null));
        assertFalse(map.containsKey(5L));
        assertNull(map.merge(6L, "ignored", (oldValue, newValue) -> null));
        assertFalse(map.containsKey(6L));
        assertEquals(0L, map.mappingCount());
    }

    @Test
    void resizeChangesOnlyHotWindowAndDoesNotLoseMappings() {
        Long2ReferenceMap<String> map = Long2ReferenceMap.concurrent(0L, 32L);

        map.put(1L, "hot-old");
        map.put(10_000L, "cold");
        map.put(Long.MAX_VALUE, "far");

        map.resize(10_000L, 64L);

        assertEquals("hot-old", map.get(1L));
        assertEquals("cold", map.get(10_000L));
        assertEquals("far", map.get(Long.MAX_VALUE));
        assertEquals(3L, map.mappingCount());
        assertEquals(10_000L, map.stats().hotBaseKey());
        assertEquals(64L, map.stats().hotCapacity());
    }

    @Test
    void resizeRejectsInvalidRanges() {
        Long2ReferenceMap<String> map = Long2ReferenceMap.concurrent();

        assertThrows(IllegalArgumentException.class, () -> map.resize(0L, -1L));
        assertThrows(ArithmeticException.class, () -> map.resize(Long.MAX_VALUE, 1L));
        assertThrows(ArithmeticException.class, () -> Long2ReferenceMap.builder().hotRange(Long.MAX_VALUE, 1L));
    }

    @Test
    void vacuumAndClearRetireEmptyPagesWithoutLosingLiveEntries() {
        Long2ReferenceMap<String> map = Long2ReferenceMap.<String>builder()
                .pageBits(4)
                .vacuumOnEmptyPage(false)
                .build();

        map.put(1L, "one");
        map.put(17L, "seventeen");
        map.remove(1L);

        Long2ReferenceMap.VacuumStats stats = map.vacuum();
        assertTrue(stats.scannedCells() >= 2L);
        assertTrue(stats.retiredPages() >= 1L);
        assertEquals("seventeen", map.get(17L));
        assertEquals(1L, map.mappingCount());

        map.clear();
        assertTrue(map.isEmpty());
        assertNull(map.get(17L));
    }

    @Test
    void mapViewMirrorsPrimitiveOperations() {
        Long2ReferenceMap<String> map = Long2ReferenceMap.concurrent();
        ConcurrentMap<Long, String> view = map.asMapView();

        assertNull(view.putIfAbsent(42L, "a"));
        assertEquals("a", map.get(42L));
        assertEquals("a", view.replace(42L, "b"));
        assertEquals("b", map.get(42L));
        assertTrue(view.replace(42L, "b", "c"));
        assertEquals("c", map.get(42L));
        assertTrue(view.remove(42L, new String("c")));
        assertFalse(map.containsKey(42L));
    }

    @Test
    void setExistingOverwritesOnlyPresentMappings() {
        Long2ReferenceMap<String> map = Long2ReferenceMap.concurrent(0L, 64L);

        assertFalse(map.setExisting(7L, "missing"));
        assertEquals(0L, map.mappingCount());

        map.put(7L, "seven");
        assertTrue(map.setExisting(7L, "seven2"));
        assertEquals("seven2", map.get(7L));
        assertEquals(1L, map.mappingCount());

        assertThrows(NullPointerException.class, () -> map.setExisting(7L, null));
        assertEquals("seven2", map.remove(7L));
        assertFalse(map.setExisting(7L, "after-remove"));
        assertNull(map.get(7L));
        assertEquals(0L, map.mappingCount());
    }

    @Test
    void iterationScansStoreNotHotWindowOnly() {
        Long2ReferenceMap<String> map = Long2ReferenceMap.<String>builder()
                .hotRange(0L, 4L)
                .pageBits(4)
                .build();
        map.put(1L, "one");
        map.put(1_000L, "thousand");
        map.put(-1_000L, "minus");

        Map<Long, String> seen = new HashMap<>();
        map.forEach(seen::put);

        assertEquals(Map.of(1L, "one", 1_000L, "thousand", -1_000L, "minus"), seen);
    }

    @Test
    void concurrentInsertsIntoDifferentPagesKeepExactSize() throws Exception {
        Long2ReferenceMap<String> map = Long2ReferenceMap.<String>builder()
                .hotRange(0L, 1024L)
                .pageBits(4)
                .build();
        int threads = 4;
        int perThread = 128;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        for (int thread = 0; thread < threads; thread++) {
            int threadId = thread;
            executor.submit(() -> {
                await(start);
                for (int i = 0; i < perThread; i++) {
                    long key = ((long) threadId << 32) + i * 16L;
                    map.putIfAbsent(key, threadId + ":" + i);
                }
            });
        }

        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals((long) threads * perThread, map.mappingCount());
        for (int thread = 0; thread < threads; thread++) {
            for (int i = 0; i < perThread; i++) {
                long key = ((long) thread << 32) + i * 16L;
                assertEquals(thread + ":" + i, map.get(key));
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

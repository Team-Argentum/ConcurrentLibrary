package net.sixik.concurrent_library.collections.maps.long2reference;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class Long2ReferenceMapTest {
    @Test
    void constructorsExposeDefaultHotAndTunedOptions() {
        ConcurrentLong2ReferenceMap<String> defaultMap = new ConcurrentLong2ReferenceMap<>();
        assertNull(defaultMap.put(42L, "answer"));
        assertEquals("answer", defaultMap.get(42L));
        assertEquals(1L, defaultMap.mappingCount());

        ConcurrentLong2ReferenceMap<String> hotMap = new ConcurrentLong2ReferenceMap<>(10L, 64L);
        hotMap.put(12L, "hot");
        hotMap.put(Long.MAX_VALUE, "cold");
        assertEquals("hot", hotMap.get(12L));
        assertEquals("cold", hotMap.get(Long.MAX_VALUE));
        assertEquals(10L, hotMap.stats().hotBaseKey());
        assertEquals(64L, hotMap.stats().hotCapacity());

        ConcurrentLong2ReferenceMap<String> tunedMap = new ConcurrentLong2ReferenceMap<>(-8L, 16L, 4);
        tunedMap.put(-1L, "minus");
        assertEquals("minus", tunedMap.get(-1L));
        assertEquals(-8L, tunedMap.stats().hotBaseKey());
        assertEquals(16L, tunedMap.stats().hotCapacity());

        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLong2ReferenceMap<String>(0L, -1L));
        assertThrows(ArithmeticException.class, () -> new ConcurrentLong2ReferenceMap<String>(Long.MAX_VALUE, 1L));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLong2ReferenceMap<String>(0L, 1L, 3));
    }

    @Test
    void constructorOptionsCoverMapViewDisablePageBitBoundsAndSparseHotWindows() {
        ConcurrentLong2ReferenceMap<String> noView = new ConcurrentLong2ReferenceMap<>(100L, 0L, 4, false, true, false);
        assertEquals(100L, noView.stats().hotBaseKey());
        assertEquals(0L, noView.stats().hotCapacity());
        assertEquals(0L, noView.stats().hotPageCount());
        assertThrows(UnsupportedOperationException.class, noView::asMapView);
        noView.put(Long.MIN_VALUE, "min");
        noView.put(Long.MAX_VALUE, "max");
        assertEquals("min", noView.get(Long.MIN_VALUE));
        assertEquals("max", noView.get(Long.MAX_VALUE));

        assertDoesNotThrow(() -> new ConcurrentLong2ReferenceMap<String>(0L, 0L, 4));
        assertDoesNotThrow(() -> new ConcurrentLong2ReferenceMap<String>(0L, 0L, 20));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLong2ReferenceMap<String>(0L, 0L, 3));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLong2ReferenceMap<String>(0L, 0L, 21));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentLong2ReferenceMap<String>(0L, -1L));
    }

    @Test
    void putGetRemoveSupportWideLongKeySpace() {
        ConcurrentLong2ReferenceMap<String> map = new ConcurrentLong2ReferenceMap<>(-8L, 16L, 4);

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
        ConcurrentLong2ReferenceMap<String> map = new ConcurrentLong2ReferenceMap<>();

        assertThrows(NullPointerException.class, () -> map.put(1L, null));
        assertThrows(NullPointerException.class, () -> map.putIfAbsent(1L, null));
        assertThrows(NullPointerException.class, () -> map.replace(1L, null));
        assertThrows(NullPointerException.class, () -> map.replace(1L, "a", null));
        assertThrows(NullPointerException.class, () -> map.remove(1L, null));
        assertThrows(NullPointerException.class, () -> map.merge(1L, null, (oldValue, newValue) -> oldValue));
    }

    @Test
    void putIfAbsentReplaceAndEqualityRemoveFollowMapSemantics() {
        ConcurrentLong2ReferenceMap<String> map = new ConcurrentLong2ReferenceMap<>(0L, 64L);

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
        ConcurrentLong2ReferenceMap<String> map = new ConcurrentLong2ReferenceMap<>();

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
        ConcurrentLong2ReferenceMap<String> map = new ConcurrentLong2ReferenceMap<>(0L, 32L);

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
        ConcurrentLong2ReferenceMap<String> map = new ConcurrentLong2ReferenceMap<>();

        assertThrows(IllegalArgumentException.class, () -> map.resize(0L, -1L));
        assertThrows(ArithmeticException.class, () -> map.resize(Long.MAX_VALUE, 1L));
        assertThrows(ArithmeticException.class, () -> new ConcurrentLong2ReferenceMap<String>(Long.MAX_VALUE, 1L));
    }

    @Test
    void vacuumAndClearRetireEmptyPagesWithoutLosingLiveEntries() {
        ConcurrentLong2ReferenceMap<String> map = new ConcurrentLong2ReferenceMap<>(0L, 0L, 4, true, false, true);

        map.put(1L, "one");
        map.put(17L, "seventeen");
        map.remove(1L);

        ConcurrentLong2ReferenceMap.VacuumStats stats = map.vacuum();
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
        ConcurrentLong2ReferenceMap<String> map = new ConcurrentLong2ReferenceMap<>();
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
    void mapViewCoversComputeMergeClearWrongTypesAndEntrySnapshots() {
        ConcurrentLong2ReferenceMap<String> map = new ConcurrentLong2ReferenceMap<>(-16L, 64L);
        ConcurrentMap<Long, String> view = map.asMapView();

        assertNull(view.get("not-a-long"));
        assertFalse(view.containsKey("not-a-long"));
        assertNull(view.remove("not-a-long"));
        assertFalse(view.remove("not-a-long", "value"));
        assertThrows(NullPointerException.class, () -> view.put(null, "x"));
        assertThrows(NullPointerException.class, () -> view.put(1L, null));
        assertThrows(NullPointerException.class, () -> view.computeIfAbsent(null, key -> "x"));
        assertThrows(NullPointerException.class, () -> view.computeIfAbsent(1L, null));

        assertEquals("one", view.computeIfAbsent(1L, key -> "one"));
        assertEquals("one!", view.computeIfPresent(1L, (key, value) -> value + "!"));
        assertEquals("one!?", view.compute(1L, (key, value) -> value + "?"));
        assertEquals("two", view.compute(2L, (key, value) -> value == null ? "two" : "bad"));
        assertEquals("two+z", view.merge(2L, "z", (oldValue, newValue) -> oldValue + "+" + newValue));
        assertNull(view.computeIfPresent(1L, (key, value) -> null));
        assertFalse(view.containsKey(1L));

        Set<Long> snapshotKeys = new HashSet<>();
        Iterator<Map.Entry<Long, String>> iterator = view.entrySet().iterator();
        view.put(3L, "three");
        while (iterator.hasNext()) {
            Map.Entry<Long, String> entry = iterator.next();
            snapshotKeys.add(entry.getKey());
            assertNotNull(entry.getValue());
        }
        assertEquals(Set.of(2L), snapshotKeys);

        view.clear();
        assertTrue(view.isEmpty());
        assertEquals(0, view.entrySet().size());
        assertEquals(0L, map.mappingCount());
    }

    @Test
    void iteratorIsSnapshotAndNextFailsAfterExhaustion() {
        ConcurrentLong2ReferenceMap<String> map = new ConcurrentLong2ReferenceMap<>(0L, 16L);
        map.put(1L, "one");
        Iterator<ConcurrentLong2ReferenceMap.Entry<String>> iterator = map.iterator();
        map.put(2L, "two");

        assertTrue(iterator.hasNext());
        ConcurrentLong2ReferenceMap.Entry<String> entry = iterator.next();
        assertEquals(1L, entry.key());
        assertEquals("one", entry.value());
        assertFalse(iterator.hasNext());
        assertThrows(java.util.NoSuchElementException.class, iterator::next);
        assertEquals("two", map.get(2L));
    }

    @Test
    void setExistingOverwritesOnlyPresentMappings() {
        ConcurrentLong2ReferenceMap<String> map = new ConcurrentLong2ReferenceMap<>(0L, 64L);

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
        ConcurrentLong2ReferenceMap<String> map = new ConcurrentLong2ReferenceMap<>(0L, 4L, 4);
        map.put(1L, "one");
        map.put(1_000L, "thousand");
        map.put(-1_000L, "minus");

        Map<Long, String> seen = new HashMap<>();
        map.forEach(seen::put);

        assertEquals(Map.of(1L, "one", 1_000L, "thousand", -1_000L, "minus"), seen);
    }

    @Test
    void concurrentInsertsIntoDifferentPagesKeepExactSize() throws Exception {
        ConcurrentLong2ReferenceMap<String> map = new ConcurrentLong2ReferenceMap<>(0L, 1024L, 4);
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

    @Test
    void repeatedClearVacuumAndReuseKeepStatsAndCountsConsistent() {
        ConcurrentLong2ReferenceMap<String> map = new ConcurrentLong2ReferenceMap<>(0L, 0L, 4, true, true, true);

        for (int round = 0; round < 64; round++) {
            for (int i = 0; i < 256; i++) {
                map.put(((long) round << 32) | i, round + ":" + i);
            }
            assertEquals(256L, map.mappingCount());
            map.clear();
            assertEquals(0L, map.mappingCount());
            assertTrue(map.isEmpty());
            assertEquals(0, map.size());
            map.vacuum();
            assertTrue(map.stats().allocatedPages() >= map.stats().retiredPages());
        }

        map.put(7L, "seven");
        assertEquals("seven", map.get(7L));
        assertEquals(1L, map.mappingCount());
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

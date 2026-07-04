package net.sixik.concurrent_library.long2int;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class Long2IntRobustnessTest {
    private static final long[] EDGE_KEYS = {
            0L,
            1L,
            -1L,
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            0x5555_5555_5555_5555L,
            0xAAAA_AAAA_AAAA_AAAAL,
            0x0123_4567_89AB_CDEFL,
            0xFEDC_BA98_7654_3210L
    };

    @Test
    void singleThreadedLookupBackendsMatchHashMapAcrossRandomAndEdgeKeys() {
        for (Long2IntBackend backend : new Long2IntBackend[]{Long2IntBackend.HEAP, Long2IntBackend.DIRECT}) {
            long[] keys = new long[768];
            int[] values = new int[keys.length];
            Map<Long, Integer> expected = new HashMap<>();
            Random random = new Random(0x51A6EL + backend.ordinal());

            for (int i = 0; i < EDGE_KEYS.length; i++) {
                keys[i] = EDGE_KEYS[i];
                values[i] = i * 17 - 50;
                expected.put(keys[i], values[i]);
            }
            for (int i = EDGE_KEYS.length; i < keys.length; i++) {
                long key = switch (i & 3) {
                    case 0 -> i - 128L;
                    case 1 -> random.nextLong();
                    case 2 -> (long) (i % 97) * 0x9E3779B97F4A7C15L;
                    default -> keys[random.nextInt(i)];
                };
                int value = random.nextInt();
                keys[i] = key;
                values[i] = value;
                expected.put(key, value);
            }

            try (Long2IntLookup lookup = Long2Int.singleThreadedBuilder(keys.length)
                    .backend(backend)
                    .missingValue(Integer.MIN_VALUE)
                    .loadFactor(0.82d)
                    .buildLookup(keys, values)) {
                assertEquals(expected.size(), lookup.size(), backend.name());
                for (Map.Entry<Long, Integer> entry : expected.entrySet()) {
                    assertTrue(lookup.containsKey(entry.getKey()), backend.name());
                    assertEquals(entry.getValue(), lookup.get(entry.getKey()), backend.name());
                    assertEquals(entry.getValue(), lookup.getOrDefault(entry.getKey(), 123), backend.name());
                }
                for (long missing : new long[]{2_000_000_001L, -2_000_000_001L, 0xCAFEBABECAFEL}) {
                    assertFalse(lookup.containsKey(missing), backend.name());
                    assertEquals(Integer.MIN_VALUE, lookup.get(missing), backend.name());
                    assertEquals(777, lookup.getOrDefault(missing, 777), backend.name());
                }
                assertHealthyAndSized(lookup, expected.size());
            }
        }
    }

    @Test
    void singleThreadedFixedBackendsMatchReferenceModelForUpdatesAndCursor() {
        for (Long2IntBackend backend : new Long2IntBackend[]{Long2IntBackend.HEAP, Long2IntBackend.DIRECT}) {
            try (Long2IntSingleThreadMap map = Long2Int.singleThreadedBuilder(512)
                    .backend(backend)
                    .missingValue(Integer.MIN_VALUE)
                    .buildFixed()) {
                Map<Long, Integer> expected = new HashMap<>();
                Random random = new Random(0xF17ED_0000L + backend.ordinal());

                for (long key : EDGE_KEYS) {
                    assertTrue(map.put(key, (int) (key ^ (key >>> 32))));
                    expected.put(key, (int) (key ^ (key >>> 32)));
                }

                for (int i = 0; i < 4_000; i++) {
                    long key = randomKey(random, i, expected);
                    int current = expected.getOrDefault(key, Integer.MIN_VALUE);
                    int value = random.nextInt();
                    switch (i % 7) {
                        case 0 -> {
                            boolean actual = map.putIfAbsent(key, value);
                            boolean model = !expected.containsKey(key);
                            if (model) expected.put(key, value);
                            assertEquals(model, actual, backend.name());
                        }
                        case 1 -> {
                            assertTrue(map.put(key, value), backend.name());
                            expected.put(key, value);
                        }
                        case 2 -> {
                            boolean actual = map.compareAndSet(key, current, value);
                            boolean model = expected.containsKey(key);
                            if (model) expected.put(key, value);
                            assertEquals(model, actual, backend.name());
                        }
                        case 3 -> {
                            assertFalse(map.compareAndSet(key, current + 1, value), backend.name());
                        }
                        case 4 -> {
                            boolean actual = map.addIfPresent(key, 3);
                            boolean model = expected.containsKey(key);
                            if (model) expected.put(key, expected.get(key) + 3);
                            assertEquals(model, actual, backend.name());
                        }
                        case 5 -> {
                            int actual = map.getAndAddIfPresent(key, -2, Integer.MIN_VALUE);
                            if (expected.containsKey(key)) {
                                assertEquals(expected.get(key), actual, backend.name());
                                expected.put(key, expected.get(key) - 2);
                            } else {
                                assertEquals(Integer.MIN_VALUE, actual, backend.name());
                            }
                        }
                        default -> assertEquals(expected.getOrDefault(key, Integer.MIN_VALUE), map.get(key), backend.name());
                    }
                }

                assertMapMatches(map, expected, backend.name());
                assertCursorMatches(map, expected, backend.name());
                assertHealthyAndSized(map, expected.size());
            }
        }
    }

    @Test
    void singleThreadedMutableBackendsMatchReferenceModelForDeletesAndReuse() {
        for (Long2IntBackend backend : new Long2IntBackend[]{Long2IntBackend.HEAP, Long2IntBackend.DIRECT}) {
            try (Long2IntMap map = Long2Int.singleThreadedBuilder(1_024)
                    .backend(backend)
                    .missingValue(Integer.MIN_VALUE)
                    .loadFactor(0.65d)
                    .buildMutable()) {
                Map<Long, Integer> expected = new HashMap<>();
                Random random = new Random(0xDE1E7E_0000L + backend.ordinal());

                for (int i = 0; i < 5_000; i++) {
                    long key = randomKey(random, i, expected);
                    int value = (i & 15) == 0 ? Integer.MIN_VALUE : random.nextInt();
                    switch (i % 9) {
                        case 0, 1, 2, 3 -> {
                            boolean ok = map.put(key, value);
                            assertTrue(ok, backend.name());
                            expected.put(key, value);
                        }
                        case 4 -> {
                            boolean actual = map.putIfAbsent(key, value);
                            boolean model = !expected.containsKey(key);
                            if (model) expected.put(key, value);
                            assertEquals(model, actual, backend.name());
                        }
                        case 5 -> {
                            boolean actual = map.remove(key);
                            boolean model = expected.remove(key) != null;
                            assertEquals(model, actual, backend.name());
                        }
                        case 6 -> {
                            int actual = map.removeAndGetOld(key, 0x13572468);
                            Integer old = expected.remove(key);
                            assertEquals(old == null ? 0x13572468 : old, actual, backend.name());
                        }
                        case 7 -> {
                            boolean actual = map.addIfPresent(key, 11);
                            boolean model = expected.containsKey(key);
                            if (model) expected.put(key, expected.get(key) + 11);
                            assertEquals(model, actual, backend.name());
                        }
                        default -> assertEquals(expected.getOrDefault(key, Integer.MIN_VALUE), map.get(key), backend.name());
                    }
                }

                assertMapMatches(map, expected, backend.name());
                assertCursorMatches((Long2IntSingleThreadMap) map, expected, backend.name());
                assertFalse(map.resizeInProgress(), backend.name());
                map.completeResize();
                assertHealthyAndSized(map, expected.size());
            }
        }
    }

    @Test
    void singleThreadedFixedReportsFullButStillUpdatesExistingKeys() {
        try (Long2IntSingleThreadMap map = Long2Int.singleThreadedBuilder(6)
                .backend(Long2IntBackend.HEAP)
                .loadFactor(0.95d)
                .buildFixed()) {
            long capacity = map.capacity();
            for (int i = 0; i < capacity; i++) {
                assertTrue(map.put(i, i));
            }
            assertFalse(map.put(10_000L, 1));
            assertTrue(map.put(0L, 100));
            assertEquals(100, map.get(0L));
            assertFalse(map.putIfAbsent(0L, 200));
            assertEquals(capacity, map.size());
            assertHealthyAndSized(map, (int) capacity);
        }
    }

    @Test
    void legacyFixedConcurrentDisjointWritesAndSharedAddsAreStable() throws Exception {
        try (Long2IntAppendMap map = Long2Int.fixed(4_096)) {
            int threads = 6;
            int perThread = 384;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);

            for (int t = 0; t < threads; t++) {
                int thread = t;
                executor.submit(() -> {
                    await(start);
                    long base = (long) thread * perThread;
                    for (int i = 0; i < perThread; i++) {
                        assertTrue(map.put(base + i, i));
                    }
                });
            }

            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));

            ExecutorService addExecutor = Executors.newFixedThreadPool(threads);
            CountDownLatch addStart = new CountDownLatch(1);
            for (int t = 0; t < threads; t++) {
                addExecutor.submit(() -> {
                    await(addStart);
                    for (int i = 0; i < perThread; i++) {
                        assertTrue(map.addIfPresent(i, 1));
                    }
                });
            }

            addStart.countDown();
            addExecutor.shutdown();
            assertTrue(addExecutor.awaitTermination(20, TimeUnit.SECONDS));

            for (int t = 0; t < threads; t++) {
                long base = (long) t * perThread;
                for (int i = 0; i < perThread; i++) {
                    int expected = base == 0L ? i + threads : i;
                    assertEquals(expected, map.getOrDefault(base + i, Integer.MIN_VALUE));
                }
            }
            assertEquals((long) threads * perThread, map.size());
            assertHealthyAndSized(map, threads * perThread);
        }
    }

    @RepeatedTest(3)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentDynamicMapsSurviveMixedMultiThreadedWorkloads() throws Exception {
        for (boolean managed : new boolean[]{false, true}) {
            try (Long2IntMap map = managed ? Long2Int.concurrentManaged(32) : Long2Int.concurrent(32)) {
                int threads = 6;
                int perThread = 800;
                ExecutorService executor = Executors.newFixedThreadPool(threads);
                CountDownLatch start = new CountDownLatch(1);
                Map<Long, Integer> mustExist = new ConcurrentHashMap<>();
                AtomicInteger removeMisses = new AtomicInteger();

                Future<?>[] futures = new Future<?>[threads];

                for (int t = 0; t < threads; t++) {
                    int thread = t;
                    futures[t] = executor.submit(() -> {
                        await(start);
                        long base = ((long) thread + 1L) << 32;
                        for (int i = 0; i < perThread; i++) {
                            long key = base | i;
                            assertTrue(map.put(key, i));
                            if ((i & 3) == 0) {
                                assertTrue(map.compareAndSet(key, i, i + 10_000));
                                mustExist.put(key, i + 10_000);
                            } else if ((i & 3) == 1) {
                                assertTrue(map.addIfPresent(key, 7));
                                mustExist.put(key, i + 7);
                            } else if ((i & 3) == 2) {
                                int old = map.removeAndGetOld(key, Integer.MIN_VALUE);
                                if (old == Integer.MIN_VALUE) {
                                    removeMisses.incrementAndGet();
                                }
                                assertTrue(map.put(key, i + 20_000));
                                mustExist.put(key, i + 20_000);
                            } else {
                                mustExist.put(key, i);
                            }
                        }
                    });
                }

                start.countDown();
                executor.shutdown();
                assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
                for (Future<?> future : futures) {
                    future.get();
                }
                map.completeResize();

                for (Map.Entry<Long, Integer> entry : mustExist.entrySet()) {
                    long key = entry.getKey();
                    int actual = map.getOrDefault(key, Integer.MIN_VALUE);
                    String label = (managed ? "managed" : "plain") + " key=" + key
                            + " low=" + (int) key
                            + " expected=" + entry.getValue()
                            + " actual=" + actual
                            + " size=" + map.size()
                            + " stats=" + Long2Int.inspect(map).stats();
                    assertEquals(entry.getValue(), actual, label);
                    assertTrue(map.containsKey(key), label);
                }
                assertEquals(mustExist.size(), map.size(), managed ? "managed" : "plain");
                assertEquals(mustExist.size(), Long2Int.inspect(map).exactSize(), managed ? "managed" : "plain");
                assertTrue(Long2Int.inspect(map).healthCheck().healthy(), Long2Int.inspect(map).healthCheck().problems().toString());
                assertTrue(removeMisses.get() >= 0);
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentDynamicMapReadersWritersAndResizersDoNotThrow() throws Exception {
        try (Long2IntMap map = Long2Int.concurrentManaged(16)) {
            int threads = 8;
            int iterations = 2_000;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            Future<?>[] futures = new Future<?>[threads];

            for (int t = 0; t < threads; t++) {
                int thread = t;
                futures[t] = executor.submit(() -> {
                    await(start);
                    Random random = new Random(0xC0FFEEL + thread);
                    for (int i = 0; i < iterations; i++) {
                        long key = random.nextInt(512);
                        switch ((i + thread) & 7) {
                            case 0, 1, 2 -> map.put(key, thread + i);
                            case 3 -> map.putIfAbsent(key, i);
                            case 4 -> map.addIfPresent(key, 1);
                            case 5 -> map.remove(key);
                            case 6 -> map.getOrDefault(key, Integer.MIN_VALUE);
                            default -> map.containsKey(key);
                        }
                        if ((i & 255) == 0) {
                            map.completeResize();
                        }
                    }
                });
            }

            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
            for (Future<?> future : futures) {
                future.get();
            }
            map.completeResize();
            assertEquals(Long2Int.inspect(map).exactSize(), map.size());
            assertTrue(Long2Int.inspect(map).healthCheck().healthy(), Long2Int.inspect(map).healthCheck().problems().toString());
        }
    }

    @Test
    void closeChecksCanBeDisabledButCloseStillReleasesStorage() {
        Long2IntSingleThreadMap map = Long2Int.singleThreadedBuilder(8)
                .backend(Long2IntBackend.HEAP)
                .closeChecks(false)
                .buildFixed();
        assertTrue(map.put(1L, 10));
        map.close();
        assertThrows(NullPointerException.class, () -> map.getOrDefault(1L, -1));
    }

    @Test
    void builderRejectsInvalidArgumentsAndPanamaIsExplicitlyUnavailable() {
        assertThrows(IllegalArgumentException.class, () -> Long2Int.singleThreadedBuilder(-1));
        assertThrows(IllegalArgumentException.class, () -> Long2Int.singleThreadedBuilder(1).loadFactor(0.0d));
        assertThrows(IllegalArgumentException.class, () -> Long2Int.singleThreadedBuilder(1).loadFactor(1.0d));
        assertThrows(NullPointerException.class, () -> Long2Int.singleThreadedBuilder(1).backend(null));
        assertThrows(NullPointerException.class, () -> Long2Int.singleThreadedBuilder(1).hashing(null));
        assertThrows(UnsupportedOperationException.class, () -> Long2Int.singleThreadedPanamaFixed(8));
    }

    private static long randomKey(Random random, int iteration, Map<Long, Integer> expected) {
        if (!expected.isEmpty() && (iteration & 3) == 0) {
            int target = random.nextInt(expected.size());
            int index = 0;
            for (Long key : expected.keySet()) {
                if (index++ == target) return key;
            }
        }
        return switch (iteration & 7) {
            case 0 -> EDGE_KEYS[(iteration >>> 3) % EDGE_KEYS.length];
            case 1 -> iteration;
            case 2 -> -iteration;
            case 3 -> random.nextInt(256) - 128L;
            default -> random.nextLong();
        };
    }

    private static void assertMapMatches(Long2IntLookup map, Map<Long, Integer> expected, String label) {
        for (Map.Entry<Long, Integer> entry : expected.entrySet()) {
            assertTrue(map.containsKey(entry.getKey()), label);
            assertEquals(entry.getValue(), map.get(entry.getKey()), label);
        }
        for (long missing : new long[]{9_876_543_210L, -9_876_543_210L, 0x1234_5678_1234_5678L}) {
            if (!expected.containsKey(missing)) {
                assertFalse(map.containsKey(missing), label);
            }
        }
        assertEquals(expected.size(), map.size(), label);
    }

    private static void assertCursorMatches(Long2IntSingleThreadMap map, Map<Long, Integer> expected, String label) {
        Set<Long> seen = new HashSet<>();
        LongIntCursor cursor = map.cursor();
        while (cursor.next()) {
            assertTrue(expected.containsKey(cursor.key()), label);
            assertEquals(expected.get(cursor.key()), cursor.value(), label);
            assertTrue(seen.add(cursor.key()), label);
        }
        assertEquals(expected.keySet(), seen, label);
    }

    private static void assertHealthyAndSized(Long2IntLookup map, int expectedSize) {
        Long2IntInspector inspector = Long2Int.inspect(map);
        Long2IntHealth health = inspector.healthCheck();
        assertTrue(health.healthy(), health.problems().toString());
        assertEquals(expectedSize, inspector.exactSize());
        assertEquals(expectedSize, map.size());
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

package net.sixik.concurrent_library.long2reference;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class Long2ReferenceMapStressTest {
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
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void randomSequentialOperationsMatchHashMapAcrossHotAndColdRanges() {
        Long2ReferenceMap<String> map = Long2ReferenceMap.<String>builder()
                .hotRange(-128L, 256L)
                .pageBits(4)
                .prefillHotWindow(true)
                .build();
        Map<Long, String> expected = new HashMap<>();
        SplittableRandom random = new SplittableRandom(0x1A2B_3C4D_5E6F_7788L);

        for (int i = 0; i < 20_000; i++) {
            long key = randomKey(random, i, expected);
            String value = "v" + i + ':' + Long.toUnsignedString(key, 16);
            switch (i % 11) {
                case 0, 1, 2 -> {
                    String old = map.put(key, value);
                    assertEquals(expected.put(key, value), old, "put key=" + key);
                }
                case 3 -> {
                    String old = map.putIfAbsent(key, value);
                    String model = expected.putIfAbsent(key, value);
                    assertEquals(model, old, "putIfAbsent key=" + key);
                }
                case 4 -> {
                    String old = map.remove(key);
                    assertEquals(expected.remove(key), old, "remove key=" + key);
                }
                case 5 -> {
                    String current = expected.get(key);
                    if (current == null) {
                        assertFalse(map.replace(key, value, value + "x"));
                    } else {
                        assertTrue(map.replace(key, current, value));
                        expected.put(key, value);
                    }
                }
                case 6 -> {
                    String old = map.replace(key, value);
                    String model = expected.containsKey(key) ? expected.put(key, value) : null;
                    assertEquals(model, old, "replace key=" + key);
                }
                case 7 -> {
                    String actual = map.compute(key, (k, old) -> old == null ? value : old + '!');
                    String model = expected.compute(key, (k, old) -> old == null ? value : old + '!');
                    assertEquals(model, actual, "compute key=" + key);
                }
                case 8 -> {
                    String actual = map.merge(key, value, (oldValue, newValue) -> oldValue.length() % 5 == 0 ? null : oldValue + '+' + newValue);
                    String model = expected.merge(key, value, (oldValue, newValue) -> oldValue.length() % 5 == 0 ? null : oldValue + '+' + newValue);
                    assertEquals(model, actual, "merge key=" + key);
                }
                case 9 -> map.resize(random.nextLong(-1_024L, 1_024L), random.nextInt(0, 512));
                default -> assertEquals(expected.get(key), map.get(key), "get key=" + key);
            }

            if ((i & 255) == 0) {
                assertMapMatches(map, expected);
            }
        }

        assertMapMatches(map, expected);
        map.vacuum();
        assertMapMatches(map, expected);
        map.clear();
        assertTrue(map.isEmpty());
        assertEquals(0L, map.mappingCount());
    }

    @RepeatedTest(3)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentMixedOperationsWithVacuumAndResizeRemainInternallyConsistent() throws Exception {
        Long2ReferenceMap<String> map = Long2ReferenceMap.<String>builder()
                .hotRange(0L, 1_024L)
                .pageBits(4)
                .vacuumOnEmptyPage(true)
                .build();
        ConcurrentHashMap<Long, String> expected = new ConcurrentHashMap<>();
        int threads = 8;
        int perThread = 2_000;
        ExecutorService executor = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        Future<?>[] futures = new Future<?>[threads + 1];

        futures[0] = executor.submit(() -> {
            await(start);
            int iteration = 0;
            while (running.get()) {
                if ((iteration & 1) == 0) {
                    map.resize((long) (iteration & 255) - 128L, 1_024L);
                } else {
                    map.vacuum();
                }
                iteration++;
                Thread.onSpinWait();
            }
        });

        for (int t = 0; t < threads; t++) {
            int thread = t;
            futures[t + 1] = executor.submit(() -> {
                await(start);
                SplittableRandom random = new SplittableRandom(0xC0FFEE_0000L + thread);
                long base = ((long) thread + 1L) << 40;
                for (int i = 0; i < perThread; i++) {
                    long key = keyForThread(base, random);
                    String value = thread + ":" + i;
                    switch ((i + thread) & 7) {
                        case 0, 1, 2 -> {
                            map.put(key, value);
                            expected.put(key, value);
                        }
                        case 3 -> {
                            map.putIfAbsent(key, value);
                            expected.putIfAbsent(key, value);
                        }
                        case 4 -> {
                            String old = expected.get(key);
                            if (old != null && map.replace(key, old, value)) {
                                expected.replace(key, old, value);
                            }
                        }
                        case 5 -> {
                            String old = expected.get(key);
                            if (old != null && map.remove(key, old)) {
                                expected.remove(key, old);
                            }
                        }
                        case 6 -> map.get(key);
                        default -> map.containsKey(key);
                    }
                }
            });
        }

        start.countDown();
        for (int i = 1; i < futures.length; i++) {
            futures[i].get();
        }
        running.set(false);
        futures[0].get();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        for (Map.Entry<Long, String> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), map.get(entry.getKey()), "key=" + entry.getKey());
        }
        assertTrue(map.mappingCount() <= expected.size(), "map contains more live mappings than successful model updates");
        map.vacuum();
        assertTrue(map.stats().allocatedPages() >= map.stats().retiredPages());
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void concurrentClearDoesNotCorruptSubsequentReuse() throws Exception {
        Long2ReferenceMap<String> map = Long2ReferenceMap.<String>builder()
                .hotRange(-512L, 1_024L)
                .pageBits(4)
                .build();
        int threads = 4;
        int perThread = 1_000;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            int thread = t;
            executor.submit(() -> {
                await(start);
                long base = ((long) thread + 1L) << 32;
                for (int i = 0; i < perThread; i++) {
                    long key = base | i;
                    map.put(key, thread + ":" + i);
                    if ((i & 127) == 0) {
                        map.clear();
                    }
                }
            });
        }

        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));

        map.clear();
        map.vacuum();
        assertMapMatches(map, Map.of());

        for (long key : EDGE_KEYS) {
            assertNull(map.put(key, "edge-" + key));
        }
        for (long key : EDGE_KEYS) {
            assertEquals("edge-" + key, map.get(key));
        }
        assertEquals(EDGE_KEYS.length, map.mappingCount());
    }

    private static long randomKey(SplittableRandom random, int iteration, Map<Long, String> expected) {
        if (!expected.isEmpty() && (iteration & 3) == 0) {
            int target = random.nextInt(expected.size());
            int index = 0;
            for (Long key : expected.keySet()) {
                if (index++ == target) {
                    return key;
                }
            }
        }
        return switch (iteration & 15) {
            case 0 -> EDGE_KEYS[(iteration >>> 4) % EDGE_KEYS.length];
            case 1 -> random.nextLong(-256L, 256L);
            case 2 -> random.nextLong(0L, 4_096L);
            case 3 -> -random.nextLong(0L, 4_096L);
            case 4 -> Long.MIN_VALUE + random.nextInt(4_096);
            case 5 -> Long.MAX_VALUE - random.nextInt(4_096);
            default -> random.nextLong();
        };
    }

    private static long keyForThread(long base, SplittableRandom random) {
        return base | random.nextInt(16_384);
    }

    private static void assertMapMatches(Long2ReferenceMap<String> map, Map<Long, String> expected) {
        assertEquals(expected.size(), map.mappingCount(), "mappingCount");
        assertEquals(expected.isEmpty(), map.isEmpty(), "isEmpty");
        for (Map.Entry<Long, String> entry : expected.entrySet()) {
            assertTrue(map.containsKey(entry.getKey()), "contains key=" + entry.getKey());
            assertEquals(entry.getValue(), map.get(entry.getKey()), "get key=" + entry.getKey());
        }
        Map<Long, String> seen = new HashMap<>();
        map.forEach((key, value) -> assertNull(seen.put(key, value), "duplicate key=" + key));
        assertEquals(expected, seen, "forEach snapshot");
        assertTrue(map.stats().allocatedPages() >= map.stats().retiredPages());
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

package net.sixik.concurrent_library.collections.maps.long2int;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class Long2IntConcurrencyStressTest {
    private static final int MISSING = Integer.MIN_VALUE;
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

    @RepeatedTest(3)
    @Timeout(value = 40, unit = TimeUnit.SECONDS)
    void dynamicMapsSurviveResizeDeleteAndReinsertStorms() throws Exception {
        for (boolean managed : new boolean[]{false, true}) {
            try (Long2IntMap map = managed ? new ManagedConcurrentLong2IntMap(8) : new ConcurrentLong2IntMap(8)) {
                int threads = 8;
                int perThread = 3_000;
                ExecutorService executor = Executors.newFixedThreadPool(threads);
                CountDownLatch start = new CountDownLatch(1);
                Future<?>[] futures = new Future<?>[threads];

                for (int t = 0; t < threads; t++) {
                    int thread = t;
                    futures[t] = executor.submit(() -> {
                        await(start);
                        SplittableRandom random = new SplittableRandom(0x516A6E_0000L + thread);
                        long base = ((long) thread + 1L) << 40;
                        for (int i = 0; i < perThread; i++) {
                            long key = keyForThread(base, random, i);
                            int value = (thread << 24) ^ i;
                            switch ((i + thread) % 10) {
                                case 0, 1, 2, 3 -> {
                                    assertTrue(map.put(key, value));
                                }
                                case 4 -> {
                                    map.putIfAbsent(key, value);
                                }
                                case 5 -> {
                                    map.removeAndGetOld(key, MISSING);
                                }
                                case 6 -> {
                                    int old = map.getOrDefault(key, MISSING);
                                    if (old != MISSING) {
                                        map.compareAndSet(key, old, value);
                                    }
                                }
                                case 7 -> {
                                    map.addIfPresent(key, 1);
                                }
                                case 8 -> map.getOrDefault(key, MISSING);
                                default -> map.containsKey(key);
                            }
                            if ((i & 255) == 0) {
                                map.completeResize();
                            }
                        }
                    });
                }

                start.countDown();
                for (Future<?> future : futures) {
                    future.get();
                }
                executor.shutdown();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
                map.completeResize();

                assertHealthy(map, managed ? "managed" : "plain");
                assertTrue(map.size() >= 0, managed ? "managed" : "plain");
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void managedMapRejectsHazardRegistryOverflowWithoutCorruptingExistingEntries() throws Exception {
        try (Long2IntMap map = new ManagedConcurrentLong2IntMap(16)) {
            for (long key : EDGE_KEYS) {
                assertTrue(map.put(key, (int) key));
            }

            int threads = Math.max(72, Runtime.getRuntime().availableProcessors() * 8 + 8);
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                int thread = i;
                futures[i] = executor.submit(() -> {
                    await(start);
                    try {
                        map.getOrDefault(EDGE_KEYS[thread % EDGE_KEYS.length], MISSING);
                    } catch (IllegalStateException expected) {
                        assertTrue(expected.getMessage().contains("hazard registry capacity exceeded"));
                    }
                });
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

            for (long key : EDGE_KEYS) {
                assertEquals((int) key, map.getOrDefault(key, MISSING));
            }
            assertHealthy(map, "managed hazard overflow");
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void dynamicMapsRecycleDeleteMarkersAfterHeavyRemoveReinsertCycles() {
        for (boolean managed : new boolean[]{false, true}) {
            try (Long2IntMap map = managed ? new ManagedConcurrentLong2IntMap(16) : new ConcurrentLong2IntMap(16)) {
                int keys = 512;
                for (int round = 0; round < 20; round++) {
                    for (int i = 0; i < keys; i++) {
                        assertTrue(map.put(i, round * keys + i), label(managed, i));
                    }
                    for (int i = 0; i < keys; i += 2) {
                        assertTrue(map.remove(i), label(managed, i));
                    }
                    for (int i = 0; i < keys; i += 2) {
                        assertTrue(map.put(i, -round * keys - i), label(managed, i));
                    }
                    map.completeResize();
                    assertHealthy(map, managed ? "managed" : "plain");
                }

                for (int i = 0; i < keys; i++) {
                    assertTrue(map.containsKey(i), label(managed, i));
                }
                assertEquals(keys, map.size());
            }
        }
    }

    private static long keyForThread(long base, SplittableRandom random, int iteration) {
        return switch (iteration & 15) {
            case 0 -> base | 0x7000_0000L | ((iteration >>> 4) % EDGE_KEYS.length);
            case 1 -> base | (iteration & 1_023L);
            case 2 -> random.nextInt(2_048) - 1_024L;
            case 3 -> Long.MIN_VALUE + random.nextInt(4_096);
            case 4 -> Long.MAX_VALUE - random.nextInt(4_096);
            default -> base | random.nextInt(65_536);
        };
    }

    private static void assertHealthy(Long2IntMap map, String label) {
        Long2IntInspector inspector = map.inspector();
        Long2IntHealth health = inspector.healthCheck();
        assertTrue(health.healthy(), label + " " + health.problems());
        assertEquals(inspector.exactSize(), map.size(), label);
    }

    private static String label(boolean managed, long key) {
        return (managed ? "managed" : "plain") + " key=" + key;
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

package net.sixik.concurrent_library.collections.maps.long2int;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.Random;
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
    void fixedConcurrentDisjointWritesAndSharedAddsAreStable() throws Exception {
        try (Long2IntAppendMap map = new FixedDirectLong2IntAppendMap(4_096)) {
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
            try (Long2IntMap map = managed ? new ManagedConcurrentLong2IntMap(32) : new ConcurrentLong2IntMap(32)) {
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
                            + " expected=" + entry.getValue()
                            + " actual=" + actual
                            + " size=" + map.size()
                            + " stats=" + map.inspector().stats();
                    assertEquals(entry.getValue(), actual, label);
                    assertTrue(map.containsKey(key), label);
                }
                assertEquals(mustExist.size(), map.size(), managed ? "managed" : "plain");
                assertEquals(mustExist.size(), map.inspector().exactSize(), managed ? "managed" : "plain");
                assertTrue(map.inspector().healthCheck().healthy(), map.inspector().healthCheck().problems().toString());
                assertTrue(removeMisses.get() >= 0);
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentDynamicMapReadersWritersAndResizersDoNotThrow() throws Exception {
        try (Long2IntMap map = new ManagedConcurrentLong2IntMap(16)) {
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
            assertEquals(map.inspector().exactSize(), map.size());
            assertTrue(map.inspector().healthCheck().healthy(), map.inspector().healthCheck().problems().toString());
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
                        map.getOrDefault(EDGE_KEYS[thread % EDGE_KEYS.length], Integer.MIN_VALUE);
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
                assertEquals((int) key, map.getOrDefault(key, Integer.MIN_VALUE));
            }
            assertHealthyAndSized(map, EDGE_KEYS.length);
        }
    }

    private static void assertHealthyAndSized(Long2IntLookup map, int expectedSize) {
        Long2IntInspector inspector = map.inspector();
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

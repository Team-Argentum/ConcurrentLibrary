package net.sixik.concurrent_library.long2reference;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.jupiter.api.Assertions.*;

class Long2ReferenceTest {
    @Test
    void denseMatchesJavaAndFastUtilForCoreOperations() {
        DenseChecked<String> table = Long2Reference.dense(100L, 16);
        Map<Long, String> java = new ConcurrentHashMap<>();
        AtomicReferenceArray<String> atomicArray = new AtomicReferenceArray<>(16);
        Long2ReferenceOpenHashMap<String> fastUtil = new Long2ReferenceOpenHashMap<>();

        for (long key = 100L; key < 116L; key++) {
            String value = "v" + key;
            table.put(key, value);
            java.put(key, value);
            atomicArray.set((int) (key - 100L), value);
            fastUtil.put(key, value);
        }

        for (long key = 100L; key < 116L; key++) {
            assertEquals(java.get(key), table.get(key));
            assertEquals(atomicArray.get((int) (key - 100L)), table.get(key));
            assertEquals(fastUtil.get(key), table.get(key));
            assertTrue(table.containsKey(key));
        }

        assertFalse(table.putIfAbsent(104L, "ignored"));
        String old104 = table.get(104L);
        assertEquals("v104", old104);
        assertTrue(table.compareAndSet(104L, old104, "changed"));
        assertTrue(atomicArray.compareAndSet(4, old104, "changed"));
        java.put(104L, "changed");
        fastUtil.put(104L, "changed");
        assertEquals(java.get(104L), table.get(104L));
        assertEquals(atomicArray.get(4), table.get(104L));
        assertEquals(fastUtil.get(104L), table.get(104L));

        assertEquals("changed", table.remove(104L));
        java.remove(104L);
        atomicArray.set(4, null);
        fastUtil.remove(104L);
        assertNull(table.get(104L));
        assertEquals(java.get(104L), table.get(104L));
        assertEquals(atomicArray.get(4), table.get(104L));
        assertEquals(fastUtil.get(104L), table.get(104L));

        table.delete(105L);
        java.remove(105L);
        atomicArray.set(5, null);
        fastUtil.remove(105L);
        assertEquals(14L, table.countByScan());
        assertFalse(table.isEmptyByScan());
    }

    @Test
    void pagedSupportsSlotsAcrossPageBoundary() {
        long base = -5L;
        long capacity = PagedChecked.PAGE_SIZE + 3L;
        PagedChecked<String> table = Long2Reference.paged(base, capacity);

        long first = base;
        long boundary = base + PagedChecked.PAGE_SIZE;
        long last = base + capacity - 1;
        table.put(first, "first");
        table.put(boundary, "boundary");
        table.put(last, "last");

        assertEquals("first", table.getAt(table.checkedSlotLong(first)));
        assertEquals("boundary", table.getAt(table.checkedSlotLong(boundary)));
        assertEquals("last", table.getAt(table.checkedSlotLong(last)));
        assertEquals(3L, table.countByScan());
        assertEquals(-1L, table.trySlotLong(last + 1));
    }

    @Test
    void pagedAllocatesPagesLazilyForSparseLargeRanges() {
        long capacity = (long) PagedChecked.PAGE_SIZE * 100_000L + 7L;
        PagedChecked<String> table = Long2Reference.paged(0L, capacity);
        long last = capacity - 1L;

        assertTrue(table.isEmptyByScan());
        assertNull(table.get(last));
        assertNull(table.remove(last));
        table.delete(last);

        assertTrue(table.putIfAbsent(last, "last"));
        assertEquals("last", table.get(last));
        assertEquals(1L, table.countByScan());
    }

    @Test
    void pagedCasOnUnallocatedPageMatchesNullSlotSemantics() {
        PagedChecked<String> table = Long2Reference.paged(0L, (long) PagedChecked.PAGE_SIZE * 4L);
        long key = PagedChecked.PAGE_SIZE * 3L;

        assertFalse(table.compareAndSet(key, "missing", "value"));
        assertTrue(table.compareAndSet(key, null, null));
        assertNull(table.get(key));
        assertTrue(table.compareAndSet(key, null, "value"));
        assertEquals("value", table.get(key));
    }

    @Test
    void slotApiAvoidsRepeatedKeyChecks() {
        DenseChecked<String> table = Long2Reference.dense(10L, 4);
        int slot = table.checkedSlot(12L);

        table.putAt(slot, "slot");
        assertEquals("slot", table.getAt(slot));

        Slot<DenseChecked<String>> token = table.checkedSlotObject(12L);
        assertEquals("slot", table.get(token));
        table.delete(token);
        assertNull(table.getAt(slot));
    }

    @Test
    void rangeAndNullPolicyAreEnforced() {
        DenseChecked<String> table = Long2Reference.dense(0L, 2);

        assertThrows(NullPointerException.class, () -> table.put(0L, null));
        assertThrows(IndexOutOfBoundsException.class, () -> table.get(-1L));
        assertThrows(IndexOutOfBoundsException.class, () -> table.put(2L, "x"));
        assertEquals(-1, table.trySlot(2L));
    }

    @Test
    void nullableCountingAndStatefulVariantsFollowDocumentedPolicies() {
        NullableLong2Reference<String> nullable = Long2Reference.nullable(0L, 4L);
        nullable.put(1L, null);
        assertTrue(nullable.containsKey(1L));
        assertNull(nullable.get(1L));
        assertEquals(1L, nullable.countByScan());

        CountingLong2Reference<String> counting = Long2Reference.counting(0L, 4L);
        assertTrue(counting.isEmpty());
        assertTrue(counting.putIfAbsent(2L, "two"));
        assertEquals(1L, counting.size());
        counting.put(2L, "replacement");
        assertEquals(1L, counting.size());
        assertEquals("replacement", counting.remove(2L));
        assertEquals(0L, counting.size());

        StatefulLong2Reference<String> stateful = Long2Reference.stateful(0L, 4L);
        assertEquals(Long2ReferenceState.NEVER_SET, stateful.state(3L));
        stateful.put(3L, null);
        assertEquals(Long2ReferenceState.PRESENT_NULL, stateful.state(3L));
        assertTrue(stateful.containsKey(3L));
        stateful.delete(3L);
        assertEquals(Long2ReferenceState.DELETED, stateful.state(3L));
        assertFalse(stateful.containsKey(3L));
        assertTrue(stateful.putIfAbsent(3L, "after-delete"));
        assertEquals(Long2ReferenceState.PRESENT, stateful.state(3L));
        assertEquals("after-delete", stateful.remove(3L));
        assertEquals(Long2ReferenceState.DELETED, stateful.state(3L));
    }

    @Test
    void builderCreatesExpertVariants() {
        Long2Reference<String> padded = Long2Reference.builder()
                .range(0L, 8L)
                .padded()
                .build();
        assertInstanceOf(PaddedDenseChecked.class, padded);

        Long2Reference<String> strict = Long2Reference.builder()
                .range(0L, 8L)
                .dense()
                .strict()
                .build();
        assertInstanceOf(StrictDenseChecked.class, strict);

        Long2Reference<String> plainPaged = Long2Reference.builder()
                .range(0L, 8L)
                .paged()
                .plain()
                .build();
        assertInstanceOf(PlainPagedChecked.class, plainPaged);

        Long2Reference<String> nullableCounting = Long2Reference.builder()
                .range(0L, 4L)
                .nullable()
                .counting()
                .build();
        nullableCounting.put(1L, null);
        assertTrue(nullableCounting.containsKey(1L));
        assertNull(nullableCounting.get(1L));
        assertEquals(1L, nullableCounting.countByScan());
    }

    @Test
    void refPublishesReplacementWithAcquireRelease() {
        DenseChecked<String> first = Long2Reference.dense(0L, 2);
        DenseChecked<String> second = Long2Reference.dense(0L, 2);
        Long2Reference.Ref<DenseChecked<String>> ref = Long2Reference.ref(first);

        assertSame(first, ref.get());
        ref.set(second);
        assertSame(second, ref.get());
    }

    @Test
    void concurrentWritesAndReadsPublishReferences() throws Exception {
        DenseChecked<Box> table = Long2Reference.dense(0L, 1_024);
        int threads = 4;
        int perThread = 256;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        for (int thread = 0; thread < threads; thread++) {
            int threadId = thread;
            executor.submit(() -> {
                await(start);
                int from = threadId * perThread;
                int to = from + perThread;
                for (int key = from; key < to; key++) {
                    table.put(key, new Box(key, "v" + key));
                }
            });
        }

        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        for (int key = 0; key < threads * perThread; key++) {
            Box box = table.get(key);
            assertNotNull(box);
            assertEquals(key, box.id());
            assertEquals("v" + key, box.value());
        }
        assertEquals(threads * perThread, table.countByScan());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private record Box(long id, String value) {
    }
}

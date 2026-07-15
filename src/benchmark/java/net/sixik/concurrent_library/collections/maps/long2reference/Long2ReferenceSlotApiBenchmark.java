package net.sixik.concurrent_library.collections.maps.long2reference;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
public class Long2ReferenceSlotApiBenchmark {
    private static final long BASE_KEY = -5_000_000L;
    private static final int KEY_SAMPLE_SIZE = 1 << 16;

    @State(Scope.Benchmark)
    public static class Data {
        @Param({"65536", "1048576", "4194304"})
        public int capacity;

        DenseChecked<Value> denseChecked;
        DenseUnchecked<Value> denseUnchecked;
        PagedChecked<Value> pagedChecked;
        PagedUnchecked<Value> pagedUnchecked;
        Value[] values;
        long[] keys;
        int[] denseSlots;
        long[] pagedSlots;
        Slot<DenseChecked<Value>>[] slotTokens;

        @Setup(Level.Trial)
        @SuppressWarnings("unchecked")
        public void setup() {
            denseChecked = new DenseChecked<>(BASE_KEY, capacity);
            denseUnchecked = new DenseUnchecked<>(BASE_KEY, capacity);
            pagedChecked = new PagedChecked<>(BASE_KEY, capacity);
            pagedUnchecked = new PagedUnchecked<>(BASE_KEY, capacity);
            values = new Value[capacity];
            for (int slot = 0; slot < capacity; slot++) {
                values[slot] = new Value(slot, "value-" + slot);
                long key = BASE_KEY + slot;
                denseChecked.put(key, values[slot]);
                denseUnchecked.put(key, values[slot]);
                pagedChecked.put(key, values[slot]);
                pagedUnchecked.put(key, values[slot]);
            }

            keys = new long[KEY_SAMPLE_SIZE];
            denseSlots = new int[KEY_SAMPLE_SIZE];
            pagedSlots = new long[KEY_SAMPLE_SIZE];
            slotTokens = new Slot[KEY_SAMPLE_SIZE];
            SplittableRandom random = new SplittableRandom(capacity * 97L);
            for (int i = 0; i < KEY_SAMPLE_SIZE; i++) {
                int slot = random.nextInt(capacity);
                long key = BASE_KEY + slot;
                keys[i] = key;
                denseSlots[i] = denseChecked.checkedSlot(key);
                pagedSlots[i] = pagedChecked.checkedSlotLong(key);
                slotTokens[i] = denseChecked.checkedSlotObject(key);
            }
        }
    }

    @State(Scope.Thread)
    public static class Cursor {
        int index;

        int next() {
            return index++ & (KEY_SAMPLE_SIZE - 1);
        }
    }

    @Benchmark
    public Object dense_checked_get_by_key(Data data, Cursor cursor) {
        return data.denseChecked.get(data.keys[cursor.next()]);
    }

    @Benchmark
    public Object dense_checked_get_at(Data data, Cursor cursor) {
        return data.denseChecked.getAt(data.denseSlots[cursor.next()]);
    }

    @Benchmark
    public Object dense_checked_get_by_slot_token(Data data, Cursor cursor) {
        return data.denseChecked.get(data.slotTokens[cursor.next()]);
    }

    @Benchmark
    public Object dense_unchecked_get_by_key(Data data, Cursor cursor) {
        return data.denseUnchecked.get(data.keys[cursor.next()]);
    }

    @Benchmark
    public Object dense_unchecked_get_trusted(Data data, Cursor cursor) {
        return data.denseUnchecked.getTrusted(data.keys[cursor.next()]);
    }

    @Benchmark
    public Object dense_unchecked_get_at_unchecked(Data data, Cursor cursor) {
        return data.denseUnchecked.getAtUnchecked(data.denseSlots[cursor.next()]);
    }

    @Benchmark
    public Object paged_checked_get_by_key(Data data, Cursor cursor) {
        return data.pagedChecked.get(data.keys[cursor.next()]);
    }

    @Benchmark
    public Object paged_checked_get_at(Data data, Cursor cursor) {
        return data.pagedChecked.getAt(data.pagedSlots[cursor.next()]);
    }

    @Benchmark
    public Object paged_unchecked_get_by_key(Data data, Cursor cursor) {
        return data.pagedUnchecked.get(data.keys[cursor.next()]);
    }

    @Benchmark
    public Object paged_unchecked_get_trusted(Data data, Cursor cursor) {
        return data.pagedUnchecked.getTrusted(data.keys[cursor.next()]);
    }

    @Benchmark
    public Object paged_unchecked_get_at_unchecked(Data data, Cursor cursor) {
        return data.pagedUnchecked.getAtUnchecked(data.pagedSlots[cursor.next()]);
    }

    @Benchmark
    public void dense_checked_put_at(Data data, Cursor cursor, Blackhole blackhole) {
        int i = cursor.next();
        int slot = data.denseSlots[i];
        data.denseChecked.putAt(slot, data.values[slot]);
        blackhole.consume(slot);
    }

    @Benchmark
    public void dense_unchecked_put_at_unchecked(Data data, Cursor cursor, Blackhole blackhole) {
        int i = cursor.next();
        int slot = data.denseSlots[i];
        data.denseUnchecked.putAtUnchecked(slot, data.values[slot]);
        blackhole.consume(slot);
    }

    @Benchmark
    public void paged_checked_put_at(Data data, Cursor cursor, Blackhole blackhole) {
        int i = cursor.next();
        long slot = data.pagedSlots[i];
        data.pagedChecked.putAt(slot, data.values[(int) slot]);
        blackhole.consume(slot);
    }

    @Benchmark
    public void paged_unchecked_put_at_unchecked(Data data, Cursor cursor, Blackhole blackhole) {
        int i = cursor.next();
        long slot = data.pagedSlots[i];
        data.pagedUnchecked.putAtUnchecked(slot, data.values[(int) slot]);
        blackhole.consume(slot);
    }

    private record Value(int slot, String label) {
    }
}

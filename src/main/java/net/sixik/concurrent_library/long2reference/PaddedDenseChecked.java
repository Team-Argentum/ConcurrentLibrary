package net.sixik.concurrent_library.long2reference;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class PaddedDenseChecked<V> implements Long2Reference<V> {
    private static final VarHandle REF = MethodHandles.arrayElementVarHandle(Object[].class);

    private final long base;
    private final long capacity;
    private final long limit;
    private final int stride;
    private final Object[] values;

    PaddedDenseChecked(long base, int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException();
        }
        this.base = base;
        this.capacity = capacity;
        this.limit = Math.addExact(base, capacity);
        this.stride = 64 / Long2ReferenceFactory.referenceScale();
        this.values = new Object[Math.multiplyExact(capacity, stride)];
        Long2ReferenceDiagnostics.diagnose(base, values.length, "padded-dense", false);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(long key) {
        return (V) REF.getAcquire(values, physicalSlot(checkedSlot(key)));
    }

    @Override
    public void put(long key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        REF.setRelease(values, physicalSlot(checkedSlot(key)), value);
    }

    @Override
    public void delete(long key) {
        REF.setRelease(values, physicalSlot(checkedSlot(key)), null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V remove(long key) {
        return (V) REF.getAndSet(values, physicalSlot(checkedSlot(key)), null);
    }

    @Override
    public boolean compareAndSet(long key, V expected, V update) {
        return REF.compareAndSet(values, physicalSlot(checkedSlot(key)), expected, update);
    }

    @Override
    public boolean putIfAbsent(long key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        return REF.compareAndSet(values, physicalSlot(checkedSlot(key)), null, value);
    }

    @Override public boolean containsKey(long key) { return get(key) != null; }
    @Override public long baseKey() { return base; }
    @Override public long capacity() { return capacity; }
    @Override public boolean isEmptyByScan() { return countByScan() == 0L; }
    @Override
    public long countByScan() {
        long count = 0L;
        for (int i = 0; i < capacity; i++) {
            if (REF.getAcquire(values, physicalSlot(i)) != null) {
                count++;
            }
        }
        return count;
    }

    public int checkedSlot(long key) {
        if (key < base || key >= limit) {
            throw new IndexOutOfBoundsException();
        }
        return (int) (key - base);
    }

    private int physicalSlot(int logicalSlot) {
        return logicalSlot * stride;
    }
}

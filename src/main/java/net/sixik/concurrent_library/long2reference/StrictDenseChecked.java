package net.sixik.concurrent_library.long2reference;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class StrictDenseChecked<V> implements Long2Reference<V> {
    private static final VarHandle REF = MethodHandles.arrayElementVarHandle(Object[].class);

    private final long base;
    private final long limit;
    private final Object[] values;

    StrictDenseChecked(long base, int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException();
        }
        this.base = base;
        this.limit = Math.addExact(base, capacity);
        this.values = new Object[capacity];
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(long key) {
        return (V) REF.getVolatile(values, checkedSlot(key));
    }

    @Override
    public void put(long key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        REF.setVolatile(values, checkedSlot(key), value);
    }

    @Override
    public void delete(long key) {
        REF.setVolatile(values, checkedSlot(key), null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V remove(long key) {
        return (V) REF.getAndSet(values, checkedSlot(key), null);
    }

    @Override
    public boolean compareAndSet(long key, V expected, V update) {
        return REF.compareAndSet(values, checkedSlot(key), expected, update);
    }

    @Override
    public boolean putIfAbsent(long key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        return REF.compareAndSet(values, checkedSlot(key), null, value);
    }

    @Override
    public boolean containsKey(long key) {
        return get(key) != null;
    }

    @Override
    public long baseKey() {
        return base;
    }

    @Override
    public long capacity() {
        return values.length;
    }

    @Override
    public boolean isEmptyByScan() {
        for (int i = 0; i < values.length; i++) {
            if (REF.getVolatile(values, i) != null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public long countByScan() {
        long count = 0L;
        for (int i = 0; i < values.length; i++) {
            if (REF.getVolatile(values, i) != null) {
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
}

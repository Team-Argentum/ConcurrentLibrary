package net.sixik.concurrent_library.long2reference;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class DenseUnchecked<V> implements Long2Reference<V> {
    private static final VarHandle REF = MethodHandles.arrayElementVarHandle(Object[].class);

    private final long base;
    private final long limit;
    private final Object[] values;

    DenseUnchecked(long base, int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException();
        }
        this.base = base;
        this.limit = Math.addExact(base, capacity);
        this.values = new Object[capacity];
    }

    @Override
    public V get(long key) {
        return getAtUnchecked(checkedSlot(key));
    }

    @Override
    public void put(long key, V value) {
        putAtUnchecked(checkedSlot(key), value);
    }

    @Override
    public void delete(long key) {
        deleteAtUnchecked(checkedSlot(key));
    }

    @Override
    public V remove(long key) {
        return removeAtUnchecked(checkedSlot(key));
    }

    @Override
    public boolean compareAndSet(long key, V expected, V update) {
        return compareAndSetAtUnchecked(checkedSlot(key), expected, update);
    }

    @Override
    public boolean putIfAbsent(long key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        return compareAndSetAtUnchecked(checkedSlot(key), null, value);
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
            if (REF.getAcquire(values, i) != null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public long countByScan() {
        long count = 0L;
        for (int i = 0; i < values.length; i++) {
            if (REF.getAcquire(values, i) != null) {
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

    public int trustedSlot(long key) {
        return (int) (key - base);
    }

    @SuppressWarnings("unchecked")
    public V getTrusted(long key) {
        return (V) REF.getAcquire(values, trustedSlot(key));
    }

    public void putTrusted(long key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        REF.setRelease(values, trustedSlot(key), value);
    }

    public void deleteTrusted(long key) {
        REF.setRelease(values, trustedSlot(key), null);
    }

    @SuppressWarnings("unchecked")
    public V getAtUnchecked(int slot) {
        return (V) REF.getAcquire(values, slot);
    }

    public void putAtUnchecked(int slot, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        REF.setRelease(values, slot, value);
    }

    public void deleteAtUnchecked(int slot) {
        REF.setRelease(values, slot, null);
    }

    @SuppressWarnings("unchecked")
    public V removeAtUnchecked(int slot) {
        return (V) REF.getAndSet(values, slot, null);
    }

    public boolean compareAndSetAtUnchecked(int slot, V expected, V update) {
        return REF.compareAndSet(values, slot, expected, update);
    }
}

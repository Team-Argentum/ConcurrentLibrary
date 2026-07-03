package net.sixik.concurrent_library.long2reference;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class DenseChecked<V> implements Long2Reference<V> {
    private static final VarHandle REF = MethodHandles.arrayElementVarHandle(Object[].class);

    private final long base;
    private final long limit;
    private final Object[] values;

    DenseChecked(long base, int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException();
        }

        this.base = base;
        this.limit = Math.addExact(base, capacity);
        this.values = new Object[capacity];
        Long2ReferenceDiagnostics.diagnose(base, capacity, "dense", false);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(long key) {
        return (V) REF.getAcquire(values, checkedSlot(key));
    }

    @Override
    public void put(long key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        REF.setRelease(values, checkedSlot(key), value);
    }

    @Override
    public void delete(long key) {
        REF.setRelease(values, checkedSlot(key), null);
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

    public int trySlot(long key) {
        if (key < base || key >= limit) {
            return -1;
        }
        return (int) (key - base);
    }

    @SuppressWarnings("unchecked")
    public V getAt(int slot) {
        return (V) REF.getAcquire(values, slot);
    }

    public void putAt(int slot, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        REF.setRelease(values, slot, value);
    }

    public void deleteAt(int slot) {
        REF.setRelease(values, slot, null);
    }

    @SuppressWarnings("unchecked")
    public V removeAt(int slot) {
        return (V) REF.getAndSet(values, slot, null);
    }

    public boolean compareAndSetAt(int slot, V expected, V update) {
        return REF.compareAndSet(values, slot, expected, update);
    }

    public boolean putIfAbsentAt(int slot, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        return REF.compareAndSet(values, slot, null, value);
    }

    public Slot<DenseChecked<V>> checkedSlotObject(long key) {
        return new Slot<>(this, checkedSlot(key));
    }

    public V get(Slot<DenseChecked<V>> slot) {
        return getAt(slotIndex(slot));
    }

    public void put(Slot<DenseChecked<V>> slot, V value) {
        putAt(slotIndex(slot), value);
    }

    public void delete(Slot<DenseChecked<V>> slot) {
        deleteAt(slotIndex(slot));
    }

    private int slotIndex(Slot<DenseChecked<V>> slot) {
        if (slot == null || slot.owner() != this) {
            throw new IllegalArgumentException();
        }
        return Math.toIntExact(slot.slot());
    }
}

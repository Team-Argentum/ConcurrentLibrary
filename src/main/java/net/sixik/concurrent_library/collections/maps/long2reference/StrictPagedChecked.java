package net.sixik.concurrent_library.collections.maps.long2reference;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class StrictPagedChecked<V> implements Long2ReferenceTable<V> {
    private static final VarHandle REF = MethodHandles.arrayElementVarHandle(Object[].class);

    private final PagedChecked<V> delegate;

    public StrictPagedChecked(long base, long capacity) {
        this.delegate = new PagedChecked<>(base, capacity);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(long key) {
        long slot = delegate.checkedSlotLong(key);
        Object[] page = delegate.pageForRead(slot);
        if (page == null) {
            return null;
        }
        return (V) REF.getVolatile(page, (int) slot & PagedChecked.PAGE_MASK);
    }

    @Override
    public void put(long key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        long slot = delegate.checkedSlotLong(key);
        REF.setVolatile(delegate.pageForWrite(slot), (int) slot & PagedChecked.PAGE_MASK, value);
    }

    @Override
    public void delete(long key) {
        long slot = delegate.checkedSlotLong(key);
        Object[] page = delegate.pageForRead(slot);
        if (page != null) {
            REF.setVolatile(page, (int) slot & PagedChecked.PAGE_MASK, null);
        }
    }

    @Override public V remove(long key) { return delegate.remove(key); }
    @Override public boolean compareAndSet(long key, V expected, V update) { return delegate.compareAndSet(key, expected, update); }
    @Override public boolean putIfAbsent(long key, V value) { return delegate.putIfAbsent(key, value); }
    @Override public boolean containsKey(long key) { return get(key) != null; }
    @Override public long baseKey() { return delegate.baseKey(); }
    @Override public long capacity() { return delegate.capacity(); }
    @Override public boolean isEmptyByScan() { return countByScan() == 0L; }
    @Override public long countByScan() { return delegate.countByScan(); }

}

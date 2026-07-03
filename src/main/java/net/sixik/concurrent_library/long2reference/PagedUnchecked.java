package net.sixik.concurrent_library.long2reference;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class PagedUnchecked<V> implements Long2Reference<V> {
    private static final VarHandle REF = MethodHandles.arrayElementVarHandle(Object[].class);

    private final long base;
    private final long capacity;
    private final long limit;
    private final Object[][] pages;

    PagedUnchecked(long base, long capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException();
        }
        this.base = base;
        this.capacity = capacity;
        this.limit = Math.addExact(base, capacity);
        long pageCountLong = (capacity >>> PagedChecked.PAGE_BITS)
                + ((capacity & PagedChecked.PAGE_MASK) == 0 ? 0 : 1);
        int pageCount = Math.toIntExact(pageCountLong);
        Object[][] p = new Object[pageCount][];
        for (int i = 0; i < pageCount; i++) {
            p[i] = new Object[PagedChecked.PAGE_SIZE];
        }
        this.pages = p;
    }

    @Override
    public V get(long key) {
        return getAtUnchecked(checkedSlotLong(key));
    }

    @Override
    public void put(long key, V value) {
        putAtUnchecked(checkedSlotLong(key), value);
    }

    @Override
    public void delete(long key) {
        deleteAtUnchecked(checkedSlotLong(key));
    }

    @Override
    public V remove(long key) {
        return removeAtUnchecked(checkedSlotLong(key));
    }

    @Override
    public boolean compareAndSet(long key, V expected, V update) {
        return compareAndSetAtUnchecked(checkedSlotLong(key), expected, update);
    }

    @Override
    public boolean putIfAbsent(long key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        return compareAndSetAtUnchecked(checkedSlotLong(key), null, value);
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
        return capacity;
    }

    @Override
    public boolean isEmptyByScan() {
        long remaining = capacity;
        for (Object[] page : pages) {
            int limit = (int) Math.min(PagedChecked.PAGE_SIZE, remaining);
            for (int i = 0; i < limit; i++) {
                if (REF.getAcquire(page, i) != null) {
                    return false;
                }
            }
            remaining -= limit;
        }
        return true;
    }

    @Override
    public long countByScan() {
        long count = 0L;
        long remaining = capacity;
        for (Object[] page : pages) {
            int limit = (int) Math.min(PagedChecked.PAGE_SIZE, remaining);
            for (int i = 0; i < limit; i++) {
                if (REF.getAcquire(page, i) != null) {
                    count++;
                }
            }
            remaining -= limit;
        }
        return count;
    }

    public long checkedSlotLong(long key) {
        if (key < base || key >= limit) {
            throw new IndexOutOfBoundsException();
        }
        return key - base;
    }

    public long trustedSlot(long key) {
        return key - base;
    }

    public V getTrusted(long key) {
        return getAtUnchecked(trustedSlot(key));
    }

    public void putTrusted(long key, V value) {
        putAtUnchecked(trustedSlot(key), value);
    }

    public void deleteTrusted(long key) {
        deleteAtUnchecked(trustedSlot(key));
    }

    @SuppressWarnings("unchecked")
    public V getAtUnchecked(long slot) {
        Object[] page = pages[(int) (slot >>> PagedChecked.PAGE_BITS)];
        return (V) REF.getAcquire(page, (int) slot & PagedChecked.PAGE_MASK);
    }

    public void putAtUnchecked(long slot, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        Object[] page = pages[(int) (slot >>> PagedChecked.PAGE_BITS)];
        REF.setRelease(page, (int) slot & PagedChecked.PAGE_MASK, value);
    }

    public void deleteAtUnchecked(long slot) {
        Object[] page = pages[(int) (slot >>> PagedChecked.PAGE_BITS)];
        REF.setRelease(page, (int) slot & PagedChecked.PAGE_MASK, null);
    }

    @SuppressWarnings("unchecked")
    public V removeAtUnchecked(long slot) {
        Object[] page = pages[(int) (slot >>> PagedChecked.PAGE_BITS)];
        return (V) REF.getAndSet(page, (int) slot & PagedChecked.PAGE_MASK, null);
    }

    public boolean compareAndSetAtUnchecked(long slot, V expected, V update) {
        Object[] page = pages[(int) (slot >>> PagedChecked.PAGE_BITS)];
        return REF.compareAndSet(page, (int) slot & PagedChecked.PAGE_MASK, expected, update);
    }
}

package net.sixik.concurrent_library.collections.maps.long2reference;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class PagedChecked<V> implements Long2ReferenceTable<V> {
    static final int PAGE_BITS = 16;
    static final int PAGE_SIZE = 1 << PAGE_BITS;
    static final int PAGE_MASK = PAGE_SIZE - 1;

    private static final VarHandle PAGE = MethodHandles.arrayElementVarHandle(Object[][].class);
    private static final VarHandle REF = MethodHandles.arrayElementVarHandle(Object[].class);

    private final long base;
    private final long capacity;
    private final long limit;
    private final Object[][] pages;

    public PagedChecked(long base, long capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException();
        }

        this.base = base;
        this.capacity = capacity;
        this.limit = Math.addExact(base, capacity);

        long pageCountLong = (capacity >>> PAGE_BITS) + ((capacity & PAGE_MASK) == 0 ? 0 : 1);
        int pageCount = Math.toIntExact(pageCountLong);
        this.pages = new Object[pageCount][];
        Long2ReferenceDiagnostics.diagnose(base, capacity, "paged", (capacity & PAGE_MASK) != 0);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(long key) {
        long slot = checkedSlotLong(key);
        Object[] page = pageForRead(slot);
        if (page == null) {
            return null;
        }
        return (V) REF.getAcquire(page, (int) slot & PAGE_MASK);
    }

    @Override
    public void put(long key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        long slot = checkedSlotLong(key);
        Object[] page = pageForWrite(slot);
        REF.setRelease(page, (int) slot & PAGE_MASK, value);
    }

    @Override
    public void delete(long key) {
        long slot = checkedSlotLong(key);
        Object[] page = pageForRead(slot);
        if (page == null) {
            return;
        }
        REF.setRelease(page, (int) slot & PAGE_MASK, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V remove(long key) {
        long slot = checkedSlotLong(key);
        Object[] page = pageForRead(slot);
        if (page == null) {
            return null;
        }
        return (V) REF.getAndSet(page, (int) slot & PAGE_MASK, null);
    }

    @Override
    public boolean compareAndSet(long key, V expected, V update) {
        long slot = checkedSlotLong(key);
        Object[] page = pageForCas(slot, expected, update);
        if (page == null) {
            return expected == null;
        }
        return REF.compareAndSet(page, (int) slot & PAGE_MASK, expected, update);
    }

    @Override
    public boolean putIfAbsent(long key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        long slot = checkedSlotLong(key);
        Object[] page = pageForWrite(slot);
        return REF.compareAndSet(page, (int) slot & PAGE_MASK, null, value);
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
            int limit = (int) Math.min(PAGE_SIZE, remaining);
            if (page != null) {
                for (int i = 0; i < limit; i++) {
                    if (REF.getAcquire(page, i) != null) {
                        return false;
                    }
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
            int limit = (int) Math.min(PAGE_SIZE, remaining);
            if (page != null) {
                for (int i = 0; i < limit; i++) {
                    if (REF.getAcquire(page, i) != null) {
                        count++;
                    }
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

    public long trySlotLong(long key) {
        if (key < base || key >= limit) {
            return -1L;
        }
        return key - base;
    }

    @SuppressWarnings("unchecked")
    public V getAt(long slot) {
        Object[] page = pageForRead(slot);
        if (page == null) {
            return null;
        }
        return (V) REF.getAcquire(page, (int) slot & PAGE_MASK);
    }

    public void putAt(long slot, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        Object[] page = pageForWrite(slot);
        REF.setRelease(page, (int) slot & PAGE_MASK, value);
    }

    public void deleteAt(long slot) {
        Object[] page = pageForRead(slot);
        if (page == null) {
            return;
        }
        REF.setRelease(page, (int) slot & PAGE_MASK, null);
    }

    @SuppressWarnings("unchecked")
    public V removeAt(long slot) {
        Object[] page = pageForRead(slot);
        if (page == null) {
            return null;
        }
        return (V) REF.getAndSet(page, (int) slot & PAGE_MASK, null);
    }

    public boolean compareAndSetAt(long slot, V expected, V update) {
        Object[] page = pageForCas(slot, expected, update);
        if (page == null) {
            return expected == null;
        }
        return REF.compareAndSet(page, (int) slot & PAGE_MASK, expected, update);
    }

    public boolean putIfAbsentAt(long slot, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        Object[] page = pageForWrite(slot);
        return REF.compareAndSet(page, (int) slot & PAGE_MASK, null, value);
    }

    Object[] pageForRead(long slot) {
        return (Object[]) PAGE.getAcquire(pages, (int) (slot >>> PAGE_BITS));
    }

    Object[] pageForWrite(long slot) {
        int pageIndex = (int) (slot >>> PAGE_BITS);
        Object[] page = (Object[]) PAGE.getAcquire(pages, pageIndex);
        if (page != null) {
            return page;
        }
        Object[] created = new Object[PAGE_SIZE];
        if (PAGE.compareAndSet(pages, pageIndex, null, created)) {
            return created;
        }
        return (Object[]) PAGE.getAcquire(pages, pageIndex);
    }

    private Object[] pageForCas(long slot, Object expected, Object update) {
        Object[] page = pageForRead(slot);
        if (page != null || expected != null || update == null) {
            return page;
        }
        return pageForWrite(slot);
    }
}

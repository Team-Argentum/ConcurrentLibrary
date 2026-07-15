package net.sixik.concurrent_library.collections.maps.long2reference;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class PagedUnchecked<V> implements Long2ReferenceTable<V> {
    private static final VarHandle PAGE = MethodHandles.arrayElementVarHandle(Object[][].class);
    private static final VarHandle REF = MethodHandles.arrayElementVarHandle(Object[].class);

    private final long base;
    private final long capacity;
    private final long limit;
    private final Object[][] pages;

    public PagedUnchecked(long base, long capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException();
        }
        this.base = base;
        this.capacity = capacity;
        this.limit = Math.addExact(base, capacity);
        long pageCountLong = (capacity >>> PagedChecked.PAGE_BITS)
                + ((capacity & PagedChecked.PAGE_MASK) == 0 ? 0 : 1);
        int pageCount = Math.toIntExact(pageCountLong);
        this.pages = new Object[pageCount][];
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
            int limit = (int) Math.min(PagedChecked.PAGE_SIZE, remaining);
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
        Object[] page = pageForRead(slot);
        if (page == null) {
            return null;
        }
        return (V) REF.getAcquire(page, (int) slot & PagedChecked.PAGE_MASK);
    }

    public void putAtUnchecked(long slot, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        Object[] page = pageForWrite(slot);
        REF.setRelease(page, (int) slot & PagedChecked.PAGE_MASK, value);
    }

    public void deleteAtUnchecked(long slot) {
        Object[] page = pageForRead(slot);
        if (page == null) {
            return;
        }
        REF.setRelease(page, (int) slot & PagedChecked.PAGE_MASK, null);
    }

    @SuppressWarnings("unchecked")
    public V removeAtUnchecked(long slot) {
        Object[] page = pageForRead(slot);
        if (page == null) {
            return null;
        }
        return (V) REF.getAndSet(page, (int) slot & PagedChecked.PAGE_MASK, null);
    }

    public boolean compareAndSetAtUnchecked(long slot, V expected, V update) {
        Object[] page = pageForCas(slot, expected, update);
        if (page == null) {
            return expected == null;
        }
        return REF.compareAndSet(page, (int) slot & PagedChecked.PAGE_MASK, expected, update);
    }

    private Object[] pageForRead(long slot) {
        return (Object[]) PAGE.getAcquire(pages, (int) (slot >>> PagedChecked.PAGE_BITS));
    }

    private Object[] pageForWrite(long slot) {
        int pageIndex = (int) (slot >>> PagedChecked.PAGE_BITS);
        Object[] page = (Object[]) PAGE.getAcquire(pages, pageIndex);
        if (page != null) {
            return page;
        }
        Object[] created = new Object[PagedChecked.PAGE_SIZE];
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

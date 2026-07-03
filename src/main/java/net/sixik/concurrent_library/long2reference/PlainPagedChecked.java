package net.sixik.concurrent_library.long2reference;

public final class PlainPagedChecked<V> implements Long2Reference<V> {
    private final long base;
    private final long capacity;
    private final long limit;
    private final Object[][] pages;

    PlainPagedChecked(long base, long capacity) {
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
        for (int i = 0; i < pageCount; i++) {
            this.pages[i] = new Object[PagedChecked.PAGE_SIZE];
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(long key) {
        long slot = checkedSlotLong(key);
        return (V) pages[(int) (slot >>> PagedChecked.PAGE_BITS)][(int) slot & PagedChecked.PAGE_MASK];
    }

    @Override
    public void put(long key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        long slot = checkedSlotLong(key);
        pages[(int) (slot >>> PagedChecked.PAGE_BITS)][(int) slot & PagedChecked.PAGE_MASK] = value;
    }

    @Override
    public void delete(long key) {
        long slot = checkedSlotLong(key);
        pages[(int) (slot >>> PagedChecked.PAGE_BITS)][(int) slot & PagedChecked.PAGE_MASK] = null;
    }

    @Override
    public V remove(long key) {
        long slot = checkedSlotLong(key);
        Object[] page = pages[(int) (slot >>> PagedChecked.PAGE_BITS)];
        int offset = (int) slot & PagedChecked.PAGE_MASK;
        @SuppressWarnings("unchecked") V old = (V) page[offset];
        page[offset] = null;
        return old;
    }

    @Override
    public boolean compareAndSet(long key, V expected, V update) {
        long slot = checkedSlotLong(key);
        Object[] page = pages[(int) (slot >>> PagedChecked.PAGE_BITS)];
        int offset = (int) slot & PagedChecked.PAGE_MASK;
        if (page[offset] == expected) {
            page[offset] = update;
            return true;
        }
        return false;
    }

    @Override
    public boolean putIfAbsent(long key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        return compareAndSet(key, null, value);
    }

    @Override public boolean containsKey(long key) { return get(key) != null; }
    @Override public long baseKey() { return base; }
    @Override public long capacity() { return capacity; }
    @Override public boolean isEmptyByScan() { return countByScan() == 0L; }
    @Override
    public long countByScan() {
        long count = 0L;
        long remaining = capacity;
        for (Object[] page : pages) {
            int limit = (int) Math.min(PagedChecked.PAGE_SIZE, remaining);
            for (int i = 0; i < limit; i++) {
                if (page[i] != null) {
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
}

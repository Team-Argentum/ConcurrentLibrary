package net.sixik.concurrent_library.long2reference;

/**
 * Legacy plain-memory Long2Reference variant.
 *
 * <p>This implementation is not thread-safe. It is kept only for completeness
 * and single-threaded experiments; it is intentionally excluded from the regular
 * concurrent benchmark surface.</p>
 */
@Deprecated(forRemoval = false)
public final class PlainDenseChecked<V> implements Long2Reference<V> {
    private final long base;
    private final long limit;
    private final Object[] values;

    PlainDenseChecked(long base, int capacity) {
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
        return (V) values[checkedSlot(key)];
    }

    @Override
    public void put(long key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        values[checkedSlot(key)] = value;
    }

    @Override
    public void delete(long key) {
        values[checkedSlot(key)] = null;
    }

    @Override
    public V remove(long key) {
        int slot = checkedSlot(key);
        @SuppressWarnings("unchecked") V old = (V) values[slot];
        values[slot] = null;
        return old;
    }

    @Override
    public boolean compareAndSet(long key, V expected, V update) {
        int slot = checkedSlot(key);
        if (values[slot] == expected) {
            values[slot] = update;
            return true;
        }
        return false;
    }

    @Override
    public boolean putIfAbsent(long key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        int slot = checkedSlot(key);
        if (values[slot] == null) {
            values[slot] = value;
            return true;
        }
        return false;
    }

    @Override
    public boolean containsKey(long key) {
        return values[checkedSlot(key)] != null;
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
        for (Object value : values) {
            if (value != null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public long countByScan() {
        long count = 0L;
        for (Object value : values) {
            if (value != null) {
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

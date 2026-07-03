package net.sixik.concurrent_library.long2reference;

public final class StatefulLong2Reference<V> implements Long2Reference<V> {
    private static final Object NULL_VALUE = new Object();
    private static final Object DELETED_VALUE = new Object();

    private final Long2Reference<Object> delegate;

    @SuppressWarnings("unchecked")
    StatefulLong2Reference(Long2Reference<?> rangeSource) {
        if (rangeSource == null) {
            throw new NullPointerException();
        }
        this.delegate = (Long2Reference<Object>) rangeSource;
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(long key) {
        Object value = delegate.get(key);
        if (value == null || value == NULL_VALUE || value == DELETED_VALUE) {
            return null;
        }
        return (V) value;
    }

    @Override
    public void put(long key, V value) {
        delegate.put(key, value == null ? NULL_VALUE : value);
    }

    @Override
    public void delete(long key) {
        delegate.put(key, DELETED_VALUE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V remove(long key) {
        for (;;) {
            Object old = delegate.get(key);
            if (delegate.compareAndSet(key, old, DELETED_VALUE)) {
                if (old == null || old == NULL_VALUE || old == DELETED_VALUE) {
                    return null;
                }
                return (V) old;
            }
        }
    }

    @Override
    public boolean compareAndSet(long key, V expected, V update) {
        return delegate.compareAndSet(key, mask(expected), mask(update));
    }

    @Override
    public boolean putIfAbsent(long key, V value) {
        for (;;) {
            Object old = delegate.get(key);
            if (old != null && old != DELETED_VALUE) {
                return false;
            }
            if (delegate.compareAndSet(key, old, mask(value))) {
                return true;
            }
        }
    }

    @Override
    public boolean containsKey(long key) {
        Object value = delegate.get(key);
        return value != null && value != DELETED_VALUE;
    }

    @Override
    public long baseKey() {
        return delegate.baseKey();
    }

    @Override
    public long capacity() {
        return delegate.capacity();
    }

    @Override
    public boolean isEmptyByScan() {
        return countByScan() == 0L;
    }

    @Override
    public long countByScan() {
        long count = 0L;
        long base = delegate.baseKey();
        long capacity = delegate.capacity();
        for (long slot = 0; slot < capacity; slot++) {
            Object value = delegate.get(base + slot);
            if (value != null && value != DELETED_VALUE) {
                count++;
            }
        }
        return count;
    }

    public Long2ReferenceState state(long key) {
        Object value = delegate.get(key);
        if (value == null) {
            return Long2ReferenceState.NEVER_SET;
        }
        if (value == DELETED_VALUE) {
            return Long2ReferenceState.DELETED;
        }
        if (value == NULL_VALUE) {
            return Long2ReferenceState.PRESENT_NULL;
        }
        return Long2ReferenceState.PRESENT;
    }

    private static Object mask(Object value) {
        return value == null ? NULL_VALUE : value;
    }
}

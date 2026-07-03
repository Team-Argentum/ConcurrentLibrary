package net.sixik.concurrent_library.long2reference;

public final class NullableLong2Reference<V> implements Long2Reference<V> {
    private static final Object NULL_VALUE = new Object();

    private final Long2Reference<Object> delegate;

    @SuppressWarnings("unchecked")
    NullableLong2Reference(Long2Reference<?> delegate) {
        if (delegate == null) {
            throw new NullPointerException();
        }
        this.delegate = (Long2Reference<Object>) delegate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(long key) {
        Object value = delegate.get(key);
        return value == NULL_VALUE ? null : (V) value;
    }

    @Override
    public void put(long key, V value) {
        delegate.put(key, mask(value));
    }

    @Override
    public void delete(long key) {
        delegate.delete(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V remove(long key) {
        Object value = delegate.remove(key);
        return value == NULL_VALUE ? null : (V) value;
    }

    @Override
    public boolean compareAndSet(long key, V expected, V update) {
        return delegate.compareAndSet(key, mask(expected), mask(update));
    }

    @Override
    public boolean putIfAbsent(long key, V value) {
        return delegate.compareAndSet(key, null, mask(value));
    }

    @Override
    public boolean containsKey(long key) {
        return delegate.get(key) != null;
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
        return delegate.isEmptyByScan();
    }

    @Override
    public long countByScan() {
        return delegate.countByScan();
    }

    private static Object mask(Object value) {
        return value == null ? NULL_VALUE : value;
    }
}

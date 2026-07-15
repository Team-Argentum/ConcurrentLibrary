package net.sixik.concurrent_library.collections.maps.long2reference;

import java.util.concurrent.atomic.AtomicLong;

public final class CountingLong2Reference<V> implements Long2ReferenceTable<V> {
    private final Long2ReferenceTable<V> delegate;
    private final AtomicLong size = new AtomicLong();

    public CountingLong2Reference(Long2ReferenceTable<V> delegate) {
        if (delegate == null) {
            throw new NullPointerException();
        }
        this.delegate = delegate;
    }

    @Override
    public V get(long key) {
        return delegate.get(key);
    }

    @Override
    public void put(long key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        for (;;) {
            V old = delegate.get(key);
            if (delegate.compareAndSet(key, old, value)) {
                if (old == null) {
                    size.incrementAndGet();
                }
                return;
            }
        }
    }

    @Override
    public void delete(long key) {
        for (;;) {
            V old = delegate.get(key);
            if (old == null) {
                return;
            }
            if (delegate.compareAndSet(key, old, null)) {
                size.decrementAndGet();
                return;
            }
        }
    }

    @Override
    public V remove(long key) {
        for (;;) {
            V old = delegate.get(key);
            if (old == null) {
                return null;
            }
            if (delegate.compareAndSet(key, old, null)) {
                size.decrementAndGet();
                return old;
            }
        }
    }

    @Override
    public boolean compareAndSet(long key, V expected, V update) {
        boolean changed = delegate.compareAndSet(key, expected, update);
        if (changed) {
            if (expected == null && update != null) {
                size.incrementAndGet();
            } else if (expected != null && update == null) {
                size.decrementAndGet();
            }
        }
        return changed;
    }

    @Override
    public boolean putIfAbsent(long key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        boolean changed = delegate.compareAndSet(key, null, value);
        if (changed) {
            size.incrementAndGet();
        }
        return changed;
    }

    @Override
    public boolean containsKey(long key) {
        return delegate.containsKey(key);
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

    public long size() {
        return size.get();
    }

    public boolean isEmpty() {
        return size.get() == 0L;
    }
}

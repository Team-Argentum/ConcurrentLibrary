package net.sixik.concurrent_library.collections.maps.long2reference;

/**
 * Fixed-range, direct-addressed {@code long -> reference} table.
 *
 * <p>This API is intentionally explicit: instantiate the concrete table class
 * directly. Use {@link ConcurrentLong2ReferenceMap} for sparse or moving key ranges.</p>
 */
public interface Long2ReferenceTable<V> {
    V get(long key);

    void put(long key, V value);

    void delete(long key);

    V remove(long key);

    boolean compareAndSet(long key, V expected, V update);

    boolean putIfAbsent(long key, V value);

    boolean containsKey(long key);

    long baseKey();

    long capacity();

    /**
     * Scans the backing storage and returns whether no slot was observed as present.
     * This is weakly consistent under concurrent mutation and must not be used as a
     * linearizable emptiness check.
     */
    boolean isEmptyByScan();

    /**
     * Scans the backing storage and counts slots observed as present.
     * This is weakly consistent under concurrent mutation and must not be used as a
     * linearizable size.
     */
    long countByScan();

}

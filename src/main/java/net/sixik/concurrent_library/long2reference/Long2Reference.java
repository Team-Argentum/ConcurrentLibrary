package net.sixik.concurrent_library.long2reference;

/**
 * Fixed-range, direct-addressed long to reference table.
 * Null is reserved as the absent marker in the base variants.
 */
public sealed interface Long2Reference<V>
        permits DenseChecked, PagedChecked, DenseUnchecked, PagedUnchecked,
        PaddedDenseChecked, NullableLong2Reference, CountingLong2Reference,
        StatefulLong2Reference, StrictDenseChecked, StrictPagedChecked,
        PlainDenseChecked, PlainPagedChecked {

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

    static <V> DenseChecked<V> dense(long baseKey, int capacity) {
        return new DenseChecked<>(baseKey, capacity);
    }

    static <V> Long2Reference<V> concurrent(long baseKey, long capacity) {
        return Long2ReferenceFactory.concurrent(baseKey, capacity);
    }

    static <V> PagedChecked<V> paged(long baseKey, long capacity) {
        return new PagedChecked<>(baseKey, capacity);
    }

    static <V> NullableLong2Reference<V> nullable(long baseKey, long capacity) {
        return new NullableLong2Reference<>(Long2ReferenceFactory.concurrent(baseKey, capacity));
    }

    static <V> CountingLong2Reference<V> counting(long baseKey, long capacity) {
        return new CountingLong2Reference<>(Long2ReferenceFactory.concurrent(baseKey, capacity));
    }

    static <V> StatefulLong2Reference<V> stateful(long baseKey, long capacity) {
        return new StatefulLong2Reference<>(Long2ReferenceFactory.concurrent(baseKey, capacity));
    }

    static <T extends Long2Reference<?>> Ref<T> ref(T table) {
        return Ref.of(table);
    }

    static Builder builder() {
        return new Builder();
    }

    final class Ref<T> {
        private static final java.lang.invoke.VarHandle CURRENT;

        static {
            try {
                CURRENT = java.lang.invoke.MethodHandles.lookup().findVarHandle(
                        Ref.class,
                        "current",
                        Object.class
                );
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private Object current;

        private Ref(T table) {
            if (table == null) {
                throw new NullPointerException();
            }
            this.current = table;
        }

        public static <T> Ref<T> of(T table) {
            return new Ref<>(table);
        }

        @SuppressWarnings("unchecked")
        public T get() {
            return (T) CURRENT.getAcquire(this);
        }

        public void set(T table) {
            if (table == null) {
                throw new NullPointerException();
            }
            CURRENT.setRelease(this, table);
        }
    }
}

package net.sixik.concurrent_library.long2int.singlethreaded;

import net.sixik.concurrent_library.long2int.Long2Int;
import net.sixik.concurrent_library.long2int.Long2IntBackend;
import net.sixik.concurrent_library.long2int.Long2IntLookup;
import net.sixik.concurrent_library.long2int.Long2IntMap;
import net.sixik.concurrent_library.long2int.Long2IntSingleThreadMap;
import net.sixik.concurrent_library.long2int.SingleThreadLong2IntBuilder;

/**
 * Facade for legacy non-thread-safe Long2Int variants.
 *
 * <p>The main library is optimized for concurrent use. These factories are
 * deliberately isolated in a {@code singlethreaded} package so they do not look
 * like part of the primary concurrent API. Use them only from one thread.</p>
 */
@Deprecated(forRemoval = false)
public final class Long2IntSingleThreaded {
    private Long2IntSingleThreaded() {
    }

    public static Long2IntLookup lookup(long[] keys, int[] values) {
        return builder(keys.length).loadFactor(0.82d).buildLookup(keys, values);
    }

    public static Long2IntLookup heapLookup(long[] keys, int[] values) {
        return builder(keys.length).backend(Long2IntBackend.HEAP).loadFactor(0.82d).buildLookup(keys, values);
    }

    public static Long2IntLookup directLookup(long[] keys, int[] values) {
        return builder(keys.length).backend(Long2IntBackend.DIRECT).loadFactor(0.82d).buildLookup(keys, values);
    }

    public static Long2IntLookup panamaLookup(long[] keys, int[] values) {
        return builder(keys.length).backend(Long2IntBackend.PANAMA).loadFactor(0.82d).buildLookup(keys, values);
    }

    public static Long2IntSingleThreadMap fixed(int expectedSize) {
        return builder(expectedSize).buildFixed();
    }

    public static Long2IntSingleThreadMap heapFixed(int expectedSize) {
        return builder(expectedSize).backend(Long2IntBackend.HEAP).buildFixed();
    }

    public static Long2IntSingleThreadMap directFixed(int expectedSize) {
        return builder(expectedSize).backend(Long2IntBackend.DIRECT).buildFixed();
    }

    public static Long2IntSingleThreadMap panamaFixed(int expectedSize) {
        return builder(expectedSize).backend(Long2IntBackend.PANAMA).buildFixed();
    }

    public static Long2IntMap mutable(int expectedSize) {
        return builder(expectedSize).loadFactor(0.65d).buildMutable();
    }

    public static Long2IntMap heapMutable(int expectedSize) {
        return builder(expectedSize).backend(Long2IntBackend.HEAP).loadFactor(0.65d).buildMutable();
    }

    public static Long2IntMap directMutable(int expectedSize) {
        return builder(expectedSize).backend(Long2IntBackend.DIRECT).loadFactor(0.65d).buildMutable();
    }

    public static Long2IntMap panamaMutable(int expectedSize) {
        return builder(expectedSize).backend(Long2IntBackend.PANAMA).loadFactor(0.65d).buildMutable();
    }

    @SuppressWarnings("deprecation")
    public static SingleThreadLong2IntBuilder builder(int expectedSize) {
        return Long2Int.singleThreadedBuilder(expectedSize);
    }
}

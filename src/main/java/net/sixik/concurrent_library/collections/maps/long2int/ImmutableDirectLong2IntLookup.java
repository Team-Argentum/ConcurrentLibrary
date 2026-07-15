package net.sixik.concurrent_library.collections.maps.long2int;

/** Immutable direct-memory {@code long -> int} lookup built from key/value arrays. */
public final class ImmutableDirectLong2IntLookup extends Long2IntSupport.ImmutableDirectLong2IntLookup {
    public ImmutableDirectLong2IntLookup(long[] keys, int[] values) {
        super(keys, values);
    }
}

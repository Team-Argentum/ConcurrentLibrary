package net.sixik.concurrent_library.collections.maps.long2int;

/** Fixed-capacity direct-memory {@code long -> int} append/update map. */
public final class FixedDirectLong2IntAppendMap extends Long2IntSupport.FixedDirectLong2IntAppendMap {
    public FixedDirectLong2IntAppendMap(int expectedSize) {
        super(expectedSize);
    }
}

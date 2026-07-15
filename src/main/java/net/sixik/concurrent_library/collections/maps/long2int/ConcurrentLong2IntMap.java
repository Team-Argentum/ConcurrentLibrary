package net.sixik.concurrent_library.collections.maps.long2int;

/**
 * Fast dynamic concurrent {@code long -> int} map backed by direct memory.
 *
 * <p>Retired tables are retained until {@link #close()}, so this variant favors
 * peak throughput when resize count is low or bounded.</p>
 */
public final class ConcurrentLong2IntMap extends Long2IntSupport.RetainedConcurrentMap {
    public ConcurrentLong2IntMap(int expectedSize) {
        super(expectedSize);
    }
}

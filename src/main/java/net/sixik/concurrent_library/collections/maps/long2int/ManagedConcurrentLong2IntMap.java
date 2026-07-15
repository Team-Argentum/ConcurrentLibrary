package net.sixik.concurrent_library.collections.maps.long2int;

/**
 * Dynamic concurrent {@code long -> int} map with hazard-protected reclamation.
 *
 * <p>Use this variant when repeated resize should not retain retired direct
 * memory until {@link #close()}.</p>
 */
public final class ManagedConcurrentLong2IntMap extends Long2IntSupport.ManagedConcurrentMap {
    public ManagedConcurrentLong2IntMap(int expectedSize) {
        super(expectedSize);
    }
}

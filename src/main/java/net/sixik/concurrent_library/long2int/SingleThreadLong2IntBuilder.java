package net.sixik.concurrent_library.long2int;

public final class SingleThreadLong2IntBuilder {
    static final int DEFAULT_HEAP_DIRECT_THRESHOLD = 2_000_000;

    final int expectedSize;
    Long2IntBackend backend = Long2IntBackend.AUTO;
    int heapDirectThreshold = DEFAULT_HEAP_DIRECT_THRESHOLD;
    double loadFactor = 0.75d;
    Long2IntHashing hashing = Long2IntHashing.FIBONACCI;
    int missingValue;
    boolean preTouch;
    boolean clearEntriesOnAllocate;
    boolean closeChecks = true;

    SingleThreadLong2IntBuilder(int expectedSize) {
        if (expectedSize < 0) {
            throw new IllegalArgumentException("expectedSize < 0");
        }
        this.expectedSize = expectedSize;
    }

    public SingleThreadLong2IntBuilder backend(Long2IntBackend backend) {
        if (backend == null) {
            throw new NullPointerException("backend");
        }
        this.backend = backend;
        return this;
    }

    public SingleThreadLong2IntBuilder heapDirectThreshold(int heapDirectThreshold) {
        if (heapDirectThreshold < 0) {
            throw new IllegalArgumentException("heapDirectThreshold < 0");
        }
        this.heapDirectThreshold = heapDirectThreshold;
        return this;
    }

    public SingleThreadLong2IntBuilder loadFactor(double loadFactor) {
        if (!(loadFactor > 0.0d && loadFactor < 1.0d)) {
            throw new IllegalArgumentException("loadFactor must be in (0, 1)");
        }
        this.loadFactor = loadFactor;
        return this;
    }

    public SingleThreadLong2IntBuilder hashing(Long2IntHashing hashing) {
        if (hashing == null) {
            throw new NullPointerException("hashing");
        }
        this.hashing = hashing;
        return this;
    }

    public SingleThreadLong2IntBuilder missingValue(int missingValue) {
        this.missingValue = missingValue;
        return this;
    }

    public SingleThreadLong2IntBuilder preTouch(boolean preTouch) {
        this.preTouch = preTouch;
        return this;
    }

    public SingleThreadLong2IntBuilder clearEntriesOnAllocate(boolean clearEntriesOnAllocate) {
        this.clearEntriesOnAllocate = clearEntriesOnAllocate;
        return this;
    }

    public SingleThreadLong2IntBuilder closeChecks(boolean closeChecks) {
        this.closeChecks = closeChecks;
        return this;
    }

    public Long2IntLookup buildLookup(long[] keys, int[] values) {
        return SingleThreadLong2Int.lookup(keys, values, this);
    }

    public Long2IntSingleThreadMap buildFixed() {
        return SingleThreadLong2Int.fixed(this);
    }

    public Long2IntMap buildMutable() {
        return SingleThreadLong2Int.mutable(this);
    }

    SingleThreadLong2IntBuilder copy() {
        SingleThreadLong2IntBuilder copy = new SingleThreadLong2IntBuilder(expectedSize);
        copy.backend = backend;
        copy.heapDirectThreshold = heapDirectThreshold;
        copy.loadFactor = loadFactor;
        copy.hashing = hashing;
        copy.missingValue = missingValue;
        copy.preTouch = preTouch;
        copy.clearEntriesOnAllocate = clearEntriesOnAllocate;
        copy.closeChecks = closeChecks;
        return copy;
    }
}

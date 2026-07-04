package net.sixik.concurrent_library.long2int;

public record Long2IntStats(
        long size,
        long exactSizeIfComputed,
        long capacity,
        int shards,
        long offHeapBytes,
        long retiredBytes,
        long deleteCount,
        long usedSlots,
        double loadFactor,
        boolean resizeInProgress,
        long resizeCount,
        long rebuildCount,
        long reservedWaitCount,
        long reservedParkCount,
        long failedCasCount
) {
}

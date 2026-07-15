package net.sixik.concurrent_library.collections.maps.chunk;

import net.sixik.concurrent_library.collections.maps.long2reference.ConcurrentLong2ReferenceMap;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Spatially paged concurrent {@code chunk -> long} map for primitive chunk metadata.
 *
 * <p>Each allocated region is a direct-addressed square of chunks. Slot state is
 * stored separately from the long value, so the configured missing value can also
 * be stored as a real payload.</p>
 */
public final class ChunkLong2LongMap {
    private static final int MIN_REGION_BITS = 1;
    private static final int MAX_REGION_BITS = 10;
    private static final int ABSENT = 0;
    private static final int LIVE = 1;
    private static final int RESERVED = 2;
    private static final int RESERVED_SPINS = 128;
    private static final int HOT_REGION_CACHE_SIZE = 256;
    private static final int PUT_RETRY = -1;
    private static final int PUT_UPDATED = 0;
    private static final int PUT_INSERTED = 1;

    private static final VarHandle LONG = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle INT = MethodHandles.arrayElementVarHandle(int[].class);
    private static final VarHandle CACHE = MethodHandles.arrayElementVarHandle(Object[].class);

    private final ConcurrentLong2ReferenceMap<Region> regions = new ConcurrentLong2ReferenceMap<>();
    private final Object[] hotRegions = new Object[HOT_REGION_CACHE_SIZE];
    private final LongAdder size = new LongAdder();
    private final LongAdder allocatedRegions = new LongAdder();
    private final long missingValue;
    private final int regionBits;
    private final int regionSize;
    private final int regionMask;
    private final int slotsPerRegion;

    public ChunkLong2LongMap() {
        this(ChunkKey.DEFAULT_REGION_BITS, Long.MIN_VALUE);
    }

    public ChunkLong2LongMap(long missingValue) {
        this(ChunkKey.DEFAULT_REGION_BITS, missingValue);
    }

    public ChunkLong2LongMap(int regionBits) {
        this(regionBits, Long.MIN_VALUE);
    }

    public ChunkLong2LongMap(int regionBits, long missingValue) {
        if (regionBits < MIN_REGION_BITS || regionBits > MAX_REGION_BITS) {
            throw new IllegalArgumentException("region bits must be between " + MIN_REGION_BITS + " and " + MAX_REGION_BITS);
        }
        this.regionBits = regionBits;
        this.regionSize = 1 << regionBits;
        this.regionMask = regionSize - 1;
        this.slotsPerRegion = regionSize * regionSize;
        this.missingValue = missingValue;
    }

    public long get(long chunkKey) {
        return get(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey));
    }

    public long get(int chunkX, int chunkZ) {
        return getOrDefault(chunkX, chunkZ, missingValue);
    }

    public long getOrDefault(long chunkKey, long defaultValue) {
        return getOrDefault(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey), defaultValue);
    }

    public long getOrDefault(int chunkX, int chunkZ, long defaultValue) {
        Region region = regionForRead(chunkX, chunkZ);
        if (region == null) {
            return defaultValue;
        }
        int offset = offset(chunkX, chunkZ);
        for (;;) {
            int state = (int) INT.getAcquire(region.states, offset);
            if (state == LIVE) {
                return (long) LONG.getAcquire(region.values, offset);
            }
            if (state == ABSENT || region.retired) {
                return defaultValue;
            }
            waitReserved();
        }
    }

    public boolean containsKey(long chunkKey) {
        return containsKey(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey));
    }

    public boolean containsKey(int chunkX, int chunkZ) {
        Region region = regionForRead(chunkX, chunkZ);
        if (region == null) {
            return false;
        }
        int offset = offset(chunkX, chunkZ);
        return (int) INT.getAcquire(region.states, offset) == LIVE;
    }

    public long put(long chunkKey, long value) {
        return put(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey), value);
    }

    public long put(int chunkX, int chunkZ, long value) {
        int offset = offset(chunkX, chunkZ);
        for (;;) {
            Region region = regionForWrite(chunkX, chunkZ);
            for (;;) {
                if (region.retired) {
                    break;
                }
                int state = (int) INT.getAcquire(region.states, offset);
                if (state == ABSENT) {
                    if (INT.compareAndSet(region.states, offset, ABSENT, RESERVED)) {
                        LONG.setRelease(region.values, offset, value);
                        INT.setRelease(region.states, offset, LIVE);
                        region.live.increment();
                        size.increment();
                        if (!region.retired) {
                            return missingValue;
                        }
                        if (INT.compareAndSet(region.states, offset, LIVE, RESERVED)) {
                            INT.setRelease(region.states, offset, ABSENT);
                            region.live.decrement();
                            size.decrement();
                        }
                        break;
                    }
                    continue;
                }
                if (state == LIVE) {
                    if (INT.compareAndSet(region.states, offset, LIVE, RESERVED)) {
                        long old = (long) LONG.getAcquire(region.values, offset);
                        LONG.setRelease(region.values, offset, value);
                        INT.setRelease(region.states, offset, LIVE);
                        if (!region.retired) {
                            return old;
                        }
                        break;
                    }
                    continue;
                }
                waitReserved();
            }
        }
    }

    public long putIfAbsent(long chunkKey, long value) {
        return putIfAbsent(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey), value);
    }

    public long putIfAbsent(int chunkX, int chunkZ, long value) {
        int offset = offset(chunkX, chunkZ);
        for (;;) {
            Region region = regionForWrite(chunkX, chunkZ);
            for (;;) {
                if (region.retired) {
                    break;
                }
                int state = (int) INT.getAcquire(region.states, offset);
                if (state == LIVE) {
                    return (long) LONG.getAcquire(region.values, offset);
                }
                if (state == ABSENT) {
                    if (INT.compareAndSet(region.states, offset, ABSENT, RESERVED)) {
                        LONG.setRelease(region.values, offset, value);
                        INT.setRelease(region.states, offset, LIVE);
                        region.live.increment();
                        size.increment();
                        if (!region.retired) {
                            return missingValue;
                        }
                        if (INT.compareAndSet(region.states, offset, LIVE, RESERVED)) {
                            INT.setRelease(region.states, offset, ABSENT);
                            region.live.decrement();
                            size.decrement();
                        }
                        break;
                    }
                    continue;
                }
                waitReserved();
            }
        }
    }

    public boolean setExisting(long chunkKey, long value) {
        return setExisting(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey), value);
    }

    public boolean setExisting(int chunkX, int chunkZ, long value) {
        int offset = offset(chunkX, chunkZ);
        Region region = regionForRead(chunkX, chunkZ);
        if (region == null || region.retired) {
            return false;
        }
        for (;;) {
            int state = (int) INT.getAcquire(region.states, offset);
            if (state == ABSENT || region.retired) {
                return false;
            }
            if (state == LIVE && INT.compareAndSet(region.states, offset, LIVE, RESERVED)) {
                LONG.setRelease(region.values, offset, value);
                INT.setRelease(region.states, offset, LIVE);
                return !region.retired;
            }
            waitReserved();
        }
    }

    public boolean compareAndSet(long chunkKey, long expected, long update) {
        return compareAndSet(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey), expected, update);
    }

    public boolean compareAndSet(int chunkX, int chunkZ, long expected, long update) {
        int offset = offset(chunkX, chunkZ);
        Region region = regionForRead(chunkX, chunkZ);
        if (region == null || region.retired) {
            return false;
        }
        for (;;) {
            int state = (int) INT.getAcquire(region.states, offset);
            if (state == ABSENT || region.retired) {
                return false;
            }
            if (state == LIVE && INT.compareAndSet(region.states, offset, LIVE, RESERVED)) {
                long old = (long) LONG.getAcquire(region.values, offset);
                boolean changed = old == expected;
                if (changed) {
                    LONG.setRelease(region.values, offset, update);
                }
                INT.setRelease(region.states, offset, LIVE);
                return changed && !region.retired;
            }
            waitReserved();
        }
    }

    public boolean addIfPresent(long chunkKey, long delta) {
        return addIfPresent(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey), delta);
    }

    public boolean addIfPresent(int chunkX, int chunkZ, long delta) {
        int offset = offset(chunkX, chunkZ);
        Region region = regionForRead(chunkX, chunkZ);
        if (region == null || region.retired) {
            return false;
        }
        for (;;) {
            int state = (int) INT.getAcquire(region.states, offset);
            if (state == ABSENT || region.retired) {
                return false;
            }
            if (state == LIVE && INT.compareAndSet(region.states, offset, LIVE, RESERVED)) {
                long old = (long) LONG.getAcquire(region.values, offset);
                LONG.setRelease(region.values, offset, old + delta);
                INT.setRelease(region.states, offset, LIVE);
                return !region.retired;
            }
            waitReserved();
        }
    }

    public long remove(long chunkKey) {
        return remove(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey));
    }

    public long remove(int chunkX, int chunkZ) {
        int offset = offset(chunkX, chunkZ);
        Region region = regionForRead(chunkX, chunkZ);
        if (region == null) {
            return missingValue;
        }
        for (;;) {
            int state = (int) INT.getAcquire(region.states, offset);
            if (state == ABSENT) {
                return missingValue;
            }
            if (state == LIVE && INT.compareAndSet(region.states, offset, LIVE, RESERVED)) {
                long old = (long) LONG.getAcquire(region.values, offset);
                INT.setRelease(region.states, offset, ABSENT);
                region.live.decrement();
                size.decrement();
                return old;
            }
            waitReserved();
        }
    }

    public long removeRegion(int regionX, int regionZ) {
        long regionKey = ChunkKey.pack(regionX, regionZ);
        Region region = regions.get(regionKey);
        if (region == null) {
            return 0L;
        }
        region.retired = true;
        forgetHotRegion(regionKey, region);
        regions.remove(regionKey, region);
        long removed = region.clear();
        if (removed != 0L) {
            size.add(-removed);
        }
        return removed;
    }

    public long removeChunkRegion(int chunkX, int chunkZ) {
        return removeRegion(chunkX >> regionBits, chunkZ >> regionBits);
    }

    public long removeColumn(int chunkX, int minChunkZ, int maxChunkZ) {
        return removeArea(chunkX, minChunkZ, chunkX, maxChunkZ);
    }

    public long removeRow(int minChunkX, int chunkZ, int maxChunkX) {
        return removeArea(minChunkX, chunkZ, maxChunkX, chunkZ);
    }

    public long removeArea(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            return 0L;
        }
        long removed = 0L;
        int minRegionX = minChunkX >> regionBits;
        int minRegionZ = minChunkZ >> regionBits;
        int maxRegionX = maxChunkX >> regionBits;
        int maxRegionZ = maxChunkZ >> regionBits;
        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
            int baseX = regionX << regionBits;
            int startX = Math.max(minChunkX, baseX);
            int endX = Math.min(maxChunkX, baseX + regionMask);
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                Region region = regionForRead(ChunkKey.pack(regionX, regionZ));
                if (region == null) {
                    continue;
                }
                int baseZ = regionZ << regionBits;
                int startZ = Math.max(minChunkZ, baseZ);
                int endZ = Math.min(maxChunkZ, baseZ + regionMask);
                removed += removeAreaFromRegion(region, startX, startZ, endX, endZ);
            }
        }
        return removed;
    }

    public long putColumn(int chunkX, int minChunkZ, int maxChunkZ, ChunkLongProvider values) {
        return putArea(chunkX, minChunkZ, chunkX, maxChunkZ, values);
    }

    public long putRow(int minChunkX, int chunkZ, int maxChunkX, ChunkLongProvider values) {
        return putArea(minChunkX, chunkZ, maxChunkX, chunkZ, values);
    }

    public long putArea(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, ChunkLongProvider values) {
        Objects.requireNonNull(values);
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            return 0L;
        }
        long inserted = 0L;
        int minRegionX = minChunkX >> regionBits;
        int minRegionZ = minChunkZ >> regionBits;
        int maxRegionX = maxChunkX >> regionBits;
        int maxRegionZ = maxChunkZ >> regionBits;
        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
            int baseX = regionX << regionBits;
            int startX = Math.max(minChunkX, baseX);
            int endX = Math.min(maxChunkX, baseX + regionMask);
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                int baseZ = regionZ << regionBits;
                int startZ = Math.max(minChunkZ, baseZ);
                int endZ = Math.min(maxChunkZ, baseZ + regionMask);
                inserted += putAreaIntoRegion(ChunkKey.pack(regionX, regionZ), startX, startZ, endX, endZ, values);
            }
        }
        return inserted;
    }

    public long putColumn(int chunkX, int minChunkZ, long[] values) {
        Objects.requireNonNull(values);
        return putColumn(chunkX, minChunkZ, values, 0, values.length);
    }

    public long putColumn(int chunkX, int minChunkZ, long[] values, int valuesOffset, int length) {
        Objects.requireNonNull(values);
        checkArrayRange(values.length, valuesOffset, length);
        return putAreaRowMajor(chunkX, minChunkZ, 1, length, values, valuesOffset);
    }

    public long putRow(int minChunkX, int chunkZ, long[] values) {
        Objects.requireNonNull(values);
        return putRow(minChunkX, chunkZ, values, 0, values.length);
    }

    public long putRow(int minChunkX, int chunkZ, long[] values, int valuesOffset, int length) {
        Objects.requireNonNull(values);
        checkArrayRange(values.length, valuesOffset, length);
        return putAreaRowMajor(minChunkX, chunkZ, length, 1, values, valuesOffset);
    }

    public long putAreaRowMajor(int minChunkX, int minChunkZ, int width, int height, long[] values) {
        Objects.requireNonNull(values);
        return putAreaRowMajor(minChunkX, minChunkZ, width, height, values, 0);
    }

    public long putAreaRowMajor(int minChunkX, int minChunkZ, int width, int height, long[] values, int valuesOffset) {
        Objects.requireNonNull(values);
        int cells = checkedCellCount(width, height);
        checkArrayRange(values.length, valuesOffset, cells);
        if (cells == 0) {
            return 0L;
        }
        int maxChunkX = Math.addExact(minChunkX, width - 1);
        int maxChunkZ = Math.addExact(minChunkZ, height - 1);
        long inserted = 0L;
        int minRegionX = minChunkX >> regionBits;
        int minRegionZ = minChunkZ >> regionBits;
        int maxRegionX = maxChunkX >> regionBits;
        int maxRegionZ = maxChunkZ >> regionBits;
        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
            int baseX = regionX << regionBits;
            int startX = Math.max(minChunkX, baseX);
            int endX = Math.min(maxChunkX, baseX + regionMask);
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                int baseZ = regionZ << regionBits;
                int startZ = Math.max(minChunkZ, baseZ);
                int endZ = Math.min(maxChunkZ, baseZ + regionMask);
                inserted += putAreaIntoRegionRowMajor(
                        ChunkKey.pack(regionX, regionZ),
                        minChunkX,
                        minChunkZ,
                        width,
                        values,
                        valuesOffset,
                        startX,
                        startZ,
                        endX,
                        endZ
                );
            }
        }
        return inserted;
    }

    public void clear() {
        regions.forEach((regionKey, region) -> {
            region.retired = true;
            forgetHotRegion(regionKey, region);
            regions.remove(regionKey, region);
            long removed = region.clear();
            if (removed != 0L) {
                size.add(-removed);
            }
        });
    }

    public void forEach(ChunkLongConsumer action) {
        Objects.requireNonNull(action);
        regions.forEach((regionKey, region) -> {
            int regionX = ChunkKey.x(regionKey);
            int regionZ = ChunkKey.z(regionKey);
            int baseX = regionX << regionBits;
            int baseZ = regionZ << regionBits;
            for (int offset = 0; offset < region.values.length; offset++) {
                if ((int) INT.getAcquire(region.states, offset) == LIVE) {
                    int chunkX = baseX + (offset & regionMask);
                    int chunkZ = baseZ + (offset >>> regionBits);
                    long value = (long) LONG.getAcquire(region.values, offset);
                    action.accept(chunkX, chunkZ, value);
                }
            }
        });
    }

    public void forEachInSquare(int centerChunkX, int centerChunkZ, int radius, ChunkLongConsumer action) {
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be non-negative");
        }
        forEachInArea(
                centerChunkX - radius,
                centerChunkZ - radius,
                centerChunkX + radius,
                centerChunkZ + radius,
                action
        );
    }

    public void forEachInArea(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, ChunkLongConsumer action) {
        Objects.requireNonNull(action);
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            return;
        }
        int minRegionX = minChunkX >> regionBits;
        int minRegionZ = minChunkZ >> regionBits;
        int maxRegionX = maxChunkX >> regionBits;
        int maxRegionZ = maxChunkZ >> regionBits;
        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
            int baseX = regionX << regionBits;
            int startX = Math.max(minChunkX, baseX);
            int endX = Math.min(maxChunkX, baseX + regionMask);
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                Region region = regionForRead(ChunkKey.pack(regionX, regionZ));
                if (region == null) {
                    continue;
                }
                int baseZ = regionZ << regionBits;
                int startZ = Math.max(minChunkZ, baseZ);
                int endZ = Math.min(maxChunkZ, baseZ + regionMask);
                for (int z = startZ; z <= endZ; z++) {
                    int rowOffset = (z & regionMask) << regionBits;
                    for (int x = startX; x <= endX; x++) {
                        int offset = rowOffset | (x & regionMask);
                        if ((int) INT.getAcquire(region.states, offset) == LIVE) {
                            action.accept(x, z, (long) LONG.getAcquire(region.values, offset));
                        }
                    }
                }
            }
        }
    }

    public long mappingCount() {
        return Math.max(0L, size.sum());
    }

    public int size() {
        long count = mappingCount();
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    public boolean isEmpty() {
        return mappingCount() == 0L;
    }

    public long missingValue() {
        return missingValue;
    }

    public int regionBits() {
        return regionBits;
    }

    public int regionSize() {
        return regionSize;
    }

    public ChunkLong2LongMapStats stats() {
        long currentRegions = regions.mappingCount();
        long estimatedRegionBytes = currentRegions * (long) slotsPerRegion * (Long.BYTES + Integer.BYTES);
        return new ChunkLong2LongMapStats(
                mappingCount(),
                currentRegions,
                allocatedRegions.sum(),
                regionBits,
                regionSize,
                slotsPerRegion,
                estimatedRegionBytes
        );
    }

    private Region regionForRead(int chunkX, int chunkZ) {
        return regionForRead(ChunkKey.regionKey(chunkX, chunkZ, regionBits));
    }

    private Region regionForRead(long regionKey) {
        Region cached = cachedRegion(regionKey);
        if (cached != null) {
            return cached;
        }
        Region region = regions.get(regionKey);
        if (region == null || region.retired) {
            return null;
        }
        rememberHotRegion(region);
        return region;
    }

    private Region regionForWrite(int chunkX, int chunkZ) {
        return regionForWrite(ChunkKey.regionKey(chunkX, chunkZ, regionBits));
    }

    private Region regionForWrite(long regionKey) {
        Region cached = cachedRegion(regionKey);
        if (cached != null) {
            return cached;
        }
        for (;;) {
            Region region = regions.get(regionKey);
            if (region != null && !region.retired) {
                rememberHotRegion(region);
                return region;
            }
            Region created = new Region(regionKey, slotsPerRegion);
            if (region == null) {
                Region raced = regions.putIfAbsent(regionKey, created);
                if (raced == null) {
                    allocatedRegions.increment();
                    rememberHotRegion(created);
                    return created;
                }
            } else if (regions.replace(regionKey, region, created)) {
                allocatedRegions.increment();
                rememberHotRegion(created);
                return created;
            }
        }
    }

    private long removeAreaFromRegion(Region region, int startX, int startZ, int endX, int endZ) {
        long removed = 0L;
        for (int z = startZ; z <= endZ; z++) {
            int rowOffset = (z & regionMask) << regionBits;
            for (int x = startX; x <= endX; x++) {
                int offset = rowOffset | (x & regionMask);
                for (;;) {
                    int state = (int) INT.getAcquire(region.states, offset);
                    if (state == ABSENT) {
                        break;
                    }
                    if (state == LIVE && INT.compareAndSet(region.states, offset, LIVE, RESERVED)) {
                        INT.setRelease(region.states, offset, ABSENT);
                        region.live.decrement();
                        size.decrement();
                        removed++;
                        break;
                    }
                    waitReserved();
                }
            }
        }
        return removed;
    }

    private long putAreaIntoRegion(long regionKey, int startX, int startZ, int endX, int endZ, ChunkLongProvider values) {
        for (;;) {
            long inserted = 0L;
            Region region = regionForWrite(regionKey);
            boolean retry = false;
            for (int z = startZ; z <= endZ && !retry; z++) {
                int rowOffset = (z & regionMask) << regionBits;
                for (int x = startX; x <= endX; x++) {
                    int result = putIntoRegion(region, rowOffset | (x & regionMask), values.get(x, z));
                    if (result == PUT_RETRY) {
                        retry = true;
                        break;
                    }
                    if (result == PUT_INSERTED) {
                        inserted++;
                    }
                }
            }
            if (!retry) {
                return inserted;
            }
        }
    }

    private long putAreaIntoRegionRowMajor(
            long regionKey,
            int minChunkX,
            int minChunkZ,
            int width,
            long[] values,
            int valuesOffset,
            int startX,
            int startZ,
            int endX,
            int endZ
    ) {
        for (;;) {
            long inserted = 0L;
            Region region = regionForWrite(regionKey);
            boolean retry = false;
            for (int z = startZ; z <= endZ && !retry; z++) {
                int rowOffset = (z & regionMask) << regionBits;
                int valueIndex = valuesOffset + (z - minChunkZ) * width + (startX - minChunkX);
                for (int x = startX; x <= endX; x++) {
                    int result = putIntoRegion(region, rowOffset | (x & regionMask), values[valueIndex++]);
                    if (result == PUT_RETRY) {
                        retry = true;
                        break;
                    }
                    if (result == PUT_INSERTED) {
                        inserted++;
                    }
                }
            }
            if (!retry) {
                return inserted;
            }
        }
    }

    private int putIntoRegion(Region region, int offset, long value) {
        for (;;) {
            if (region.retired) {
                return PUT_RETRY;
            }
            int state = (int) INT.getAcquire(region.states, offset);
            if (state == ABSENT) {
                if (INT.compareAndSet(region.states, offset, ABSENT, RESERVED)) {
                    LONG.setRelease(region.values, offset, value);
                    INT.setRelease(region.states, offset, LIVE);
                    region.live.increment();
                    size.increment();
                    if (!region.retired) {
                        return PUT_INSERTED;
                    }
                    if (INT.compareAndSet(region.states, offset, LIVE, RESERVED)) {
                        INT.setRelease(region.states, offset, ABSENT);
                        region.live.decrement();
                        size.decrement();
                    }
                    return PUT_RETRY;
                }
                continue;
            }
            if (state == LIVE) {
                if (INT.compareAndSet(region.states, offset, LIVE, RESERVED)) {
                    LONG.setRelease(region.values, offset, value);
                    INT.setRelease(region.states, offset, LIVE);
                    return region.retired ? PUT_RETRY : PUT_UPDATED;
                }
                continue;
            }
            waitReserved();
        }
    }

    private Region cachedRegion(long regionKey) {
        Region region = (Region) CACHE.getOpaque(hotRegions, hotRegionCacheIndex(regionKey));
        if (region != null && region.regionKey == regionKey && !region.retired) {
            return region;
        }
        return null;
    }

    private void rememberHotRegion(Region region) {
        CACHE.setOpaque(hotRegions, hotRegionCacheIndex(region.regionKey), region);
    }

    private void forgetHotRegion(long regionKey, Region region) {
        int index = hotRegionCacheIndex(regionKey);
        if (CACHE.getOpaque(hotRegions, index) == region) {
            CACHE.setOpaque(hotRegions, index, null);
        }
    }

    private static int hotRegionCacheIndex(long regionKey) {
        long mixed = regionKey * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 32;
        return (int) mixed & (HOT_REGION_CACHE_SIZE - 1);
    }

    private int offset(int chunkX, int chunkZ) {
        return ChunkKey.offset(chunkX, chunkZ, regionBits, regionMask);
    }

    private static int checkedCellCount(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("width and height must be non-negative");
        }
        long cells = (long) width * height;
        if (cells > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("area is too large");
        }
        return (int) cells;
    }

    private static void checkArrayRange(int arrayLength, int offset, int length) {
        if (offset < 0 || length < 0 || offset > arrayLength - length) {
            throw new IndexOutOfBoundsException("offset=" + offset + ", length=" + length + ", array length=" + arrayLength);
        }
    }

    private static void waitReserved() {
        for (int i = 0; i < RESERVED_SPINS; i++) {
            Thread.onSpinWait();
        }
        Thread.yield();
    }

    public record ChunkLong2LongMapStats(
            long mappingCount,
            long currentRegions,
            long allocatedRegions,
            int regionBits,
            int regionSize,
            int slotsPerRegion,
            long estimatedRegionBytes
    ) {
    }

    private static final class Region {
        final long regionKey;
        final long[] values;
        final int[] states;
        final LongAdder live = new LongAdder();
        volatile boolean retired;

        Region(long regionKey, int slots) {
            this.regionKey = regionKey;
            this.values = new long[slots];
            this.states = new int[slots];
        }

        long clear() {
            long removed = 0L;
            for (int i = 0; i < states.length; i++) {
                for (;;) {
                    int state = (int) INT.getAcquire(states, i);
                    if (state == ABSENT) {
                        break;
                    }
                    if (state == LIVE && INT.compareAndSet(states, i, LIVE, RESERVED)) {
                        INT.setRelease(states, i, ABSENT);
                        removed++;
                        break;
                    }
                    waitReserved();
                }
            }
            if (removed != 0L) {
                live.add(-removed);
            }
            return removed;
        }
    }
}

package net.sixik.concurrent_library.collections.maps.chunk;

import net.sixik.concurrent_library.collections.maps.long2reference.ConcurrentLong2ReferenceMap;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Spatially paged concurrent {@code chunk -> reference} map.
 *
 * <p>Keys use Minecraft's packed chunk coordinate layout. The directory is keyed
 * by chunk region, and each region stores a direct-addressed square of chunks.
 * This avoids hashing every chunk inside clustered workloads such as player view
 * distance updates, region unloads, and neighboring chunk scans.</p>
 */
public final class ChunkLong2ReferenceMap<V> {
    private static final int MIN_REGION_BITS = 1;
    private static final int MAX_REGION_BITS = 10;
    private static final int HOT_REGION_CACHE_SIZE = 256;
    private static final int PUT_RETRY = -1;
    private static final int PUT_UPDATED = 0;
    private static final int PUT_INSERTED = 1;
    private static final VarHandle REF = MethodHandles.arrayElementVarHandle(Object[].class);
    private static final VarHandle CACHE = MethodHandles.arrayElementVarHandle(Object[].class);

    private final ConcurrentLong2ReferenceMap<Region<V>> regions = new ConcurrentLong2ReferenceMap<>();
    private final Object[] hotRegions = new Object[HOT_REGION_CACHE_SIZE];
    private final LongAdder size = new LongAdder();
    private final LongAdder allocatedRegions = new LongAdder();
    private final int regionBits;
    private final int regionSize;
    private final int regionMask;
    private final int slotsPerRegion;

    public ChunkLong2ReferenceMap() {
        this(ChunkKey.DEFAULT_REGION_BITS);
    }

    public ChunkLong2ReferenceMap(int regionBits) {
        if (regionBits < MIN_REGION_BITS || regionBits > MAX_REGION_BITS) {
            throw new IllegalArgumentException("region bits must be between " + MIN_REGION_BITS + " and " + MAX_REGION_BITS);
        }
        this.regionBits = regionBits;
        this.regionSize = 1 << regionBits;
        this.regionMask = regionSize - 1;
        this.slotsPerRegion = regionSize * regionSize;
    }

    public V get(long chunkKey) {
        return get(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey));
    }

    @SuppressWarnings("unchecked")
    public V get(int chunkX, int chunkZ) {
        Region<V> region = regionForRead(chunkX, chunkZ);
        if (region == null) {
            return null;
        }
        return (V) REF.getAcquire(region.values, offset(chunkX, chunkZ));
    }

    public V getOrDefault(long chunkKey, V defaultValue) {
        V value = get(chunkKey);
        return value == null ? defaultValue : value;
    }

    public V getOrDefault(int chunkX, int chunkZ, V defaultValue) {
        V value = get(chunkX, chunkZ);
        return value == null ? defaultValue : value;
    }

    public boolean containsKey(long chunkKey) {
        return get(chunkKey) != null;
    }

    public boolean containsKey(int chunkX, int chunkZ) {
        return get(chunkX, chunkZ) != null;
    }

    public V put(long chunkKey, V value) {
        return put(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey), value);
    }

    @SuppressWarnings("unchecked")
    public V put(int chunkX, int chunkZ, V value) {
        Objects.requireNonNull(value);
        int offset = offset(chunkX, chunkZ);
        for (;;) {
            Region<V> region = regionForWrite(chunkX, chunkZ);
            for (;;) {
                if (region.retired) {
                    break;
                }
                Object old = REF.getAcquire(region.values, offset);
                if (old != null) {
                    Object witness = REF.compareAndExchangeRelease(region.values, offset, old, value);
                    if (witness == old) {
                        if (!region.retired) {
                            return (V) old;
                        }
                        break;
                    }
                    continue;
                }
                if (REF.compareAndSet(region.values, offset, null, value)) {
                    region.live.increment();
                    size.increment();
                    if (!region.retired) {
                        return null;
                    }
                    if (REF.compareAndSet(region.values, offset, value, null)) {
                        region.live.decrement();
                        size.decrement();
                    }
                    break;
                }
            }
        }
    }

    public V putIfAbsent(long chunkKey, V value) {
        return putIfAbsent(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey), value);
    }

    @SuppressWarnings("unchecked")
    public V putIfAbsent(int chunkX, int chunkZ, V value) {
        Objects.requireNonNull(value);
        int offset = offset(chunkX, chunkZ);
        for (;;) {
            Region<V> region = regionForWrite(chunkX, chunkZ);
            for (;;) {
                if (region.retired) {
                    break;
                }
                Object old = REF.getAcquire(region.values, offset);
                if (old != null) {
                    return (V) old;
                }
                if (REF.compareAndSet(region.values, offset, null, value)) {
                    region.live.increment();
                    size.increment();
                    if (!region.retired) {
                        return null;
                    }
                    if (REF.compareAndSet(region.values, offset, value, null)) {
                        region.live.decrement();
                        size.decrement();
                    }
                    break;
                }
            }
        }
    }

    public boolean setExisting(long chunkKey, V value) {
        return setExisting(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey), value);
    }

    public boolean setExisting(int chunkX, int chunkZ, V value) {
        Objects.requireNonNull(value);
        Region<V> region = regionForRead(chunkX, chunkZ);
        if (region == null || region.retired) {
            return false;
        }
        int offset = offset(chunkX, chunkZ);
        if (REF.getAcquire(region.values, offset) == null || region.retired) {
            return false;
        }
        REF.setRelease(region.values, offset, value);
        return !region.retired;
    }

    public boolean replace(long chunkKey, V oldValue, V newValue) {
        return replace(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey), oldValue, newValue);
    }

    public boolean replace(int chunkX, int chunkZ, V oldValue, V newValue) {
        Objects.requireNonNull(oldValue);
        Objects.requireNonNull(newValue);
        Region<V> region = regionForRead(chunkX, chunkZ);
        if (region == null || region.retired) {
            return false;
        }
        int offset = offset(chunkX, chunkZ);
        for (;;) {
            if (region.retired) {
                return false;
            }
            Object old = REF.getAcquire(region.values, offset);
            if (old == null || !old.equals(oldValue)) {
                return false;
            }
            if (REF.compareAndSet(region.values, offset, old, newValue)) {
                return !region.retired;
            }
        }
    }

    public V remove(long chunkKey) {
        return remove(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey));
    }

    @SuppressWarnings("unchecked")
    public V remove(int chunkX, int chunkZ) {
        Region<V> region = regionForRead(chunkX, chunkZ);
        if (region == null) {
            return null;
        }
        int offset = offset(chunkX, chunkZ);
        for (;;) {
            Object old = REF.getAcquire(region.values, offset);
            if (old == null) {
                return null;
            }
            if (REF.compareAndSet(region.values, offset, old, null)) {
                region.live.decrement();
                size.decrement();
                return (V) old;
            }
        }
    }

    public long removeRegion(int regionX, int regionZ) {
        long regionKey = ChunkKey.pack(regionX, regionZ);
        Region<V> region = regions.get(regionKey);
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
                Region<V> region = regionForRead(ChunkKey.pack(regionX, regionZ));
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

    public long putColumn(int chunkX, int minChunkZ, int maxChunkZ, ChunkObjProvider<? extends V> values) {
        return putArea(chunkX, minChunkZ, chunkX, maxChunkZ, values);
    }

    public long putRow(int minChunkX, int chunkZ, int maxChunkX, ChunkObjProvider<? extends V> values) {
        return putArea(minChunkX, chunkZ, maxChunkX, chunkZ, values);
    }

    public long putArea(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, ChunkObjProvider<? extends V> values) {
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

    public long putColumn(int chunkX, int minChunkZ, V[] values) {
        Objects.requireNonNull(values);
        return putColumn(chunkX, minChunkZ, values, 0, values.length);
    }

    public long putColumn(int chunkX, int minChunkZ, V[] values, int valuesOffset, int length) {
        Objects.requireNonNull(values);
        checkArrayRange(values.length, valuesOffset, length);
        return putAreaRowMajor(chunkX, minChunkZ, 1, length, values, valuesOffset);
    }

    public long putRow(int minChunkX, int chunkZ, V[] values) {
        Objects.requireNonNull(values);
        return putRow(minChunkX, chunkZ, values, 0, values.length);
    }

    public long putRow(int minChunkX, int chunkZ, V[] values, int valuesOffset, int length) {
        Objects.requireNonNull(values);
        checkArrayRange(values.length, valuesOffset, length);
        return putAreaRowMajor(minChunkX, chunkZ, length, 1, values, valuesOffset);
    }

    public long putAreaRowMajor(int minChunkX, int minChunkZ, int width, int height, V[] values) {
        Objects.requireNonNull(values);
        return putAreaRowMajor(minChunkX, minChunkZ, width, height, values, 0);
    }

    public long putAreaRowMajor(int minChunkX, int minChunkZ, int width, int height, V[] values, int valuesOffset) {
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

    public void forEach(ChunkObjConsumer<? super V> action) {
        Objects.requireNonNull(action);
        regions.forEach((regionKey, region) -> {
            int regionX = ChunkKey.x(regionKey);
            int regionZ = ChunkKey.z(regionKey);
            int baseX = regionX << regionBits;
            int baseZ = regionZ << regionBits;
            for (int offset = 0; offset < region.values.length; offset++) {
                @SuppressWarnings("unchecked")
                V value = (V) REF.getAcquire(region.values, offset);
                if (value != null) {
                    int chunkX = baseX + (offset & regionMask);
                    int chunkZ = baseZ + (offset >>> regionBits);
                    action.accept(chunkX, chunkZ, value);
                }
            }
        });
    }

    public void forEachInSquare(int centerChunkX, int centerChunkZ, int radius, ChunkObjConsumer<? super V> action) {
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

    public void forEachInArea(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, ChunkObjConsumer<? super V> action) {
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
                Region<V> region = regionForRead(ChunkKey.pack(regionX, regionZ));
                if (region == null) {
                    continue;
                }
                int baseZ = regionZ << regionBits;
                int startZ = Math.max(minChunkZ, baseZ);
                int endZ = Math.min(maxChunkZ, baseZ + regionMask);
                for (int z = startZ; z <= endZ; z++) {
                    int rowOffset = (z & regionMask) << regionBits;
                    for (int x = startX; x <= endX; x++) {
                        @SuppressWarnings("unchecked")
                        V value = (V) REF.getAcquire(region.values, rowOffset | (x & regionMask));
                        if (value != null) {
                            action.accept(x, z, value);
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

    public int regionBits() {
        return regionBits;
    }

    public int regionSize() {
        return regionSize;
    }

    public ChunkLong2ReferenceMapStats stats() {
        long currentRegions = regions.mappingCount();
        long estimatedRegionBytes = currentRegions * (long) slotsPerRegion * referenceScale();
        return new ChunkLong2ReferenceMapStats(
                mappingCount(),
                currentRegions,
                allocatedRegions.sum(),
                regionBits,
                regionSize,
                slotsPerRegion,
                estimatedRegionBytes
        );
    }

    private Region<V> regionForRead(int chunkX, int chunkZ) {
        return regionForRead(ChunkKey.regionKey(chunkX, chunkZ, regionBits));
    }

    private Region<V> regionForRead(long regionKey) {
        Region<V> cached = cachedRegion(regionKey);
        if (cached != null) {
            return cached;
        }
        Region<V> region = regions.get(regionKey);
        if (region == null || region.retired) {
            return null;
        }
        rememberHotRegion(region);
        return region;
    }

    private Region<V> regionForWrite(int chunkX, int chunkZ) {
        return regionForWrite(ChunkKey.regionKey(chunkX, chunkZ, regionBits));
    }

    private Region<V> regionForWrite(long regionKey) {
        Region<V> cached = cachedRegion(regionKey);
        if (cached != null) {
            return cached;
        }
        for (;;) {
            Region<V> region = regions.get(regionKey);
            if (region != null && !region.retired) {
                rememberHotRegion(region);
                return region;
            }
            Region<V> created = new Region<>(regionKey, slotsPerRegion);
            if (region == null) {
                Region<V> raced = regions.putIfAbsent(regionKey, created);
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

    private long removeAreaFromRegion(Region<V> region, int startX, int startZ, int endX, int endZ) {
        long removed = 0L;
        for (int z = startZ; z <= endZ; z++) {
            int rowOffset = (z & regionMask) << regionBits;
            for (int x = startX; x <= endX; x++) {
                Object old = REF.getAndSet(region.values, rowOffset | (x & regionMask), null);
                if (old != null) {
                    region.live.decrement();
                    size.decrement();
                    removed++;
                }
            }
        }
        return removed;
    }

    private long putAreaIntoRegion(long regionKey, int startX, int startZ, int endX, int endZ, ChunkObjProvider<? extends V> values) {
        for (;;) {
            long inserted = 0L;
            Region<V> region = regionForWrite(regionKey);
            boolean retry = false;
            for (int z = startZ; z <= endZ && !retry; z++) {
                int rowOffset = (z & regionMask) << regionBits;
                for (int x = startX; x <= endX; x++) {
                    V value = Objects.requireNonNull(values.get(x, z));
                    int result = putIntoRegion(region, rowOffset | (x & regionMask), value);
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
            V[] values,
            int valuesOffset,
            int startX,
            int startZ,
            int endX,
            int endZ
    ) {
        for (;;) {
            long inserted = 0L;
            Region<V> region = regionForWrite(regionKey);
            boolean retry = false;
            for (int z = startZ; z <= endZ && !retry; z++) {
                int rowOffset = (z & regionMask) << regionBits;
                int valueIndex = valuesOffset + (z - minChunkZ) * width + (startX - minChunkX);
                for (int x = startX; x <= endX; x++) {
                    V value = Objects.requireNonNull(values[valueIndex++]);
                    int result = putIntoRegion(region, rowOffset | (x & regionMask), value);
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

    private int putIntoRegion(Region<V> region, int offset, V value) {
        for (;;) {
            if (region.retired) {
                return PUT_RETRY;
            }
            Object old = REF.getAcquire(region.values, offset);
            if (old != null) {
                Object witness = REF.compareAndExchangeRelease(region.values, offset, old, value);
                if (witness == old) {
                    return region.retired ? PUT_RETRY : PUT_UPDATED;
                }
                continue;
            }
            if (REF.compareAndSet(region.values, offset, null, value)) {
                region.live.increment();
                size.increment();
                if (!region.retired) {
                    return PUT_INSERTED;
                }
                if (REF.compareAndSet(region.values, offset, value, null)) {
                    region.live.decrement();
                    size.decrement();
                }
                return PUT_RETRY;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Region<V> cachedRegion(long regionKey) {
        Region<V> region = (Region<V>) CACHE.getOpaque(hotRegions, hotRegionCacheIndex(regionKey));
        if (region != null && region.regionKey == regionKey && !region.retired) {
            return region;
        }
        return null;
    }

    private void rememberHotRegion(Region<V> region) {
        CACHE.setOpaque(hotRegions, hotRegionCacheIndex(region.regionKey), region);
    }

    private void forgetHotRegion(long regionKey, Region<V> region) {
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

    private static int referenceScale() {
        String dataModel = System.getProperty("sun.arch.data.model");
        if ("32".equals(dataModel)) {
            return 4;
        }
        long maxMemory = Runtime.getRuntime().maxMemory();
        return maxMemory <= (32L << 30) ? 4 : 8;
    }

    public record ChunkLong2ReferenceMapStats(
            long mappingCount,
            long currentRegions,
            long allocatedRegions,
            int regionBits,
            int regionSize,
            int slotsPerRegion,
            long estimatedRegionBytes
    ) {
    }

    private static final class Region<V> {
        final long regionKey;
        final Object[] values;
        final LongAdder live = new LongAdder();
        volatile boolean retired;

        Region(long regionKey, int slots) {
            this.regionKey = regionKey;
            this.values = new Object[slots];
        }

        long clear() {
            long removed = 0L;
            for (int i = 0; i < values.length; i++) {
                Object old = REF.getAndSet(values, i, null);
                if (old != null) {
                    removed++;
                }
            }
            if (removed != 0L) {
                live.add(-removed);
            }
            return removed;
        }
    }
}

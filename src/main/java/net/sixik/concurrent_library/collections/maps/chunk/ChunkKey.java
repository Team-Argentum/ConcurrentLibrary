package net.sixik.concurrent_library.collections.maps.chunk;

/** Utility methods for Minecraft-style packed chunk coordinates. */
public final class ChunkKey {
    public static final int DEFAULT_REGION_BITS = 5;

    private ChunkKey() {
    }

    /** Packs coordinates using the same low/high 32-bit layout as ChunkPos.asLong(x, z). */
    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xffff_ffffL) | (((long) chunkZ & 0xffff_ffffL) << 32);
    }

    public static int x(long chunkKey) {
        return (int) chunkKey;
    }

    public static int z(long chunkKey) {
        return (int) (chunkKey >>> 32);
    }

    static long regionKey(int chunkX, int chunkZ, int regionBits) {
        return pack(chunkX >> regionBits, chunkZ >> regionBits);
    }

    static int offset(int chunkX, int chunkZ, int regionBits, int regionMask) {
        return ((chunkZ & regionMask) << regionBits) | (chunkX & regionMask);
    }
}

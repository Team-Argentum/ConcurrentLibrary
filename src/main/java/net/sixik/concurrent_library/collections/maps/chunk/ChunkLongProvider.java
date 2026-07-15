package net.sixik.concurrent_library.collections.maps.chunk;

@FunctionalInterface
public interface ChunkLongProvider {
    long get(int chunkX, int chunkZ);
}

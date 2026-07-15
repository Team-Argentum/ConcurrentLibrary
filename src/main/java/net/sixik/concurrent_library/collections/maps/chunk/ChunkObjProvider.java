package net.sixik.concurrent_library.collections.maps.chunk;

@FunctionalInterface
public interface ChunkObjProvider<V> {
    V get(int chunkX, int chunkZ);
}

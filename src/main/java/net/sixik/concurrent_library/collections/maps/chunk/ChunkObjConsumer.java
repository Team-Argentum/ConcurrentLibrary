package net.sixik.concurrent_library.collections.maps.chunk;

@FunctionalInterface
public interface ChunkObjConsumer<V> {
    void accept(int chunkX, int chunkZ, V value);
}

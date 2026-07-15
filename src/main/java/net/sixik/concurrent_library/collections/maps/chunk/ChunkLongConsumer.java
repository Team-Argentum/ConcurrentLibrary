package net.sixik.concurrent_library.collections.maps.chunk;

@FunctionalInterface
public interface ChunkLongConsumer {
    void accept(int chunkX, int chunkZ, long value);
}

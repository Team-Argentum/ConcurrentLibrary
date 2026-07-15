package net.sixik.concurrent_library.collections.maps.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkKeyTest {
    @Test
    void packRoundTripsPositiveAndNegativeChunkCoordinates() {
        int[] values = {0, 1, -1, 31, 32, -32, -33, Integer.MIN_VALUE, Integer.MAX_VALUE};
        for (int x : values) {
            for (int z : values) {
                long key = ChunkKey.pack(x, z);
                assertEquals(x, ChunkKey.x(key));
                assertEquals(z, ChunkKey.z(key));
            }
        }
    }
}

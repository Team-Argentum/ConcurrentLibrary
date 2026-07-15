package net.sixik.concurrent_library.collections.maps.long2int;

public interface Long2IntMap extends Long2IntAppendMap {
    boolean remove(long key);

    int removeAndGetOld(long key, int missingReturn);

    boolean resizeInProgress();

    void completeResize();
}

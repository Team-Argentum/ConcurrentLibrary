package net.sixik.concurrent_library.collections.maps.long2int;

public interface Long2IntAppendMap extends Long2IntLookup {
    boolean put(long key, int value);

    boolean putIfAbsent(long key, int value);

    boolean compareAndSet(long key, int expected, int update);

    boolean replace(long key, int expected, int update);

    boolean addIfPresent(long key, int delta);

    int getAndAddIfPresent(long key, int delta, int missingReturn);

    long capacity();
}

package net.sixik.concurrent_library.long2int;

public interface Long2IntLookup extends AutoCloseable {
    int get(long key);

    int getOrDefault(long key, int defaultValue);

    boolean containsKey(long key);

    long size();

    @Override
    void close();
}

package net.sixik.concurrent_library.pools;

public interface ObjectPool<T> {

    T createNew();

    void preInit(T value);

    void postInit(T value);

    T alloc();

    void release(T object);
}

package net.sixik.concurrent_library.collections.maps.long2int;

@FunctionalInterface
public interface LongIntConsumer {
    void accept(long key, int value);
}

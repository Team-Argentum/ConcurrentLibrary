package net.sixik.concurrent_library.long2int;

@FunctionalInterface
public interface LongIntConsumer {
    void accept(long key, int value);
}

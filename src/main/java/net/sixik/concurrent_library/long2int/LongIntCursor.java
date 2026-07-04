package net.sixik.concurrent_library.long2int;

public interface LongIntCursor {
    LongIntCursor reset();

    boolean next();

    long key();

    int value();
}

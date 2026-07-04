package net.sixik.concurrent_library.long2int;

public interface Long2IntSingleThreadMap extends Long2IntAppendMap {
    void forEach(LongIntConsumer consumer);

    LongIntCursor cursor();

    LongIntCursor cachedCursor();

    long backendBytes();

    Long2IntBackend backend();
}

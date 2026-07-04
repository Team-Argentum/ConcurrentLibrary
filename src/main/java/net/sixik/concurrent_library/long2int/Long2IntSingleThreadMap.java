package net.sixik.concurrent_library.long2int;

/**
 * Legacy single-threaded Long2Int map API.
 *
 * <p>Implementations are not thread-safe and are kept outside the primary
 * concurrent collection surface for completeness only.</p>
 */
@Deprecated(forRemoval = false)
public interface Long2IntSingleThreadMap extends Long2IntAppendMap {
    void forEach(LongIntConsumer consumer);

    LongIntCursor cursor();

    LongIntCursor cachedCursor();

    long backendBytes();

    Long2IntBackend backend();
}

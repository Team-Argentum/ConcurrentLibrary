package net.sixik.concurrent_library.long2int;

public interface Long2IntInspector {
    Long2IntStats stats();

    Long2IntHealth healthCheck();

    long exactSize();
}

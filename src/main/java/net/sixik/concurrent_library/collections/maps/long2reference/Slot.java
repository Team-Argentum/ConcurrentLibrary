package net.sixik.concurrent_library.collections.maps.long2reference;

public final class Slot<T extends Long2ReferenceTable<?>> {
    private final T owner;
    private final long slot;

    Slot(T owner, long slot) {
        this.owner = owner;
        this.slot = slot;
    }

    public T owner() {
        return owner;
    }

    public long slot() {
        return slot;
    }
}

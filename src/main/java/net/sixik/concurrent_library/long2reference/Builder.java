package net.sixik.concurrent_library.long2reference;

public final class Builder {
    private enum Layout {
        AUTO,
        DENSE,
        PAGED,
        PADDED
    }

    private enum Order {
        ACQUIRE_RELEASE,
        STRICT,
        PLAIN
    }

    private long baseKey;
    private long capacity = -1L;
    private Layout layout = Layout.AUTO;
    private Order order = Order.ACQUIRE_RELEASE;
    private boolean checked = true;
    private boolean nullable;
    private boolean counting;
    private boolean stateful;
    private boolean diagnostics;

    public Builder range(long baseKey, long capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException();
        }
        this.baseKey = baseKey;
        this.capacity = capacity;
        return this;
    }

    public Builder dense() {
        this.layout = Layout.DENSE;
        return this;
    }

    public Builder paged() {
        this.layout = Layout.PAGED;
        return this;
    }

    public Builder padded() {
        this.layout = Layout.PADDED;
        return this;
    }

    public Builder strict() {
        this.order = Order.STRICT;
        return this;
    }

    /**
     * Selects legacy plain-memory storage. The produced table is not thread-safe
     * and is intended only for single-threaded experiments.
     */
    @Deprecated(forRemoval = false)
    public Builder plain() {
        this.order = Order.PLAIN;
        return this;
    }

    public Builder acquireRelease() {
        this.order = Order.ACQUIRE_RELEASE;
        return this;
    }

    public Builder unchecked() {
        this.checked = false;
        return this;
    }

    public Builder checked() {
        this.checked = true;
        return this;
    }

    public Builder nullable() {
        this.nullable = true;
        return this;
    }

    public Builder counting() {
        this.counting = true;
        return this;
    }

    public Builder stateful() {
        this.stateful = true;
        return this;
    }

    public Builder diagnostics(boolean enabled) {
        this.diagnostics = enabled;
        return this;
    }

    public <V> Long2Reference<V> build() {
        Long2Reference<V> table = buildBase();
        if (stateful) {
            table = new StatefulLong2Reference<>(table);
        }
        if (counting) {
            table = new CountingLong2Reference<>(table);
        }
        if (nullable) {
            table = new NullableLong2Reference<>(table);
        }
        return table;
    }

    public <V> DenseChecked<V> buildDense() {
        requireRange();
        return new DenseChecked<>(baseKey, Math.toIntExact(capacity));
    }

    public <V> PagedChecked<V> buildPaged() {
        requireRange();
        return new PagedChecked<>(baseKey, capacity);
    }

    @SuppressWarnings("unchecked")
    private <V> Long2Reference<V> buildBase() {
        requireRange();
        if (!diagnostics) {
            Long2ReferenceDiagnostics.setSink(null);
        }
        return switch (layout) {
            case DENSE -> denseBase();
            case PAGED -> pagedBase();
            case PADDED -> new PaddedDenseChecked<>(baseKey, Math.toIntExact(capacity));
            case AUTO -> Long2ReferenceFactory.concurrent(baseKey, capacity);
        };
    }

    @SuppressWarnings("unchecked")
    private <V> Long2Reference<V> denseBase() {
        int denseCapacity = Math.toIntExact(capacity);
        if (!checked) {
            return new DenseUnchecked<>(baseKey, denseCapacity);
        }
        return switch (order) {
            case ACQUIRE_RELEASE -> new DenseChecked<>(baseKey, denseCapacity);
            case STRICT -> new StrictDenseChecked<>(baseKey, denseCapacity);
            case PLAIN -> new PlainDenseChecked<>(baseKey, denseCapacity);
        };
    }

    private <V> Long2Reference<V> pagedBase() {
        if (!checked) {
            return new PagedUnchecked<>(baseKey, capacity);
        }
        return switch (order) {
            case ACQUIRE_RELEASE -> new PagedChecked<>(baseKey, capacity);
            case STRICT -> new StrictPagedChecked<>(baseKey, capacity);
            case PLAIN -> new PlainPagedChecked<>(baseKey, capacity);
        };
    }

    private void requireRange() {
        if (capacity < 0) {
            throw new IllegalStateException("range(baseKey, capacity) must be configured before build");
        }
    }
}

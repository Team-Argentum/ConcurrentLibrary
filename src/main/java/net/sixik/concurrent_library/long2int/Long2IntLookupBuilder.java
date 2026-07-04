package net.sixik.concurrent_library.long2int;

public final class Long2IntLookupBuilder {
    private long[] keys;
    private int[] values;
    private int size;

    Long2IntLookupBuilder(int expectedSize) {
        int capacity = Math.max(1, expectedSize);
        this.keys = new long[capacity];
        this.values = new int[capacity];
    }

    public Long2IntLookupBuilder put(long key, int value) {
        ensureCapacity(size + 1);
        keys[size] = key;
        values[size] = value;
        size++;
        return this;
    }

    public Long2IntLookupBuilder putAll(long[] keys, int[] values) {
        if (keys.length != values.length) {
            throw new IllegalArgumentException("keys and values lengths differ");
        }
        ensureCapacity(size + keys.length);
        System.arraycopy(keys, 0, this.keys, size, keys.length);
        System.arraycopy(values, 0, this.values, size, values.length);
        size += keys.length;
        return this;
    }

    public Long2IntLookup build() {
        long[] buildKeys = new long[size];
        int[] buildValues = new int[size];
        System.arraycopy(keys, 0, buildKeys, 0, size);
        System.arraycopy(values, 0, buildValues, 0, size);
        return Long2Int.lookup(buildKeys, buildValues);
    }

    private void ensureCapacity(int required) {
        if (required <= keys.length) {
            return;
        }
        int next = Math.max(required, keys.length << 1);
        long[] nextKeys = new long[next];
        int[] nextValues = new int[next];
        System.arraycopy(keys, 0, nextKeys, 0, size);
        System.arraycopy(values, 0, nextValues, 0, size);
        keys = nextKeys;
        values = nextValues;
    }
}

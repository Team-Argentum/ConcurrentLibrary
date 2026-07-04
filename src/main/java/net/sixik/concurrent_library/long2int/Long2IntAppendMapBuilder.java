package net.sixik.concurrent_library.long2int;

public final class Long2IntAppendMapBuilder {
    private long[] keys;
    private int[] values;
    private int size;

    Long2IntAppendMapBuilder(int expectedSize) {
        int capacity = Math.max(1, expectedSize);
        this.keys = new long[capacity];
        this.values = new int[capacity];
    }

    public Long2IntAppendMapBuilder put(long key, int value) {
        ensureCapacity(size + 1);
        keys[size] = key;
        values[size] = value;
        size++;
        return this;
    }

    public Long2IntAppendMapBuilder putAll(long[] keys, int[] values) {
        if (keys.length != values.length) {
            throw new IllegalArgumentException("keys and values lengths differ");
        }
        ensureCapacity(size + keys.length);
        System.arraycopy(keys, 0, this.keys, size, keys.length);
        System.arraycopy(values, 0, this.values, size, values.length);
        size += keys.length;
        return this;
    }

    public Long2IntAppendMap build() {
        Long2IntAppendMap map = Long2Int.fixed(Math.max(1, size));
        for (int i = 0; i < size; i++) {
            map.put(keys[i], values[i]);
        }
        return map;
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

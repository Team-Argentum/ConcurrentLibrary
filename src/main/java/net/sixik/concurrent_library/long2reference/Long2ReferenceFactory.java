package net.sixik.concurrent_library.long2reference;

final class Long2ReferenceFactory {
    private Long2ReferenceFactory() {
    }

    static <V> Long2Reference<V> concurrent(long base, long capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException();
        }
        if (capacity <= Integer.MAX_VALUE && fitsDense(capacity)) {
            return new DenseChecked<>(base, (int) capacity);
        }
        return new PagedChecked<>(base, capacity);
    }

    static boolean fitsDense(long capacity) {
        int scale = referenceScale();
        long maxCapacity = Long.MAX_VALUE / scale;
        if (capacity > maxCapacity) {
            return false;
        }
        long bytes = capacity * scale;
        long maxMemory = Runtime.getRuntime().maxMemory();
        return bytes < (maxMemory >>> 1);
    }

    static int referenceScale() {
        String dataModel = System.getProperty("sun.arch.data.model");
        if ("32".equals(dataModel)) {
            return 4;
        }
        long maxMemory = Runtime.getRuntime().maxMemory();
        return maxMemory <= (32L << 30) ? 4 : 8;
    }
}

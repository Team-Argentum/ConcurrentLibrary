package net.sixik.concurrent_library.collections.maps.long2reference;

final class Long2ReferenceLayout {
    private Long2ReferenceLayout() {
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

package net.sixik.concurrent_library.collections.maps.long2reference;

import java.util.function.Consumer;

public final class Long2ReferenceDiagnostics {
    private static volatile Consumer<String> sink;

    private Long2ReferenceDiagnostics() {
    }

    public static void setSink(Consumer<String> sink) {
        Long2ReferenceDiagnostics.sink = sink;
    }

    static void diagnose(long base, long capacity, String layout, boolean tailWaste) {
        Consumer<String> target = sink;
        if (target == null) {
            return;
        }

        int scale = Long2ReferenceLayout.referenceScale();
        if (capacity <= Long.MAX_VALUE / scale) {
            long bytes = capacity * scale;
            if (bytes >= 256L * 1024L * 1024L) {
                target.accept("Long2Reference: table uses approximately "
                        + (bytes / (1024L * 1024L))
                        + " MB with reference scale " + scale
                        + ". For lower startup page faults consider -XX:+AlwaysPreTouch.");
            }
        }

        if (capacity >= 64L * 1024L * 1024L) {
            target.accept("Long2Reference: very large " + layout
                    + " table detected. For lower TLB miss rate consider -XX:+UseLargePages.");
        }

        int processors = Runtime.getRuntime().availableProcessors();
        if ("dense".equals(layout) && processors >= 16) {
            target.accept("Long2Reference: dense table on " + processors
                    + " CPU cores. If many threads write adjacent keys, consider padded layout.");
        }

        if (tailWaste) {
            target.accept("Long2Reference: paged capacity is not page-aligned. "
                    + "The final page may contain unused tail slots.");
        }
    }
}

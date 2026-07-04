package net.sixik.concurrent_library.long2reference;

final class PagedAccess {
    private PagedAccess() {
    }

    static Object[] page(PagedChecked<?> table, long slot) {
        return table.pageForRead(slot);
    }
}

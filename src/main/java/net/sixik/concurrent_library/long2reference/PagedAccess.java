package net.sixik.concurrent_library.long2reference;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

final class PagedAccess {
    private static final VarHandle PAGES;

    static {
        try {
            PAGES = MethodHandles.privateLookupIn(PagedChecked.class, MethodHandles.lookup())
                    .findVarHandle(PagedChecked.class, "pages", Object[][].class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private PagedAccess() {
    }

    static Object[] page(PagedChecked<?> table, long slot) {
        Object[][] pages = (Object[][]) PAGES.get(table);
        return pages[(int) (slot >>> PagedChecked.PAGE_BITS)];
    }
}

package net.sixik.concurrent_library.collections.maps.long2reference;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongFunction;

/**
 * Dynamic sparse {@code long -> reference} map with a direct-addressed hot range.
 * <p>
 * The authoritative storage is a lazily allocated radix page store covering the full
 * {@code long} key-space. {@link #resize(long, long)} only replaces the hot range
 * accelerator and never moves mappings.
 */
public final class ConcurrentLong2ReferenceMap<V> {
    private static final int DEFAULT_PAGE_BITS = 12;
    private static final int MIN_PAGE_BITS = 4;
    private static final int MAX_PAGE_BITS = 20;
    private static final int RADIX_BITS = 10;
    private static final int RADIX_SIZE = 1 << RADIX_BITS;
    private static final int RADIX_MASK = RADIX_SIZE - 1;
    private static final int RADIX_FULL_LEVELS = Long.SIZE / RADIX_BITS;
    private static final int RADIX_LOW_BITS = Long.SIZE - RADIX_FULL_LEVELS * RADIX_BITS;
    private static final int RADIX_LOW_SIZE = 1 << RADIX_LOW_BITS;
    private static final int RADIX_LEVELS = RADIX_FULL_LEVELS + 1;
    private static final int EAGER_PREFILL_PAGE_LIMIT = 16_384;

    private static final VarHandle HOT;
    private static final VarHandle REF = MethodHandles.arrayElementVarHandle(Object[].class);
    private static final VarHandle NODE = MethodHandles.arrayElementVarHandle(Object[].class);
    private static final VarHandle HOT_PAGE = MethodHandles.arrayElementVarHandle(Page[].class);
    private static final VarHandle CELL_PAGE;
    private static final VarHandle PAGE_LIVE;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            HOT = lookup.findVarHandle(ConcurrentLong2ReferenceMap.class, "hot", HotWindow.class);
            CELL_PAGE = lookup.findVarHandle(PageCell.class, "page", Page.class);
            PAGE_LIVE = lookup.findVarHandle(Page.class, "live", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final RadixPageStore store;
    private final LongAdder size = new LongAdder();
    private final int pageBits;
    private final int pageSize;
    private final int pageMask;
    private final boolean prefillHotWindow;
    private final boolean vacuumOnEmptyPage;
    private final boolean mapViewEnabled;
    private final ConcurrentMap<Long, V> mapView;

    private volatile HotWindow hot;

    public ConcurrentLong2ReferenceMap() {
        this(0L, 0L);
    }

    public ConcurrentLong2ReferenceMap(long hotBaseKey, long hotCapacity) {
        this(hotBaseKey, hotCapacity, DEFAULT_PAGE_BITS);
    }

    public ConcurrentLong2ReferenceMap(long hotBaseKey, long hotCapacity, int pageBits) {
        this(hotBaseKey, hotCapacity, pageBits, true, true, true);
    }

    public ConcurrentLong2ReferenceMap(
            long hotBaseKey,
            long hotCapacity,
            int pageBits,
            boolean prefillHotWindow,
            boolean vacuumOnEmptyPage,
            boolean mapViewEnabled
    ) {
        if (hotCapacity < 0) {
            throw new IllegalArgumentException("capacity must be non-negative");
        }
        Math.addExact(hotBaseKey, hotCapacity);
        if (pageBits < MIN_PAGE_BITS || pageBits > MAX_PAGE_BITS) {
            throw new IllegalArgumentException("page bits must be between " + MIN_PAGE_BITS + " and " + MAX_PAGE_BITS);
        }
        this.pageBits = pageBits;
        this.pageSize = 1 << pageBits;
        this.pageMask = pageSize - 1;
        this.prefillHotWindow = prefillHotWindow;
        this.vacuumOnEmptyPage = vacuumOnEmptyPage;
        this.mapViewEnabled = mapViewEnabled;
        this.store = new RadixPageStore();
        this.hot = buildHotWindow(hotBaseKey, hotCapacity);
        this.mapView = mapViewEnabled ? new MapView() : null;
    }

    @SuppressWarnings("unchecked")
    public V get(long key) {
        Page page = pageForRead(key);
        if (page == null) {
            return null;
        }
        return (V) REF.getAcquire(page.values, offset(key));
    }

    public V getOrDefault(long key, V defaultValue) {
        V value = get(key);
        return value == null ? defaultValue : value;
    }

    public boolean containsKey(long key) {
        return get(key) != null;
    }

    @SuppressWarnings("unchecked")
    public V put(long key, V value) {
        Objects.requireNonNull(value);
        int offset = offset(key);
        Page page = pageForRead(key);
        if (page != null) {
            Object old = REF.getAcquire(page.values, offset);
            if (old != null) {
                Object witness = REF.compareAndExchangeRelease(page.values, offset, old, value);
                if (witness == old) {
                    return (V) old;
                }
            }
        }

        PageCell cell = cellForWrite(key);

        for (;;) {
            page = livePageForWrite(key, cell);
            Object old = REF.getAcquire(page.values, offset);
            if (old != null) {
                Object witness = REF.compareAndExchangeRelease(page.values, offset, old, value);
                if (witness == old) {
                    return (V) old;
                }
                continue;
            }
            if (!reserveInsert(page)) {
                continue;
            }
            size.increment();
            if (REF.compareAndSet(page.values, offset, null, value)) {
                return null;
            }
            size.decrement();
            cancelInsertReservation(page);
        }
    }

    /**
     * Expert overwrite for keys that are already present.
     * <p>
     * This method is intentionally weaker than {@link #put(long, Object)}: it does not
     * return the previous value, does not insert absent keys, and does not update size
     * or page live counters. It is meant for hot paths where the caller owns the key
     * lifecycle and only needs to publish a new non-null reference for an existing key.
     *
     * @return {@code true} when an existing non-null slot was overwritten.
     */
    public boolean setExisting(long key, V value) {
        Objects.requireNonNull(value);
        Page page = pageForRead(key);
        if (page == null) {
            return false;
        }
        int offset = offset(key);
        if (REF.getAcquire(page.values, offset) == null) {
            return false;
        }
        REF.setRelease(page.values, offset, value);
        return true;
    }

    @SuppressWarnings("unchecked")
    public V putIfAbsent(long key, V value) {
        Objects.requireNonNull(value);
        PageCell cell = cellForWrite(key);

        for (;;) {
            Page page = livePageForWrite(key, cell);
            int offset = offset(key);
            Object old = REF.getAcquire(page.values, offset);
            if (old != null) {
                return (V) old;
            }
            if (!reserveInsert(page)) {
                continue;
            }
            size.increment();
            if (REF.compareAndSet(page.values, offset, null, value)) {
                return null;
            }
            size.decrement();
            cancelInsertReservation(page);
        }
    }

    @SuppressWarnings("unchecked")
    public V remove(long key) {
        PageCell cell = cellForRead(key);
        if (cell == null) {
            return null;
        }

        for (;;) {
            Page page = livePageForRead(cell);
            if (page == null) {
                return null;
            }
            int offset = offset(key);
            Object old = REF.getAcquire(page.values, offset);
            if (old == null) {
                return null;
            }
            if (REF.compareAndSet(page.values, offset, old, null)) {
                mappingRemoved(cell, page);
                return (V) old;
            }
        }
    }

    public boolean remove(long key, V value) {
        Objects.requireNonNull(value);
        PageCell cell = cellForRead(key);
        if (cell == null) {
            return false;
        }

        for (;;) {
            Page page = livePageForRead(cell);
            if (page == null) {
                return false;
            }
            int offset = offset(key);
            Object old = REF.getAcquire(page.values, offset);
            if (old == null || !old.equals(value)) {
                return false;
            }
            if (REF.compareAndSet(page.values, offset, old, null)) {
                mappingRemoved(cell, page);
                return true;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public V replace(long key, V value) {
        Objects.requireNonNull(value);
        PageCell cell = cellForRead(key);
        if (cell == null) {
            return null;
        }

        for (;;) {
            Page page = livePageForRead(cell);
            if (page == null) {
                return null;
            }
            int offset = offset(key);
            Object old = REF.getAcquire(page.values, offset);
            if (old == null) {
                return null;
            }
            if (REF.compareAndSet(page.values, offset, old, value)) {
                return (V) old;
            }
        }
    }

    public boolean replace(long key, V oldValue, V newValue) {
        Objects.requireNonNull(oldValue);
        Objects.requireNonNull(newValue);
        PageCell cell = cellForRead(key);
        if (cell == null) {
            return false;
        }

        for (;;) {
            Page page = livePageForRead(cell);
            if (page == null) {
                return false;
            }
            int offset = offset(key);
            Object old = REF.getAcquire(page.values, offset);
            if (old == null || !old.equals(oldValue)) {
                return false;
            }
            if (REF.compareAndSet(page.values, offset, old, newValue)) {
                return true;
            }
        }
    }

    public V computeIfAbsent(long key, LongFunction<? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        V old = get(key);
        if (old != null) {
            return old;
        }
        V next = mappingFunction.apply(key);
        if (next == null) {
            return null;
        }
        V raced = putIfAbsent(key, next);
        return raced == null ? next : raced;
    }

    public V computeIfPresent(long key, LongReferenceRemappingFunction<V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        for (;;) {
            V old = get(key);
            if (old == null) {
                return null;
            }
            V next = remappingFunction.apply(key, old);
            if (next == null) {
                if (remove(key, old)) {
                    return null;
                }
            } else if (replace(key, old, next)) {
                return next;
            }
        }
    }

    public V compute(long key, LongReferenceRemappingFunction<V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        for (;;) {
            V old = get(key);
            V next = remappingFunction.apply(key, old);
            if (next == null) {
                if (old == null || remove(key, old)) {
                    return null;
                }
            } else if (old == null) {
                V raced = putIfAbsent(key, next);
                if (raced == null) {
                    return next;
                }
            } else if (replace(key, old, next)) {
                return next;
            }
        }
    }

    public V merge(long key, V value, ReferenceMergeFunction<V> remappingFunction) {
        Objects.requireNonNull(value);
        Objects.requireNonNull(remappingFunction);
        for (;;) {
            V old = get(key);
            if (old == null) {
                V raced = putIfAbsent(key, value);
                if (raced == null) {
                    return value;
                }
            } else {
                V next = remappingFunction.apply(old, value);
                if (next == null) {
                    if (remove(key, old)) {
                        return null;
                    }
                } else if (replace(key, old, next)) {
                    return next;
                }
            }
        }
    }

    public long mappingCount() {
        return Math.max(0L, size.sum());
    }

    public int size() {
        long count = mappingCount();
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    public boolean isEmpty() {
        return mappingCount() == 0L;
    }

    public void clear() {
        long removed = store.clear();
        if (removed != 0L) {
            size.add(-removed);
        }
    }

    public void resize(long baseKey, long capacity) {
        HotWindow next = buildHotWindow(baseKey, capacity);
        HOT.setRelease(this, next);
    }

    public VacuumStats vacuum() {
        return store.vacuum();
    }

    public ConcurrentMap<Long, V> asMapView() {
        if (!mapViewEnabled) {
            throw new UnsupportedOperationException("map view is disabled");
        }
        return mapView;
    }

    public void forEach(LongObjConsumer<? super V> action) {
        Objects.requireNonNull(action);
        store.forEach(action);
    }

    public Iterator<Entry<V>> iterator() {
        return new EntryIterator();
    }

    public ConcurrentLong2ReferenceMapStats stats() {
        HotWindow window = hot;
        long hotCachedCells = 0L;
        for (int i = 0; i < window.pageCount; i++) {
            if (window.cellAt(i) != null) {
                hotCachedCells++;
            }
        }
        long allocatedPages = store.allocatedPages.sum();
        long retiredPages = store.retiredPages.sum();
        long radixNodes = store.radixNodes.sum();
        long estimatedBytes = allocatedPages * (long) pageSize * Long2ReferenceLayout.referenceScale()
                + radixNodes * (long) RADIX_SIZE * Long2ReferenceLayout.referenceScale()
                + window.pageCount * (long) Long2ReferenceLayout.referenceScale();
        return new ConcurrentLong2ReferenceMapStats(
                mappingCount(),
                allocatedPages,
                retiredPages,
                radixNodes,
                window.baseKey,
                window.capacity,
                window.pageCount,
                hotCachedCells,
                estimatedBytes
        );
    }

    private Page pageForRead(long key) {
        long pageIndex = pageIndex(key);
        HotWindow window = hot;
        Page page = window.pageForRead(pageIndex);
        if (page != null && (int) PAGE_LIVE.getAcquire(page) != Page.RETIRED) {
            return page;
        }
        PageCell cell = window.cellForRead(pageIndex);
        if (cell != null) {
            page = livePageForRead(cell);
            if (page != null) {
                window.cachePage(pageIndex, page);
            }
            return page;
        }
        cell = store.cellForRead(pageIndex);
        if (cell == null) {
            return null;
        }
        page = livePageForRead(cell);
        if (page != null) {
            window.cache(pageIndex, cell, page);
        }
        return page;
    }

    private PageCell cellForRead(long key) {
        long pageIndex = pageIndex(key);
        HotWindow window = hot;
        PageCell cell = window.cellForRead(pageIndex);
        if (cell != null) {
            return cell;
        }
        return store.cellForRead(pageIndex);
    }

    private PageCell cellForWrite(long key) {
        long pageIndex = pageIndex(key);
        HotWindow window = hot;
        PageCell cell = window.cellForRead(pageIndex);
        if (cell != null) {
            return cell;
        }
        cell = store.cellForWrite(pageIndex);
        window.cacheCell(pageIndex, cell);
        return cell;
    }

    private Page livePageForRead(PageCell cell) {
        Page page = (Page) CELL_PAGE.getAcquire(cell);
        if (page == null || (int) PAGE_LIVE.getAcquire(page) == Page.RETIRED) {
            return null;
        }
        return page;
    }

    private Page livePageForWrite(PageCell cell) {
        for (;;) {
            Page page = (Page) CELL_PAGE.getAcquire(cell);
            if (page != null && (int) PAGE_LIVE.getAcquire(page) != Page.RETIRED) {
                return page;
            }
            Page created = new Page(pageSize);
            if (CELL_PAGE.compareAndSet(cell, page, created)) {
                store.allocatedPages.increment();
                return created;
            }
        }
    }

    private Page livePageForWrite(long key, PageCell cell) {
        Page page = livePageForWrite(cell);
        hot.cachePage(pageIndex(key), page);
        return page;
    }

    private boolean reserveInsert(Page page) {
        for (;;) {
            int live = (int) PAGE_LIVE.getAcquire(page);
            if (live == Page.RETIRED) {
                return false;
            }
            if (live < 0) {
                throw new IllegalStateException("page live counter is corrupted");
            }
            if (PAGE_LIVE.compareAndSet(page, live, live + 1)) {
                return true;
            }
        }
    }

    private void cancelInsertReservation(Page page) {
        int live = (int) PAGE_LIVE.getAndAdd(page, -1);
        if (live <= 0) {
            throw new IllegalStateException("page live counter underflow");
        }
    }

    private void mappingRemoved(PageCell cell, Page page) {
        size.decrement();
        int after = (int) PAGE_LIVE.getAndAdd(page, -1) - 1;
        if (after < 0) {
            throw new IllegalStateException("page live counter underflow");
        }
        if (after == 0 && vacuumOnEmptyPage) {
            store.tryRetirePage(cell, page);
        }
    }

    private long pageIndex(long key) {
        return key >> pageBits;
    }

    private int offset(long key) {
        return (int) key & pageMask;
    }

    private HotWindow buildHotWindow(long baseKey, long capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must be non-negative");
        }
        if (capacity == 0L) {
            return new HotWindow(baseKey, capacity, baseKey >> pageBits, 0);
        }
        long limit = Math.addExact(baseKey, capacity);
        long firstPage = baseKey >> pageBits;
        long lastPageExclusive = ceilPageIndex(limit);
        long pageCountLong = lastPageExclusive - firstPage;
        int pageCount = Math.toIntExact(pageCountLong);
        HotWindow window = new HotWindow(baseKey, capacity, firstPage, pageCount);
        if (prefillHotWindow && pageCount <= EAGER_PREFILL_PAGE_LIMIT) {
            for (int i = 0; i < pageCount; i++) {
                PageCell cell = store.cellForRead(firstPage + i);
                if (cell != null) {
                    window.cells[i] = cell;
                }
            }
        }
        return window;
    }

    private long ceilPageIndex(long keyLimit) {
        if ((keyLimit & pageMask) == 0L) {
            return keyLimit >> pageBits;
        }
        return (keyLimit >> pageBits) + 1L;
    }

    @FunctionalInterface
    public interface LongReferenceRemappingFunction<V> {
        V apply(long key, V value);
    }

    @FunctionalInterface
    public interface ReferenceMergeFunction<V> {
        V apply(V oldValue, V newValue);
    }

    @FunctionalInterface
    public interface LongObjConsumer<V> {
        void accept(long key, V value);
    }

    public record Entry<V>(long key, V value) {
    }

    public record VacuumStats(
            long scannedCells,
            long retiredPages,
            long skippedLivePages,
            long skippedRacedPages
    ) {
    }

    public record ConcurrentLong2ReferenceMapStats(
            long mappingCount,
            long allocatedPages,
            long retiredPages,
            long radixNodes,
            long hotBaseKey,
            long hotCapacity,
            long hotPageCount,
            long hotCachedCells,
            long estimatedBytes
    ) {
    }

    private final class RadixPageStore {
        private final Node root = new Node();
        private final LongAdder allocatedPages = new LongAdder();
        private final LongAdder retiredPages = new LongAdder();
        private final LongAdder radixNodes = new LongAdder();

        private RadixPageStore() {
            radixNodes.increment();
        }

        PageCell cellForRead(long pageIndex) {
            Object cursor = root;
            long normalized = pageIndex ^ Long.MIN_VALUE;
            for (int level = 0; level < RADIX_LEVELS; level++) {
                int slot = radixSlot(normalized, level);
                Object next = NODE.getAcquire(((Node) cursor).children, slot);
                if (next == null) {
                    return null;
                }
                cursor = next;
            }
            return (PageCell) cursor;
        }

        PageCell cellForWrite(long pageIndex) {
            Node node = root;
            long normalized = pageIndex ^ Long.MIN_VALUE;
            for (int level = 0; level < RADIX_LEVELS - 1; level++) {
                int slot = radixSlot(normalized, level);
                Object next = NODE.getAcquire(node.children, slot);
                if (next == null) {
                    Node created = new Node(level + 1 == RADIX_LEVELS - 1 ? RADIX_LOW_SIZE : RADIX_SIZE);
                    if (NODE.compareAndSet(node.children, slot, null, created)) {
                        radixNodes.increment();
                        next = created;
                    } else {
                        next = NODE.getAcquire(node.children, slot);
                    }
                }
                node = (Node) next;
            }

            int slot = radixSlot(normalized, RADIX_LEVELS - 1);
            Object cell = NODE.getAcquire(node.children, slot);
            if (cell != null) {
                return (PageCell) cell;
            }
            PageCell created = new PageCell();
            if (NODE.compareAndSet(node.children, slot, null, created)) {
                return created;
            }
            return (PageCell) NODE.getAcquire(node.children, slot);
        }

        VacuumStats vacuum() {
            VacuumCounter counter = new VacuumCounter();
            scan(root, 0, 0L, counter, null);
            return new VacuumStats(counter.scannedCells, counter.retiredPages, counter.skippedLivePages, counter.skippedRacedPages);
        }

        long clear() {
            ClearCounter counter = new ClearCounter();
            scan(root, 0, 0L, counter, null);
            return counter.removedMappings;
        }

        void forEach(LongObjConsumer<? super V> action) {
            scan(root, 0, 0L, null, action);
        }

        boolean tryRetirePage(PageCell cell, Page page) {
            if (!PAGE_LIVE.compareAndSet(page, 0, Page.RETIRED)) {
                return false;
            }
            if (CELL_PAGE.compareAndSet(cell, page, null)) {
                retiredPages.increment();
                return true;
            }
            return false;
        }

        private void scan(Node node, int level, long prefix, ScanCounter counter, LongObjConsumer<? super V> action) {
            Object[] children = node.children;
            for (int i = 0; i < children.length; i++) {
                Object child = NODE.getAcquire(children, i);
                if (child == null) {
                    continue;
                }
                if (level == RADIX_LEVELS - 1) {
                    long normalizedPageIndex = (prefix << RADIX_LOW_BITS) | i;
                    long pageIndex = normalizedPageIndex ^ Long.MIN_VALUE;
                    scanCell((PageCell) child, pageIndex, counter, action);
                } else {
                    long nextPrefix = (prefix << RADIX_BITS) | i;
                    scan((Node) child, level + 1, nextPrefix, counter, action);
                }
            }
        }

        @SuppressWarnings("unchecked")
        private void scanCell(PageCell cell, long pageIndex, ScanCounter counter, LongObjConsumer<? super V> action) {
            if (counter != null) {
                counter.scannedCells++;
            }
            Page page = (Page) CELL_PAGE.getAcquire(cell);
            if (page == null) {
                return;
            }
            int live = (int) PAGE_LIVE.getAcquire(page);
            if (live == Page.RETIRED) {
                return;
            }
            if (counter instanceof VacuumCounter vacuumCounter) {
                if (live == 0) {
                    if (tryRetirePage(cell, page)) {
                        vacuumCounter.retiredPages++;
                    } else {
                        vacuumCounter.skippedRacedPages++;
                    }
                } else {
                    vacuumCounter.skippedLivePages++;
                }
                return;
            }
            if (counter instanceof ClearCounter clearCounter) {
                clearCounter.removedMappings += clearPage(cell, page);
                return;
            }
            if (action != null) {
                for (int offset = 0; offset < page.values.length; offset++) {
                    Object value = REF.getAcquire(page.values, offset);
                    if (value != null) {
                        action.accept((pageIndex << pageBits) + offset, (V) value);
                    }
                }
            }
        }

        private long clearPage(PageCell cell, Page page) {
            long removed = 0L;
            for (int i = 0; i < page.values.length; i++) {
                Object old = REF.getAndSet(page.values, i, null);
                if (old != null) {
                    removed++;
                }
            }
            if (removed != 0L) {
                int live = (int) PAGE_LIVE.getAndAdd(page, -(int) removed);
                if (live == Page.RETIRED || live < removed) {
                    throw new IllegalStateException("page live counter underflow");
                }
            }
            if ((int) PAGE_LIVE.getAcquire(page) == 0) {
                tryRetirePage(cell, page);
            }
            return removed;
        }

        private int radixSlot(long normalizedPageIndex, int level) {
            if (level == RADIX_LEVELS - 1) {
                return (int) normalizedPageIndex & (RADIX_LOW_SIZE - 1);
            }
            int shift = Long.SIZE - RADIX_BITS * (level + 1);
            return (int) (normalizedPageIndex >>> shift) & RADIX_MASK;
        }
    }

    private static final class Node {
        final Object[] children;

        Node() {
            this(RADIX_SIZE);
        }

        Node(int size) {
            this.children = new Object[size];
        }
    }

    private static final class PageCell {
        volatile Page page;
    }

    private static final class Page {
        static final int RETIRED = Integer.MIN_VALUE;

        final Object[] values;
        volatile int live;

        Page(int pageSize) {
            this.values = new Object[pageSize];
        }
    }

    private static final class HotWindow {
        final long baseKey;
        final long capacity;
        final long basePage;
        final int pageCount;
        final PageCell[] cells;
        final Page[] pages;

        HotWindow(long baseKey, long capacity, long basePage, int pageCount) {
            this.baseKey = baseKey;
            this.capacity = capacity;
            this.basePage = basePage;
            this.pageCount = pageCount;
            this.cells = new PageCell[pageCount];
            this.pages = new Page[pageCount];
        }

        Page pageForRead(long pageIndex) {
            long pageSlotLong = pageIndex - basePage;
            if (pageSlotLong < 0L || pageSlotLong >= pageCount) {
                return null;
            }
            int pageSlot = (int) pageSlotLong;
            return (Page) HOT_PAGE.getAcquire(pages, pageSlot);
        }

        PageCell cellForRead(long pageIndex) {
            long pageSlotLong = pageIndex - basePage;
            if (pageSlotLong < 0L || pageSlotLong >= pageCount) {
                return null;
            }
            int pageSlot = (int) pageSlotLong;
            return (PageCell) NODE.getAcquire(cells, pageSlot);
        }

        void cache(long pageIndex, PageCell cell, Page page) {
            long pageSlotLong = pageIndex - basePage;
            if (pageSlotLong >= 0L && pageSlotLong < pageCount) {
                int pageSlot = (int) pageSlotLong;
                NODE.compareAndSet(cells, pageSlot, null, cell);
                HOT_PAGE.setRelease(pages, pageSlot, page);
            }
        }

        void cacheCell(long pageIndex, PageCell cell) {
            long pageSlotLong = pageIndex - basePage;
            if (pageSlotLong >= 0L && pageSlotLong < pageCount) {
                int pageSlot = (int) pageSlotLong;
                NODE.compareAndSet(cells, pageSlot, null, cell);
            }
        }

        void cachePage(long pageIndex, Page page) {
            long pageSlotLong = pageIndex - basePage;
            if (pageSlotLong >= 0L && pageSlotLong < pageCount) {
                int pageSlot = (int) pageSlotLong;
                HOT_PAGE.setRelease(pages, pageSlot, page);
            }
        }

        PageCell cellAt(int pageSlot) {
            return (PageCell) NODE.getAcquire(cells, pageSlot);
        }
    }

    private static class ScanCounter {
        long scannedCells;
    }

    private static final class VacuumCounter extends ScanCounter {
        long retiredPages;
        long skippedLivePages;
        long skippedRacedPages;
    }

    private static final class ClearCounter extends ScanCounter {
        long removedMappings;
    }

    private static final class PageIndexSearch {
        final PageCell target;
        boolean found;
        long pageIndex;

        PageIndexSearch(PageCell target) {
            this.target = target;
        }
    }

    private final class EntryIterator implements Iterator<Entry<V>> {
        private final Iterator<Entry<V>> snapshot;

        EntryIterator() {
            java.util.ArrayList<Entry<V>> entries = new java.util.ArrayList<>();
            ConcurrentLong2ReferenceMap.this.forEach((key, value) -> entries.add(new Entry<>(key, value)));
            this.snapshot = entries.iterator();
        }

        @Override
        public boolean hasNext() {
            return snapshot.hasNext();
        }

        @Override
        public Entry<V> next() {
            return snapshot.next();
        }
    }

    private final class MapView extends AbstractMap<Long, V> implements ConcurrentMap<Long, V> {
        private final Set<Map.Entry<Long, V>> entrySet = new EntrySetView();

        @Override
        public V get(Object key) {
            return key instanceof Long longKey ? ConcurrentLong2ReferenceMap.this.get(longKey) : null;
        }

        @Override
        public boolean containsKey(Object key) {
            return key instanceof Long longKey && ConcurrentLong2ReferenceMap.this.containsKey(longKey);
        }

        @Override
        public V put(Long key, V value) {
            Objects.requireNonNull(key);
            return ConcurrentLong2ReferenceMap.this.put(key, value);
        }

        @Override
        public V remove(Object key) {
            return key instanceof Long longKey ? ConcurrentLong2ReferenceMap.this.remove(longKey) : null;
        }

        @Override
        public boolean remove(Object key, Object value) {
            if (!(key instanceof Long longKey) || value == null) {
                return false;
            }
            @SuppressWarnings("unchecked")
            V typed = (V) value;
            return ConcurrentLong2ReferenceMap.this.remove(longKey, typed);
        }

        @Override
        public boolean replace(Long key, V oldValue, V newValue) {
            Objects.requireNonNull(key);
            return ConcurrentLong2ReferenceMap.this.replace(key, oldValue, newValue);
        }

        @Override
        public V replace(Long key, V value) {
            Objects.requireNonNull(key);
            return ConcurrentLong2ReferenceMap.this.replace(key, value);
        }

        @Override
        public V putIfAbsent(Long key, V value) {
            Objects.requireNonNull(key);
            return ConcurrentLong2ReferenceMap.this.putIfAbsent(key, value);
        }

        @Override
        public void clear() {
            ConcurrentLong2ReferenceMap.this.clear();
        }

        @Override
        public int size() {
            return ConcurrentLong2ReferenceMap.this.size();
        }

        @Override
        public boolean isEmpty() {
            return ConcurrentLong2ReferenceMap.this.isEmpty();
        }

        @Override
        public Set<Map.Entry<Long, V>> entrySet() {
            return entrySet;
        }

        @Override
        public void forEach(BiConsumer<? super Long, ? super V> action) {
            Objects.requireNonNull(action);
            ConcurrentLong2ReferenceMap.this.forEach(action::accept);
        }

        @Override
        public V computeIfAbsent(Long key, Function<? super Long, ? extends V> mappingFunction) {
            Objects.requireNonNull(key);
            Objects.requireNonNull(mappingFunction);
            return ConcurrentLong2ReferenceMap.this.computeIfAbsent(key, mappingFunction::apply);
        }

        @Override
        public V computeIfPresent(Long key, BiFunction<? super Long, ? super V, ? extends V> remappingFunction) {
            Objects.requireNonNull(key);
            Objects.requireNonNull(remappingFunction);
            return ConcurrentLong2ReferenceMap.this.computeIfPresent(key, remappingFunction::apply);
        }

        @Override
        public V compute(Long key, BiFunction<? super Long, ? super V, ? extends V> remappingFunction) {
            Objects.requireNonNull(key);
            Objects.requireNonNull(remappingFunction);
            return ConcurrentLong2ReferenceMap.this.compute(key, remappingFunction::apply);
        }

        @Override
        public V merge(Long key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
            Objects.requireNonNull(key);
            Objects.requireNonNull(remappingFunction);
            return ConcurrentLong2ReferenceMap.this.merge(key, value, remappingFunction::apply);
        }
    }

    private final class EntrySetView extends AbstractSet<Map.Entry<Long, V>> {
        @Override
        public Iterator<Map.Entry<Long, V>> iterator() {
            Iterator<Entry<V>> iterator = ConcurrentLong2ReferenceMap.this.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public Map.Entry<Long, V> next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    Entry<V> entry = iterator.next();
                    return new AbstractMap.SimpleEntry<>(entry.key(), entry.value());
                }
            };
        }

        @Override
        public int size() {
            return ConcurrentLong2ReferenceMap.this.size();
        }

        @Override
        public void clear() {
            ConcurrentLong2ReferenceMap.this.clear();
        }
    }
}

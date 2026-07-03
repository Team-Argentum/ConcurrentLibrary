# Long2Reference Implementation Guide

This guide is for contributors who want to understand, maintain, or extend the `Long2Reference` collection.

The public API is small, but the implementation relies on several important invariants. Preserve those invariants when adding variants.

## Package Overview

Main package:

```text
net.sixik.concurrent_library.long2reference
```

Key files:

| File | Purpose |
|---|---|
| `Long2Reference.java` | Sealed public interface and static factories |
| `Long2ReferenceFactory.java` | Auto layout selection for `concurrent(...)` |
| `Builder.java` | Expert configuration surface |
| `DenseChecked.java` | Dense acquire/release checked implementation |
| `PagedChecked.java` | Paged acquire/release checked implementation |
| `DenseUnchecked.java` | Dense implementation with trusted/unchecked accessors |
| `PagedUnchecked.java` | Paged implementation with trusted/unchecked accessors |
| `StrictDenseChecked.java` | Dense volatile implementation |
| `StrictPagedChecked.java` | Paged volatile implementation |
| `PlainDenseChecked.java` | Dense plain-access implementation |
| `PlainPagedChecked.java` | Paged plain-access implementation |
| `PaddedDenseChecked.java` | Dense padded layout for false-sharing-sensitive writes |
| `NullableLong2Reference.java` | Wrapper for real `null` values |
| `CountingLong2Reference.java` | Wrapper with exact O(1) size counter |
| `StatefulLong2Reference.java` | Wrapper with `NEVER_SET`, `PRESENT`, `DELETED`, `PRESENT_NULL` states |
| `Slot.java` | Owner-checked slot token for dense tables |
| `Long2ReferenceDiagnostics.java` | Optional construction-time diagnostics |

## Core Invariants

Every implementation must preserve these rules:

- `capacity` is fixed after construction.
- Valid keys are `baseKey <= key < baseKey + capacity`.
- Checked variants throw `IndexOutOfBoundsException` for invalid keys.
- Base variants use `null` as the absent marker.
- Base variants reject `put(key, null)` and `putIfAbsent(key, null)`.
- Arrays and range fields must be final where possible.
- Constructors must not let `this` escape.
- Default concurrent variants publish references with acquire/release semantics.
- `delete` clears without returning the old value.
- `remove` gets and clears atomically where the variant is concurrent.
- Scan methods are O(capacity) and weakly consistent during concurrent writes.

## Sealed Interface

`Long2Reference` is a sealed interface. When adding a new implementation class, update the `permits` list.

```java
public sealed interface Long2Reference<V>
        permits DenseChecked, PagedChecked, DenseUnchecked, PagedUnchecked,
        PaddedDenseChecked, NullableLong2Reference, CountingLong2Reference,
        StatefulLong2Reference, StrictDenseChecked, StrictPagedChecked,
        PlainDenseChecked, PlainPagedChecked {
}
```

The sealed set keeps the implementation family explicit and helps avoid an accidental open-ended hierarchy.

## Dense Layout

Dense layout stores values in one `Object[]`.

```text
slot = key - baseKey
value = values[slot]
```

Relevant fields:

```java
private final long base;
private final long limit;
private final Object[] values;
```

Construction:

```java
DenseChecked(long base, int capacity) {
    if (capacity < 0) {
        throw new IllegalArgumentException();
    }

    this.base = base;
    this.limit = Math.addExact(base, capacity);
    this.values = new Object[capacity];
}
```

Important details:

- Dense capacity is an `int` because Java array indexes are `int`.
- `Math.addExact(base, capacity)` catches range overflow.
- The dense slot type is `int`.
- `trySlot(...)` returns `-1` instead of throwing.

Checked slot calculation:

```java
public int checkedSlot(long key) {
    if (key < base || key >= limit) {
        throw new IndexOutOfBoundsException();
    }
    return (int) (key - base);
}
```

No-throw slot calculation:

```java
public int trySlot(long key) {
    if (key < base || key >= limit) {
        return -1;
    }
    return (int) (key - base);
}
```

## Paged Layout

Paged layout stores values in `Object[][]` pages.

```text
slot      = key - baseKey
pageIndex = slot >>> PAGE_BITS
offset    = slot & PAGE_MASK
value     = pages[pageIndex][offset]
```

Current page constants:

```java
static final int PAGE_BITS = 16;
static final int PAGE_SIZE = 1 << PAGE_BITS;
static final int PAGE_MASK = PAGE_SIZE - 1;
```

This means each page contains `65,536` references.

Relevant fields:

```java
private final long base;
private final long capacity;
private final long limit;
private final Object[][] pages;
```

Page count calculation:

```java
long pageCountLong = (capacity >>> PAGE_BITS)
        + ((capacity & PAGE_MASK) == 0 ? 0 : 1);
int pageCount = Math.toIntExact(pageCountLong);
```

Important details:

- Paged capacity is a `long`.
- Paged slots are `long`.
- Each individual page is still indexed by `int`.
- The final page may contain unused tail slots when capacity is not page-aligned.
- Scan methods must only scan legal slots, not unused tail slots.

Tail-safe scan pattern:

```java
long remaining = capacity;
for (Object[] page : pages) {
    int limit = (int) Math.min(PAGE_SIZE, remaining);
    for (int i = 0; i < limit; i++) {
        // inspect legal slot
    }
    remaining -= limit;
}
```

## VarHandle Access Modes

The concurrent implementations use `VarHandle` array element access.

```java
private static final VarHandle REF = MethodHandles.arrayElementVarHandle(Object[].class);
```

Default acquire/release variant:

```java
@SuppressWarnings("unchecked")
public V get(long key) {
    return (V) REF.getAcquire(values, checkedSlot(key));
}

public void put(long key, V value) {
    if (value == null) {
        throw new NullPointerException();
    }
    REF.setRelease(values, checkedSlot(key), value);
}
```

Strict variant:

```java
REF.getVolatile(values, slot);
REF.setVolatile(values, slot, value);
```

Plain variant:

```java
values[slot]
values[slot] = value
```

Use the weakest access mode that satisfies the variant contract:

| Variant | Read | Write | Intended use |
|---|---|---|---|
| `DenseChecked`, `PagedChecked` | acquire | release | Default concurrent publication |
| `Strict*Checked` | volatile | volatile | Stronger visibility ordering |
| `Plain*Checked` | plain | plain | Single-threaded or externally synchronized code |

## Implementing `delete` and `remove`

Keep these operations distinct.

`delete` clears the slot without returning the old value:

```java
public void delete(long key) {
    REF.setRelease(values, checkedSlot(key), null);
}
```

`remove` returns the old value and clears atomically:

```java
@SuppressWarnings("unchecked")
public V remove(long key) {
    return (V) REF.getAndSet(values, checkedSlot(key), null);
}
```

Do not implement `delete` by calling `remove` in concurrent variants. That would add unnecessary read-modify-write cost to the common clear path.

## Implementing `putIfAbsent`

Base variants reject `null` values.

```java
public boolean putIfAbsent(long key, V value) {
    if (value == null) {
        throw new NullPointerException();
    }
    return REF.compareAndSet(values, checkedSlot(key), null, value);
}
```

For wrappers, make sure presence semantics match the wrapper policy. For example, `StatefulLong2Reference.putIfAbsent(...)` treats both `NEVER_SET` and `DELETED` as absent, but treats `PRESENT_NULL` as present.

## Nullable Wrapper

`NullableLong2Reference` maps real `null` values to a private sentinel object.

```java
private static final Object NULL_VALUE = new Object();
```

Write path:

```java
public void put(long key, V value) {
    delegate.put(key, mask(value));
}

private static Object mask(Object value) {
    return value == null ? NULL_VALUE : value;
}
```

Read path:

```java
Object value = delegate.get(key);
return value == NULL_VALUE ? null : (V) value;
```

Presence rule:

```java
public boolean containsKey(long key) {
    return delegate.get(key) != null;
}
```

That means a sentinel-masked `null` is present.

## Stateful Wrapper

`StatefulLong2Reference` maps special states to sentinels:

```java
private static final Object NULL_VALUE = new Object();
private static final Object DELETED_VALUE = new Object();
```

State mapping:

| Stored delegate value | Public state | Public `get` |
|---|---|---|
| `null` | `NEVER_SET` | `null` |
| `NULL_VALUE` | `PRESENT_NULL` | `null` |
| `DELETED_VALUE` | `DELETED` | `null` |
| any other object | `PRESENT` | that object |

Presence rule:

```java
public boolean containsKey(long key) {
    Object value = delegate.get(key);
    return value != null && value != DELETED_VALUE;
}
```

`PRESENT_NULL` is present. `DELETED` and `NEVER_SET` are absent.

## Counting Wrapper

`CountingLong2Reference` wraps a delegate and maintains an `AtomicLong` size.

```java
private final AtomicLong size = new AtomicLong();
```

The counter changes only on absent/present transitions:

```java
if (expected == null && update != null) {
    size.incrementAndGet();
} else if (expected != null && update == null) {
    size.decrementAndGet();
}
```

Important warning: current `CountingLong2Reference.compareAndSet(...)` trusts the caller's `expected` and `update` values to describe a real transition. Keep tests around counter behavior when changing wrapper composition order.

Builder composition currently applies wrappers in this order:

```java
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
```

This order matters. Do not reorder wrappers without tests for null, state, count, delete, remove, and compare-and-set behavior.

## Padded Dense Layout

`PaddedDenseChecked` reduces false sharing by spreading logical slots across cache-line-sized strides.

```java
this.stride = 64 / Long2ReferenceFactory.referenceScale();
this.values = new Object[Math.multiplyExact(capacity, stride)];
```

Physical addressing:

```java
private int physicalSlot(int logicalSlot) {
    return logicalSlot * stride;
}
```

Tradeoffs:

- Adjacent logical slots are less likely to share a cache line.
- Memory usage is multiplied by `stride`.
- Capacity must fit `int` because it is dense-based.
- `Math.multiplyExact` intentionally catches allocation-size overflow.

Only use padded layout when profiling or workload knowledge suggests adjacent writes from different threads are a real bottleneck.

## Factory Selection

`Long2Reference.concurrent(base, capacity)` delegates to `Long2ReferenceFactory.concurrent(...)`.

Current selection:

```java
static <V> Long2Reference<V> concurrent(long base, long capacity) {
    if (capacity < 0) {
        throw new IllegalArgumentException();
    }
    if (capacity <= Integer.MAX_VALUE && fitsDense(capacity)) {
        return new DenseChecked<>(base, (int) capacity);
    }
    return new PagedChecked<>(base, capacity);
}
```

`fitsDense(...)` estimates the memory required for one dense reference array and compares it with half of max heap.

```java
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
```

Contributor rules:

- Keep factory construction allocation-light.
- Do not route the default factory through `Builder`.
- Check `capacity <= Integer.MAX_VALUE` before constructing dense variants.
- Guard multiplication against overflow before estimating memory.

## Diagnostics

Diagnostics are advisory and must not affect correctness.

```java
Long2ReferenceDiagnostics.setSink(System.out::println);
```

`diagnose(...)` emits warnings only when a sink is configured.

Current warning categories:

- Estimated table memory at or above 256 MiB.
- Capacity at or above 64 Mi slots.
- Dense layout on at least 16 processors.
- Paged layout with unused final-page tail slots.

Keep diagnostics construction-time only. Do not add hot-path checks to `get`, `put`, `delete`, CAS, or slot APIs.

## Ref Wrapper

`Long2Reference.Ref<T>` safely publishes a replaceable table reference.

Constructor:

```java
private Ref(T table) {
    if (table == null) {
        throw new NullPointerException();
    }
    this.current = table;
}
```

Runtime reads/writes:

```java
public T get() {
    return (T) CURRENT.getAcquire(this);
}

public void set(T table) {
    if (table == null) {
        throw new NullPointerException();
    }
    CURRENT.setRelease(this, table);
}
```

The constructor uses plain assignment because the `Ref` instance should not be published before construction completes. Runtime replacement uses release/acquire.

## Adding A New Variant

Follow this checklist:

1. Decide whether the variant is a base implementation or a wrapper.
2. Add the class under `net.sixik.concurrent_library.long2reference`.
3. If it implements `Long2Reference` directly, add it to the sealed `permits` list.
4. Preserve range, null, delete/remove, and scan semantics.
5. Use final fields for range and storage.
6. Do not let `this` escape during construction.
7. Pick the correct memory access mode and document it.
8. Add builder/factory methods only when the variant is part of the public surface.
9. Add tests for range boundaries, null policy, delete/remove, CAS, scan methods, and wrapper composition.
10. Add documentation in this directory.

## Test Checklist

At minimum, tests should cover:

- First valid key, last valid key, and invalid keys on both sides.
- `put`, `get`, `containsKey`, `delete`, `remove`.
- `putIfAbsent` success and failure.
- `compareAndSet` success and failure.
- `countByScan` and `isEmptyByScan` after writes and deletes.
- Dense slot APIs.
- Paged slots across page boundaries.
- Nullable present-null behavior.
- Stateful `NEVER_SET`, `PRESENT`, `PRESENT_NULL`, and `DELETED` transitions.
- Counting `size()` after replacements, deletes, removes, and failed `putIfAbsent`.
- Builder variants: dense, paged, padded, strict, plain, nullable, counting, stateful.
- `Ref` replacement publication.

## Common Contributor Mistakes

- Treating `capacity` as growable.
- Forgetting that dense slots are `int` and paged slots are `long`.
- Scanning unused tail slots in the final paged page.
- Replacing `delete` with `remove` and making clear operations more expensive.
- Allowing `null` into base variants.
- Reordering wrapper composition without tests.
- Adding diagnostics to the hot path.
- Using plain variants in concurrent publication code.
- Adding a new implementation but forgetting the sealed `permits` list.
- Publishing a mutable table field directly instead of using `Long2Reference.Ref`.


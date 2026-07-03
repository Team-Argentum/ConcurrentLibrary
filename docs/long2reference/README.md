# Long2Reference Developer Guide

`Long2Reference<V>` is a fixed-range, direct-addressed collection for mapping `long` keys to Java object references.

It is designed for cases where your key range is known in advance and you want very fast indexed access without hashing, boxing, probing, node allocation, locks, or resize work in the hot path.

```text
long key -> normalized slot -> Object[] or Object[][] -> reference
```

## Documentation Map

- [README.md](README.md) - concepts, API overview, variant selection, and safe usage rules.
- [examples.md](examples.md) - copy-paste friendly examples for common application patterns.
- [implementation-guide.md](implementation-guide.md) - how the collection is implemented and how to add or modify variants safely.
- [pitfalls.md](pitfalls.md) - warnings, performance notes, and troubleshooting checklist.

## Mental Model

`Long2Reference` is closer to a specialized array-backed address table than to a general-purpose `Map`.

You choose a fixed key range:

```text
[baseKey, baseKey + capacity)
```

Each valid key is normalized into a slot:

```text
slot = key - baseKey
```

The slot is then used to load or store a reference in an array-backed layout:

```text
Dense layout: key -> int slot  -> Object[]
Paged layout: key -> long slot -> Object[][] pages
```

This is why the collection can be very fast: it does not need to hash the key, allocate a map node, compare keys, resolve collisions, or coordinate resizing.

## When To Use It

Use `Long2Reference` when all of these are true:

- Your keys are `long` values.
- The valid key range is known before the table is created.
- Keys can be normalized into `[0, capacity)` by subtracting `baseKey`.
- Most operations are point reads, writes, deletes, or compare-and-set operations.
- You want predictable O(1) access without hash-map behavior.
- `null` can mean "absent", or you explicitly choose the nullable/stateful wrapper.

Good fits:

- `instrumentId -> OrderBook`
- `entityId -> Component`
- `sessionId -> SessionState`
- `connectionId -> ConnectionContext`
- `workerId -> WorkerState`
- `nativeHandle -> JavaWrapper`

## When Not To Use It

Do not use `Long2Reference` when your keys are sparse across the whole `long` space or the range must grow dynamically like a normal map.

Prefer `ConcurrentHashMap`, fastutil maps, radix maps, or another general-purpose structure when:

- The key range is unknown or unbounded.
- The range is huge but only a tiny number of keys are ever present.
- You need map iteration semantics.
- You need a dynamic resize policy.
- You need arbitrary object keys.
- You need real `null` values but do not want wrapper overhead.

## Quick Start

### Dense Table For Compact Ranges

Use `dense` when the range fits in a single Java array and you want the fastest layout.

```java
import net.sixik.concurrent_library.long2reference.DenseChecked;
import net.sixik.concurrent_library.long2reference.Long2Reference;

record OrderBook(long instrumentId, String symbol) {}

DenseChecked<OrderBook> books = Long2Reference.dense(0L, 1_000_000);

books.put(42L, new OrderBook(42L, "EURUSD"));
OrderBook book = books.get(42L);
books.delete(42L);
```

Why the concrete `DenseChecked<OrderBook>` type? `dense(...)` returns a concrete final class, which is useful in very hot paths because the JIT has an easier call site.

### Auto Layout For Most Code

Use `concurrent` when you want the library to choose dense or paged layout for the configured capacity.

```java
import net.sixik.concurrent_library.long2reference.Long2Reference;

Long2Reference<OrderBook> books = Long2Reference.concurrent(0L, 1_000_000L);

books.put(42L, new OrderBook(42L, "EURUSD"));
OrderBook book = books.get(42L);
```

`Long2Reference.concurrent(baseKey, capacity)` chooses `DenseChecked` when the capacity fits a single array and the estimated array memory is reasonable. Otherwise it uses `PagedChecked`.

### Paged Table For Large Ranges

Use `paged` when the capacity can exceed what a single Java array can address.

```java
import net.sixik.concurrent_library.long2reference.Long2Reference;
import net.sixik.concurrent_library.long2reference.PagedChecked;

PagedChecked<OrderBook> books = Long2Reference.paged(0L, 10_000_000_000L);

books.put(8_000_000_000L, new OrderBook(8_000_000_000L, "LARGE"));
OrderBook book = books.get(8_000_000_000L);
```

Paged layout stores references in fixed-size pages of `65,536` slots. This keeps each individual Java array addressable by `int` while allowing the logical table capacity to be a `long`.

## Core API

The base interface exposes a small collection-like surface:

```java
V get(long key);

void put(long key, V value);

void delete(long key);

V remove(long key);

boolean compareAndSet(long key, V expected, V update);
boolean putIfAbsent(long key, V value);
boolean containsKey(long key);

long baseKey();
long capacity();

boolean isEmptyByScan();
long countByScan();
```

### Operation Summary

| Operation | Meaning | Notes |
|---|---|---|
| `get(key)` | Returns the current value or `null` if absent | Checks the key range in checked variants |
| `put(key, value)` | Publishes/replaces a value | Base variants reject `null` values |
| `delete(key)` | Clears a value without returning it | Prefer this when the old value is not needed |
| `remove(key)` | Atomically gets and clears the value | More expensive than `delete` |
| `compareAndSet(key, expected, update)` | Atomic conditional replacement | Useful for lock-free state transitions |
| `putIfAbsent(key, value)` | Stores only if the slot is absent | Base variants reject `null` values |
| `containsKey(key)` | Tests whether a slot is logically present | Wrapper variants define presence differently |
| `isEmptyByScan()` | Scans the whole table | O(capacity), weakly consistent during writes |
| `countByScan()` | Counts present entries by scanning | O(capacity), weakly consistent during writes |

## Range Rules

Every table has a fixed valid key range:

```text
baseKey <= key < baseKey + capacity
```

Example:

```java
Long2Reference<String> table = Long2Reference.concurrent(100L, 3L);

table.put(100L, "a"); // ok
table.put(101L, "b"); // ok
table.put(102L, "c"); // ok

table.put(99L, "x");  // IndexOutOfBoundsException
table.put(103L, "x"); // IndexOutOfBoundsException
```

The capacity is fixed after construction. There is no resize operation.

## Null Policy

In the base variants, `null` means "absent".

```java
Long2Reference<String> table = Long2Reference.concurrent(0L, 16L);

table.get(3L);          // null, absent
table.put(3L, null);    // NullPointerException
table.delete(3L);       // slot becomes absent
```

If you need real `null` values, use `Long2Reference.nullable(...)`:

```java
var nullable = Long2Reference.<String>nullable(0L, 16L);

nullable.put(3L, null);

nullable.containsKey(3L); // true
nullable.get(3L);         // null, but present
nullable.countByScan();   // 1
```

The nullable wrapper stores an internal sentinel object for real `null` values. This adds a small read/write cost, so use it only when the distinction matters.

## State Policy

The base variants do not distinguish "never set" from "deleted". Both look absent.

If your domain needs lifecycle state, use `Long2Reference.stateful(...)`:

```java
import net.sixik.concurrent_library.long2reference.Long2ReferenceState;

var table = Long2Reference.<String>stateful(0L, 16L);

Long2ReferenceState initial = table.state(7L); // NEVER_SET

table.put(7L, null);
Long2ReferenceState withNull = table.state(7L); // PRESENT_NULL

table.delete(7L);
Long2ReferenceState deleted = table.state(7L); // DELETED

table.putIfAbsent(7L, "new-value");
Long2ReferenceState present = table.state(7L); // PRESENT
```

Available states:

| State | Meaning |
|---|---|
| `NEVER_SET` | The slot has never been written |
| `PRESENT` | The slot contains a non-null value |
| `PRESENT_NULL` | The slot contains a real `null` value |
| `DELETED` | The slot was explicitly deleted or removed |

## Counting Policy

The base variants intentionally do not maintain an exact size counter. This keeps writes cheap.

Use scans for rare checks:

```java
long count = table.countByScan();
boolean empty = table.isEmptyByScan();
```

Use `Long2Reference.counting(...)` when frequent O(1) size checks matter more than write speed:

```java
var table = Long2Reference.<String>counting(0L, 16L);

table.putIfAbsent(2L, "two");
table.size();    // 1
table.isEmpty(); // false

table.remove(2L);
table.size();    // 0
```

The counting wrapper updates an `AtomicLong` when entries transition between absent and present. That makes writes heavier than the base variants.

## Choosing A Variant

| Need | Recommended API |
|---|---|
| Simple compact range | `Long2Reference.dense(base, intCapacity)` |
| Let the library choose layout | `Long2Reference.concurrent(base, capacity)` |
| Very large logical capacity | `Long2Reference.paged(base, capacity)` |
| Real `null` values | `Long2Reference.nullable(base, capacity)` |
| Fast `size()` / `isEmpty()` | `Long2Reference.counting(base, capacity)` |
| Distinguish never-set/deleted/null | `Long2Reference.stateful(base, capacity)` |
| Strict volatile memory accesses | `Long2Reference.builder().range(...).strict().build()` |
| Plain single-threaded/external sync | `Long2Reference.builder().range(...).plain().build()` |
| Reduced false sharing for adjacent writes | `Long2Reference.builder().range(...).padded().build()` |
| Trusted range with unchecked hot methods | `Long2Reference.builder().range(...).dense().unchecked().build()` |

## Builder API

The builder is for expert configuration. The default factory methods are better for normal code.

```java
Long2Reference<OrderBook> table = Long2Reference.builder()
        .range(0L, 1_000_000L)
        .dense()
        .strict()
        .build();
```

Useful builder options:

```java
Long2Reference<Value> dense = Long2Reference.builder()
        .range(baseKey, capacity)
        .dense()
        .build();

Long2Reference<Value> paged = Long2Reference.builder()
        .range(baseKey, capacity)
        .paged()
        .build();

Long2Reference<Value> strict = Long2Reference.builder()
        .range(baseKey, capacity)
        .dense()
        .strict()
        .build();

Long2Reference<Value> nullableCounting = Long2Reference.builder()
        .range(baseKey, capacity)
        .nullable()
        .counting()
        .build();
```

Important builder rules:

- Always call `range(baseKey, capacity)` before `build()`.
- `capacity` must be non-negative.
- `padded()` requires capacity to fit an `int`, because it is dense-based.
- `dense()` also requires capacity to fit an `int`.
- `plain()` is not a thread-safe publication mode by itself.
- `unchecked()` does not make invalid keys safe. It is for trusted hot paths only.

## Slot API For Hot Loops

If you repeatedly access the same key, normalize the key once and use the slot API.

```java
DenseChecked<OrderBook> books = Long2Reference.dense(0L, 1_000_000);

int slot = books.checkedSlot(42L);

books.putAt(slot, new OrderBook(42L, "EURUSD"));
OrderBook book = books.getAt(slot);
books.deleteAt(slot);
```

For paged tables, slots are `long`:

```java
PagedChecked<OrderBook> books = Long2Reference.paged(0L, 10_000_000_000L);

long slot = books.checkedSlotLong(8_000_000_000L);

books.putAt(slot, new OrderBook(8_000_000_000L, "LARGE"));
OrderBook book = books.getAt(slot);
```

Use `trySlot` / `trySlotLong` when invalid keys are expected and should not throw:

```java
int slot = books.trySlot(candidateKey);
if (slot >= 0) {
    books.putAt(slot, value);
}
```

Dense tables also support debug-friendly slot tokens:

```java
Slot<DenseChecked<OrderBook>> slot = books.checkedSlotObject(42L);

books.put(slot, new OrderBook(42L, "EURUSD"));
OrderBook book = books.get(slot);
books.delete(slot);
```

Slot tokens verify that the token belongs to the same table. Primitive slots are faster; token slots are safer in cold/debug code.

## Thread Safety And Publication

Default checked variants use acquire/release reference accesses:

```text
put/delete -> release store
get        -> acquire load
```

This safely publishes the reference and the state written before `put(...)`:

```java
record Snapshot(long version, String payload) {}

// Thread A
table.put(10L, new Snapshot(1L, "ready"));

// Thread B
Snapshot snapshot = table.get(10L);
if (snapshot != null) {
    // sees the state constructed before publication
}
```

The collection publishes references safely. It does not make later mutations inside the referenced object safe.

Prefer immutable or replacement-based values:

```java
record SessionView(long sequence, String status) {}

table.put(sessionId, new SessionView(1L, "OPEN"));
table.put(sessionId, new SessionView(2L, "CLOSED"));
```

Avoid mutating shared objects after publishing unless you provide separate synchronization:

```java
final class MutableSession {
    long sequence;
    String status;
}

MutableSession session = new MutableSession();
table.put(sessionId, session);

session.status = "CLOSED"; // unsafe if other threads read it without synchronization
```

## Safe Table Replacement

If the table is stored in a final field before worker threads start, normal Java final-field publication is enough.

```java
final class Engine {
    private final DenseChecked<OrderBook> books = Long2Reference.dense(0L, 1_000_000);
}
```

If the table can be replaced at runtime, publish the table through `Long2Reference.Ref`:

```java
Long2Reference.Ref<Long2Reference<OrderBook>> ref = Long2Reference.ref(
        Long2Reference.concurrent(0L, 1_000_000L)
);

Long2Reference<OrderBook> current = ref.get();

Long2Reference<OrderBook> replacement = Long2Reference.concurrent(0L, 2_000_000L);
ref.set(replacement);
```

`Ref.get()` uses acquire semantics and `Ref.set(...)` uses release semantics.

## Delete vs Remove

Use `delete` when you do not need the old value:

```java
table.delete(key);
```

Use `remove` only when the old value matters:

```java
OrderBook old = table.remove(key);
if (old != null) {
    closeBook(old);
}
```

`remove` is an atomic get-and-clear operation. It is generally more expensive than `delete`, especially under contention.

## Diagnostics

Diagnostics are optional and construction-time only.

```java
Long2ReferenceDiagnostics.setSink(System.out::println);

Long2Reference<OrderBook> table = Long2Reference.concurrent(0L, 100_000_000L);
```

Diagnostics can warn about:

- Large estimated memory usage.
- Very large tables where huge pages may help tail latency.
- Dense tables on many CPU cores where adjacent writes may cause false sharing.
- Paged capacities with unused tail slots in the final page.

Builder note: when diagnostics are not enabled through the builder, `Builder.build()` clears the diagnostics sink.

```java
Long2Reference<OrderBook> table = Long2Reference.builder()
        .range(0L, 100_000_000L)
        .diagnostics(true)
        .build();
```

## Practical Rules Of Thumb

- Use `Long2Reference.concurrent(...)` first unless you already know the layout you need.
- Use `DenseChecked` for compact hot ranges.
- Use `PagedChecked` for huge capacities.
- Keep values immutable, or replace references instead of mutating shared objects.
- Use `delete` instead of `remove` unless you need the old value.
- Use `counting(...)` only when exact O(1) size is worth slower writes.
- Use `nullable(...)` only when present-null is a real business state.
- Use `stateful(...)` only when lifecycle state matters.
- Use slot APIs only after profiling or in obvious tight loops.
- Use unchecked/plain variants only when the calling code owns the safety contract.

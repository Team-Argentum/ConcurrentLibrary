# Long2Reference Pitfalls, Warnings, And Tips

This guide collects the things that are easy to get wrong when adopting or modifying `Long2Reference`.

## 1. This Is Not A General Map

`Long2Reference` does not hash keys and does not resize.

You must know the range in advance:

```text
baseKey <= key < baseKey + capacity
```

Bad fit:

```java
// User IDs are arbitrary and sparse across a huge ID space.
Long2Reference<User> users = Long2Reference.concurrent(0L, Long.MAX_VALUE);
```

Better fit:

```java
// Runtime assigns dense local IDs from 0 to maxSessionCount - 1.
Long2Reference<Session> sessions = Long2Reference.concurrent(0L, maxSessionCount);
```

If the valid range is huge but occupancy is low, a map may use much less memory.

## 2. Capacity Is Fixed

There is no resize operation.

If you need a larger table, create a new one, migrate values, and publish the replacement through `Long2Reference.Ref`.

```java
Long2Reference.Ref<Long2Reference<Value>> ref = Long2Reference.ref(
        Long2Reference.concurrent(0L, oldCapacity)
);

Long2Reference<Value> oldTable = ref.get();
Long2Reference<Value> newTable = Long2Reference.concurrent(0L, newCapacity);

for (long slot = 0; slot < oldTable.capacity(); slot++) {
    long key = oldTable.baseKey() + slot;
    Value value = oldTable.get(key);
    if (value != null) {
        newTable.put(key, value);
    }
}

ref.set(newTable);
```

> Warning: migration is your responsibility. `Ref` only safely publishes the new table reference.

## 3. Base Variants Do Not Store Null

In base variants `null = absent`, so this code will throw a NullPointerException:

```java
table.put(key, null);
```

Use nullable when real null values are meaningful:

```java
var table = Long2Reference.<String>nullable(0L, 128L);
table.put(10L, null);
table.containsKey(10L); // true
```

> Tip: with nullable tables, use `containsKey(key)` to distinguish absent from present-null.

## 4. `delete` Is Usually Better Than `remove`

Use `delete` when you do not need the old value.

```java
table.delete(key);
```

Use `remove` only when cleanup needs the old value.

```java
Resource old = table.remove(key);
if (old != null) {
    old.close();
}
```

In this case `remove` is an atomic get-and-clear operation. It is heavier than a release-store clear.

## 5. `countByScan` Is Not A Free Size

Base variants do not maintain exact size.

```java
long count = table.countByScan();
```

This scans the table, so it is O(capacity).

Use it for rare checks, diagnostics, assertions, and tests. Use `Long2Reference.counting(...)` if frequent exact size checks are a core requirement.

```java
CountingLong2Reference<Value> table = Long2Reference.counting(0L, 1_000_000L);
long size = table.size();
```

Be warned, counting makes writes more expensive!

## 6. Scan Results Are Weakly Consistent During Writes

`countByScan()` and `isEmptyByScan()` do not freeze the table.

If another thread writes while a scan is running, the result is a moment-in-time traversal, not a linearizable global snapshot.

Use scans for:

- Tests after workers have stopped.
- Debugging.
- Rare approximate operational checks.
- Maintenance tasks where weak consistency is acceptable.

Do not use scan results as a strict concurrent coordination primitive.

## 7. Safe Publication Is For References, Not Object Internals

Default variants safely publish a reference with acquire/release semantics.

Good:

```java
record Snapshot(long sequence, String status) {}

table.put(id, new Snapshot(10L, "READY"));
```

Risky:

```java
final class MutableSnapshot {
    long sequence;
    String status;
}

MutableSnapshot snapshot = new MutableSnapshot();
table.put(id, snapshot);
snapshot.status = "READY"; // data race if readers access it concurrently
```

> Tip: use immutable values or replace the whole reference after building the new state.

## 8. Plain Variants Are Not Thread-Safe Publication

Builder plain mode uses plain array reads and writes.

```java
Long2Reference<Value> table = Long2Reference.builder()
        .range(0L, 1024L)
        .plain()
        .build();
```

Use it only when:

- Access is single-threaded.
- Access is guarded by external synchronization.
- Visibility is provided by a higher-level protocol.

For normal concurrent publication, use the default acquire/release variants.

## 9. Unchecked Variants Still Have A Safety Contract

Unchecked variants expose trusted methods such as:

```java
getTrusted(long key)
putTrusted(long key, value)
getAtUnchecked(slot)
putAtUnchecked(slot, value)
```

Only use them when the caller has already proved the key or slot is valid.

Bad:

```java
unchecked.putTrusted(userInputKey, value);
```

Better:

```java
int slot = checked.trySlot(userInputKey);
if (slot >= 0) {
    unchecked.putAtUnchecked(slot, value);
}
```

Unchecked access can fail with array errors or corrupt logical assumptions if the key belongs to another range.

## 10. Dense Slots Are `int`, Paged Slots Are `long`

Dense:

```java
int slot = dense.checkedSlot(key);
```

Paged:

```java
long slot = paged.checkedSlotLong(key);
```

Do not downcast paged slots to `int`. Paged capacity may exceed `Integer.MAX_VALUE`.

## 11. Paged Tables Allocate Full Pages

Paged layout uses fixed-size pages of `65,536` references.

If capacity is not page-aligned, the final page has unused tail slots.

```text
PAGE_SIZE = 65,536
capacity  = 70,000
allocated = 131,072 slots
legal     = 70,000 slots
unused    = 61,072 slots
```

This avoids special branches in hot access paths. The checked APIs keep unused tail slots inaccessible.

## 12. Padded Layout Can Use Much More Memory

Padded layout spreads logical slots apart to reduce false sharing.

```java
Long2Reference<Value> table = Long2Reference.builder()
        .range(0L, 4096L)
        .padded()
        .build();
```

The stride is based on reference scale:

```text
64 bytes / referenceScale
```

Typical outcomes:

- 4-byte compressed references -> stride 16.
- 8-byte references -> stride 8.

That means memory usage can grow by 8x or 16x. Use padded layout only for measured adjacent-write contention or very clear workload reasons.

## 13. Range Overflow Is A Construction Error

Implementations use `Math.addExact(base, capacity)` to compute the exclusive limit.

This catches overflow:

```java
Long2Reference.concurrent(Long.MAX_VALUE - 5L, 10L); // ArithmeticException
```

Pick a base/capacity pair that forms a valid half-open range.

## 14. `containsKey` Can Mean Different Things In Wrappers

Base variants:

```text
containsKey(key) == get(key) != null
```

Nullable wrapper:

```text
present-null is present
```

Stateful wrapper:

```text
PRESENT and PRESENT_NULL are present
NEVER_SET and DELETED are absent
```

When wrapper semantics matter, write tests against `containsKey`, not only `get`.

## 15. Builder Wrapper Order Matters

Builder wraps the base table in this order:

```text
stateful -> counting -> nullable
```

Composition affects null handling, presence checks, and counter updates. If you change wrapper order in code, update tests and documentation together.

## 16. Diagnostics Are Advisory

Diagnostics can warn about memory size, huge-page opportunities, false sharing risk, and paged tail waste.

They should not be used for correctness decisions.

```java
Long2ReferenceDiagnostics.setSink(System.out::println);

Long2Reference<Value> table = Long2Reference.builder()
        .range(0L, 100_000_000L)
        .diagnostics(true)
        .build();
```

Tip: diagnostics run during construction. Keep hot-path code free from diagnostic branches.

## 17. Do Not Reuse Slot Tokens Across Tables

Dense slot tokens store an owner table identity.

```java
Slot<DenseChecked<Value>> slot = first.checkedSlotObject(key);

first.get(slot);  // ok
second.get(slot); // IllegalArgumentException
```

This is intentional. A primitive slot from one table can silently mean something else in another table, so token APIs protect debug/cold code from owner mistakes.

## 18. Interface Type vs Concrete Type

This is clean and flexible:

```java
Long2Reference<Value> table = Long2Reference.concurrent(0L, capacity);
```

This can be better in extreme hot loops:

```java
DenseChecked<Value> table = Long2Reference.dense(0L, 1_000_000);
```

The concrete final class gives the JIT a simpler call target. Prefer clarity first; use concrete types where profiling or obvious hot-path pressure justifies it.

## 19. Avoid Exception-Driven Validation For Normal Input

Checked variants throw for invalid keys. That is useful for catching bugs.

If invalid keys are expected from input, validate without exceptions:

```java
int slot = dense.trySlot(key);
if (slot < 0) {
    return;
}
dense.putAt(slot, value);
```

For paged tables:

```java
long slot = paged.trySlotLong(key);
if (slot >= 0L) {
    paged.putAt(slot, value);
}
```

## 20. Adoption Checklist

Before replacing a map with `Long2Reference`, answer these questions:

- What is the exact `baseKey`?
- What is the maximum required `capacity`?
- Is the key range dense enough to justify direct addressing?
- Should `null` mean absent, or can `null` be a real value?
- Do you need never-set vs deleted state?
- Do you need frequent exact size checks?
- Is runtime resize/replacement required?
- Are values immutable or otherwise safely synchronized?
- Are invalid keys bugs or expected input?
- Is this path hot enough to use slot APIs or concrete classes?

If any answer is unclear, start with a normal map or a small adapter around `Long2Reference` and add tests before moving the hot path over.

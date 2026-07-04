# Long2IntMap Documentation

This folder is centered around `Long2IntMap`, the dynamic concurrent `long -> int` map.

The broader `Long2Int` family also includes immutable lookup tables, fixed append/update maps, and single-threaded variants. Use this folder when you need the mutable concurrent map behavior specifically. The full low-level implementation specification remains in [`../Long2Int.md`](../Long2Int.md).

## Start Here

- [Long2IntMap.md](Long2IntMap.md) - main guide: concept, quick start, API, dynamic vs managed mode, concurrency, performance, and examples.

## Quick Start

```java
import net.sixik.concurrent_library.long2int.Long2Int;
import net.sixik.concurrent_library.long2int.Long2IntMap;

Long2IntMap scores = new Long2Int.DynamicDirectLong2IntMap(1_000_000);

scores.put(42L, 100);
int score = scores.getOrDefault(42L, -1);
scores.addIfPresent(42L, 5);
scores.remove(42L);
scores.close();
```

`get(long key)` returns `0` when the key is absent. Use `getOrDefault(long key, int defaultValue)` when `0` is a real domain value:

```java
int missing = Integer.MIN_VALUE;
int value = scores.getOrDefault(42L, missing);
```

## What To Use

Use `Long2IntMap` for new concurrent mutable code when:

- keys are primitive `long` values;
- values are primitive `int` values;
- key boxing and value boxing are unacceptable in hot paths;
- you need `put`, `putIfAbsent`, `remove`, `compareAndSet`, or add-if-present operations;
- the table may need to grow or rebuild after deletes;
- direct off-heap storage and high throughput matter more than a general-purpose Java collections API.

Use `Long2Int.concurrent(expectedSize)` when:

- the map is long-lived and resize count is bounded;
- retaining retired tables until `close()` is acceptable;
- maximum read/write throughput is more important than bounded retired memory.

The equivalent constructor form is:

```java
Long2IntMap map = new Long2Int.DynamicDirectLong2IntMap(expectedSize);
```

Use `Long2Int.concurrentManaged(expectedSize)` when:

- the map may resize repeatedly;
- bounded retired direct memory is required;
- the small hazard-pointer cost is acceptable.

The equivalent constructor form is:

```java
Long2IntMap map = new Long2Int.ManagedDynamicDirectLong2IntMap(expectedSize);
```

Use `Long2Int.fixed(expectedSize)` when:

- capacity is known in advance;
- removes and resizes are not needed;
- the workload is append/update-only and should be as fast as possible.

Use `Long2Int.lookup(keys, values)` when:

- the data set is built once and then read-only;
- fastest immutable lookup is the goal.

# Long2IntMap Documentation

This folder is centered around explicit `long -> int` map implementations under `collections.maps.long2int`.

The public API is constructor-first: use concrete classes directly instead of facade factories or builders.

## Start Here

- [Long2IntMap.md](Long2IntMap.md) - main guide: concept, quick start, API, dynamic vs managed mode, concurrency, performance, and examples.

## Quick Start

```java
import net.sixik.concurrent_library.collections.maps.long2int.ConcurrentLong2IntMap;
import net.sixik.concurrent_library.collections.maps.long2int.Long2IntMap;

Long2IntMap scores = new ConcurrentLong2IntMap(1_000_000);

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

Use `ConcurrentLong2IntMap` for the fastest dynamic concurrent map when retired tables can stay allocated until `close()`:

```java
Long2IntMap map = new ConcurrentLong2IntMap(expectedSize);
```

Use `ManagedConcurrentLong2IntMap` when repeated resize/rebuild is expected and retired direct memory should be reclaimed before `close()`:

```java
Long2IntMap map = new ManagedConcurrentLong2IntMap(expectedSize);
```

Use `FixedDirectLong2IntAppendMap` when capacity is known and the workload is append/update-only:

```java
Long2IntAppendMap map = new FixedDirectLong2IntAppendMap(expectedSize);
```

Use `ImmutableDirectLong2IntLookup` for read-only tables built from arrays:

```java
Long2IntLookup lookup = new ImmutableDirectLong2IntLookup(keys, values);
```

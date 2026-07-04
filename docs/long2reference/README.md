# Long2ReferenceMap Documentation

This folder is centered around `Long2ReferenceMap<V>`, the dynamic concurrent `long -> reference` map.

The old `Long2Reference` fixed-range table remains in the project as a legacy direct-addressed structure. It is useful when the full key range is known in advance and does not move, but sparse or moving workloads should use `Long2ReferenceMap`.

## Start Here

- [Long2ReferenceMap.md](../Long2ReferenceMap.md) - main guide: concept, quick start, API, performance, and examples.

## Quick Start

```java
import net.sixik.concurrent_library.long2reference.Long2ReferenceMap;

record OrderBook(long instrumentId, String symbol) {}

Long2ReferenceMap<OrderBook> books = new Long2ReferenceMap<>(0L, 1_000_000L);

books.put(42L, new OrderBook(42L, "EURUSD"));
OrderBook book = books.get(42L);
books.remove(42L);
```

The first range is the hot range, not the complete valid key domain:

```java
books.put(Long.MAX_VALUE, new OrderBook(Long.MAX_VALUE, "FAR")); // valid cold path
books.resize(Long.MAX_VALUE - 1_000L, 2_000L);                   // make this range hot
```

## What To Use

Use `Long2ReferenceMap` for new code when:

- keys are primitive `long` values;
- values are non-null Java references;
- the active key range can move;
- keys can be sparse or distant;
- map-like operations and high throughput are both needed.

Construction options are equivalent for the common default case:

```java
Long2ReferenceMap<OrderBook> viaConstructor = new Long2ReferenceMap<>(0L, 1_000_000L);
Long2ReferenceMap<OrderBook> viaFactory = Long2ReferenceMap.concurrent(0L, 1_000_000L);
Long2ReferenceMap<OrderBook> viaBuilder = Long2ReferenceMap.<OrderBook>builder()
        .hotRange(0L, 1_000_000L)
        .build();
```

Use old fixed-range `Long2Reference` only when:

- the key range is known in advance;
- the range is compact enough for direct addressing;
- there is no need to move the hot range.

## Performance Snapshot

Quick 8-thread JMH regression runs show approximately:

| Scenario | Long2ReferenceMap Hot | JCTools | ConcurrentHashMap |
|---|---:|---:|---:|
| `get(existing)` | 630.64 M ops/s | 113.40 M ops/s | 121.71 M ops/s |
| `mixed 90/10` | 284.29 M ops/s | 106.82 M ops/s | 86.73 M ops/s |
| `put(existing)` contended | 79.14 M ops/s | 103.98 M ops/s | 27.28 M ops/s |
| `put(existing)` striped | 399.57 M ops/s | 257.85 M ops/s | 81.47 M ops/s |
| `setExisting` striped | 415.30 M ops/s | 320.63 M ops/s | 91.46 M ops/s |

These are quick `-wi 1 -i 3 -f 1` numbers for regression checks. Use a longer JMH run before treating them as final benchmark results.

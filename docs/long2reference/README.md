# Long2Reference Documentation

This folder is centered around two separate structures:

- `ConcurrentLong2ReferenceMap<V>` - dynamic sparse concurrent `long -> reference` map;
- `Long2ReferenceTable<V>` - fixed-range direct-addressed table for compact, known ranges.

Use concrete constructors directly. The old `Long2Reference` compatibility alias and builder/factory APIs were removed so construction stays explicit at the call site.

## Start Here

- [ConcurrentLong2ReferenceMap.md](ConcurrentLong2ReferenceMap.md) - dynamic map guide: concept, quick start, API, performance, and examples.

## Quick Start

```java
import net.sixik.concurrent_library.collections.maps.long2reference.ConcurrentLong2ReferenceMap;

record OrderBook(long instrumentId, String symbol) {}

ConcurrentLong2ReferenceMap<OrderBook> books = new ConcurrentLong2ReferenceMap<>(0L, 1_000_000L);

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

Use `ConcurrentLong2ReferenceMap` for new code when:

- keys are primitive `long` values;
- values are non-null Java references;
- the active key range can move;
- keys can be sparse or distant;
- map-like operations and high throughput are both needed.

Construct maps directly:

```java
ConcurrentLong2ReferenceMap<OrderBook> books = new ConcurrentLong2ReferenceMap<>(0L, 1_000_000L);
ConcurrentLong2ReferenceMap<OrderBook> tuned = new ConcurrentLong2ReferenceMap<>(0L, 1_000_000L, 12, true, true, true);
```

Use fixed-range `Long2ReferenceTable` only when:

- the key range is known in advance;
- the range is compact enough for direct addressing;
- there is no need to move the hot range.

Choose the fixed-range layout explicitly:

```java
Long2ReferenceTable<OrderBook> dense = new DenseChecked<>(0L, 1_000_000);
Long2ReferenceTable<OrderBook> paged = new PagedChecked<>(0L, 100_000_000L);
Long2ReferenceTable<OrderBook> strict = new StrictDenseChecked<>(0L, 1_000_000);
```

## Performance Snapshot

Quick 8-thread JMH regression runs show approximately:

| Scenario | ConcurrentLong2ReferenceMap Hot | JCTools | ConcurrentHashMap |
|---|---:|---:|---:|
| `get(existing)` | 630.64 M ops/s | 113.40 M ops/s | 121.71 M ops/s |
| `mixed 90/10` | 284.29 M ops/s | 106.82 M ops/s | 86.73 M ops/s |
| `put(existing)` contended | 79.14 M ops/s | 103.98 M ops/s | 27.28 M ops/s |
| `put(existing)` striped | 399.57 M ops/s | 257.85 M ops/s | 81.47 M ops/s |
| `setExisting` striped | 415.30 M ops/s | 320.63 M ops/s | 91.46 M ops/s |

These are quick `-wi 1 -i 3 -f 1` numbers for regression checks. Use a longer JMH run before treating them as final benchmark results.

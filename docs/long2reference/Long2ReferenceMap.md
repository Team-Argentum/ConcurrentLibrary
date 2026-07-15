# ConcurrentLong2ReferenceMap

`ConcurrentLong2ReferenceMap<V>` is a concurrent dynamic `long -> reference` map.

It is the recommended structure when keys are primitive `long` values, the active key range can move over time, and the fixed-range `Long2ReferenceTable` family is too rigid. Construction is explicit through concrete classes; the old compatibility alias and builder/factory APIs were removed.

Core idea:

```text
authoritative storage = sparse radix page store over the full long key-space
hot path accelerator  = replaceable direct-addressed window over the active range
```

`resize(baseKey, capacity)` changes only the hot accelerator. It does not move values, copy pages, or invalidate mappings outside the new range.

## Why It Exists

`ConcurrentHashMap<Long, V>` is flexible, but hot operations pay for key boxing, hashing, probing, and a general-purpose map layout.

`Long2ReferenceTable` is very direct, but it requires a fixed range:

```text
slot = key - baseKey
```

That is excellent when the range is known, compact, and stable. It is a poor fit when keys are sparse, distant, or when the active range changes over time.

`ConcurrentLong2ReferenceMap` sits between these two shapes:

- primitive `long` API with no key boxing on the primary path;
- familiar map-like operations: `get`, `put`, `remove`, `replace`, `compute`, `merge`;
- sparse dynamic storage covering the whole `long` key-space;
- hot range acceleration for frequently touched keys;
- cheap `resize(...)`, because only acceleration metadata changes;
- optional `ConcurrentMap<Long, V>` view for integration code.

## Quick Start

```java
import net.sixik.concurrent_library.collections.maps.long2reference.ConcurrentLong2ReferenceMap;

record OrderBook(long instrumentId, String symbol) {}

ConcurrentLong2ReferenceMap<OrderBook> books = new ConcurrentLong2ReferenceMap<>(0L, 1_000_000L);

books.put(42L, new OrderBook(42L, "EURUSD"));

OrderBook book = books.get(42L);

books.remove(42L);
```

The constructor sets the initial hot range:

```text
[baseKey, baseKey + capacity)
```

This is not the complete valid key domain. It is only the range the map tries to serve through the fastest path.

```java
ConcurrentLong2ReferenceMap<String> map = new ConcurrentLong2ReferenceMap<>(1_000L, 10_000L);

map.put(1_234L, "hot");             // hot path
map.put(Long.MAX_VALUE, "distant"); // valid, cold sparse path

map.resize(Long.MAX_VALUE - 100L, 1_000L);

map.get(Long.MAX_VALUE);             // now covered by the hot window
map.get(1_234L);                     // still valid, now cold path
```

## Constructors

Use constructors for the common default configuration:

```java
ConcurrentLong2ReferenceMap<OrderBook> emptyHotRange = new ConcurrentLong2ReferenceMap<>();

ConcurrentLong2ReferenceMap<OrderBook> books = new ConcurrentLong2ReferenceMap<>(0L, 1_000_000L);

ConcurrentLong2ReferenceMap<OrderBook> smallPages = new ConcurrentLong2ReferenceMap<>(0L, 1_000_000L, 10);
```

Use direct constructors for both default and tuned configurations:

| Constructor | Meaning |
|---|---|
| `new ConcurrentLong2ReferenceMap<>()` | Empty hot range with default page settings. |
| `new ConcurrentLong2ReferenceMap<>(baseKey, capacity)` | Initial hot range with default page settings. |
| `new ConcurrentLong2ReferenceMap<>(baseKey, capacity, pageBits)` | Initial hot range with custom page size. |
| `new ConcurrentLong2ReferenceMap<>(baseKey, capacity, pageBits, prefillHotWindow, vacuumOnEmptyPage, mapViewEnabled)` | Fully tuned configuration. |

Use the full constructor when you need to tune page size, hot-window behavior, or `ConcurrentMap` compatibility.

```java
ConcurrentLong2ReferenceMap<OrderBook> books = new ConcurrentLong2ReferenceMap<>(
        0L,
        1_000_000L,
        12,
        true,
        true,
        true
);
```

| Option | Default | Meaning |
|---|---:|---|
| `baseKey, capacity` | `0, 0` | Sets the initially accelerated range. Keys outside it remain valid. |
| `pageBits` | `12` | Page size is `1 << pageBits`. Larger pages reduce page count but may waste memory on sparse workloads. |
| `prefillHotWindow` | `true` | Caches known page cells when a new hot window is built. |
| `vacuumOnEmptyPage` | `true` | Allows empty pages to retire after the last mapping is removed. |
| `mapViewEnabled` | `true` | Enables or disables `asMapView()`. Disable it if boxed `ConcurrentMap<Long,V>` compatibility is never needed. |

## Data Model

For every key, the map derives an absolute page and a page-local offset:

```text
pageIndex = key >> pageBits
offset    = key & ((1 << pageBits) - 1)
```

Hot path:

```text
key -> HotWindow.pages[pageSlot] -> Page.values[offset]
```

Cold path:

```text
key -> RadixPageStore -> PageCell -> Page.values[offset]
```

`RadixPageStore` is the authoritative storage. `HotWindow` only caches pages for the current active range. This is why `resize(...)` is safe: old and new windows point back to the same underlying storage.

## Core API

| Method | Meaning |
|---|---|
| `get(long key)` | Returns the current value or `null` when absent. |
| `getOrDefault(long key, V defaultValue)` | Returns the value or a fallback. |
| `containsKey(long key)` | Checks presence via `get(key) != null`. |
| `put(long key, V value)` | Inserts or replaces a value and returns the previous value. |
| `setExisting(long key, V value)` | Expert overwrite for keys that are already present. |
| `putIfAbsent(long key, V value)` | Inserts only when absent; returns the existing value when present. |
| `remove(long key)` | Removes a mapping and returns the previous value. |
| `remove(long key, V value)` | Removes only if the current value equals the expected value. |
| `replace(long key, V value)` | Replaces only an existing mapping and returns the previous value. |
| `replace(long key, V oldValue, V newValue)` | Conditional replace. |
| `computeIfAbsent(...)` | Computes and inserts a value when absent. |
| `computeIfPresent(...)` | Recomputes only when present; a `null` result removes the mapping. |
| `compute(...)` | General remapping; a `null` result removes the mapping. |
| `merge(...)` | Merges the current and provided values; a `null` result removes the mapping. |
| `mappingCount()` | Mapping count as `long`. Prefer this over `size()`. |
| `size()` | Java collection-compatible `int` size, saturated at `Integer.MAX_VALUE`. |
| `clear()` | Removes all mappings. |
| `resize(baseKey, capacity)` | Changes the hot range. Does not remove or move entries. |
| `vacuum()` | Scans storage and retires empty pages. |
| `stats()` | Returns diagnostic counters and hot-window information. |
| `asMapView()` | Returns a boxed `ConcurrentMap<Long,V>` adapter. |
| `forEach(...)`, `iterator()` | Weakly consistent traversal over current mappings. |

## Null Policy

`null` values are forbidden.

This is intentional and mirrors `ConcurrentHashMap`:

```text
null  = absent key
value = present key
```

```java
map.put(10L, value);        // ok
map.put(10L, null);         // NullPointerException
map.setExisting(10L, null); // NullPointerException
```

If the domain needs a real nullable state, wrap the value explicitly:

```java
record MaybeValue<T>(boolean presentNull, T value) {}
```

or use a domain-specific sentinel.

## Overwrite: `put` Or `setExisting`

The map has two overwrite paths with different contracts.

### Normal `put`

Use `put` when you need normal map semantics:

```java
OrderBook old = books.put(id, updatedBook);
```

`put`:

- returns the previous value;
- inserts absent keys;
- correctly updates `size` and page live counters;
- is the safe general-purpose method.

For an existing key, it first tries a fast overwrite path:

```text
hot page -> old value -> compareAndExchangeRelease(old, newValue)
```

The full insert/reservation path is used only for absent slots or races.

### Expert `setExisting`

`setExisting` is for hot loops where the caller owns key lifecycle and knows the mapping is already present.

```java
boolean overwritten = books.setExisting(id, updatedBook);
```

Contract:

- returns `true` only when a non-null value was already present;
- returns `false` when the key/page/slot is missing;
- does not insert absent keys;
- does not return the old value;
- does not update `size` or page live counters;
- publishes the new reference with a release store.

This is the fastest overwrite path. Use it only when a missing key is a lifecycle error, not a normal case.

## Concurrency Model

Point operations are thread-safe. Reference publication uses acquire/release semantics:

```text
writer release-publishes reference
reader acquire-loads reference
```

This safely publishes the reference and the state written before publication. The map does not make later unsynchronized mutation inside the referenced object safe.

Prefer immutable values or replacement-based updates:

```java
record SessionView(long version, String status) {}

sessions.put(sessionId, new SessionView(1L, "OPEN"));
sessions.put(sessionId, new SessionView(2L, "CLOSED"));
```

Avoid mutable shared values without external synchronization:

```java
final class MutableSession {
    long version;
    String status;
}

MutableSession session = new MutableSession();
sessions.put(sessionId, session);
session.status = "CLOSED"; // readers do not automatically synchronize with this write
```

`forEach`, `iterator`, `stats`, and `vacuum` should be treated as maintenance/diagnostic operations with weakly consistent behavior, not as transactional snapshots.

## `asMapView`

`asMapView()` is for integration with Java APIs that expect `ConcurrentMap<Long,V>`.

```java
ConcurrentLong2ReferenceMap<OrderBook> primitive = new ConcurrentLong2ReferenceMap<>(0L, 1_000_000L);
ConcurrentMap<Long, OrderBook> boxed = primitive.asMapView();

boxed.computeIfAbsent(42L, id -> new OrderBook(id, "EURUSD"));
```

This path boxes keys. Use primitive methods directly in hot code.

## Vacuum And Stats

Pages are allocated lazily. Empty pages can retire automatically after the last mapping is removed when `vacuumOnEmptyPage(true)` is enabled.

Explicit empty-page cleanup:

```java
ConcurrentLong2ReferenceMap.VacuumStats vacuum = map.vacuum();

System.out.println(vacuum.scannedCells());
System.out.println(vacuum.retiredPages());
```

Diagnostics:

```java
ConcurrentLong2ReferenceMap.ConcurrentLong2ReferenceMapStats stats = map.stats();

System.out.println(stats.mappingCount());
System.out.println(stats.allocatedPages());
System.out.println(stats.hotPageCount());
System.out.println(stats.estimatedBytes());
```

## How Much Faster

The numbers below are from quick JMH regression runs in this project:

```text
JDK: GraalVM JDK 25.0.3
JMH: throughput mode
Threads: 8
Capacity: 262,144
Fill ratio: 0.90
Warmup/measurement: -wi 1 -i 3 -f 1
```

These are not publication-quality runs; error bars are large in several scenarios. For final numbers, use at least `-wi 5 -i 10 -f 2`.

### Throughput, M ops/s

| Scenario | ConcurrentLong2ReferenceMap Hot | ConcurrentLong2ReferenceMap Cold | ConcurrentHashMap | JCTools NonBlockingHashMapLong | Trivago blocking | Trivago busy-waiting |
|---|---:|---:|---:|---:|---:|---:|
| `get(existing)` | 630.64 | 226.44 | 121.71 | 113.40 | 35.46 | 37.08 |
| `mixed 90% read / 10% write` | 284.29 | 159.98 | 86.73 | 106.82 | 27.61 | 32.10 |
| `put(existing)` contended | 79.14 | 56.84 | 27.28 | 103.98 | 25.56 | 33.15 |
| `put(existing)` striped | 399.57 | 192.70 | 81.47 | 257.85 | 24.82 | 25.30 |
| `setExisting` striped | 415.30 | 213.07 | 91.46 | 320.63 | 23.92 | 23.76 |

The Trivago wrapper is benchmarked here as primitive `long -> long`, not as a direct `long -> reference` competitor. Keep that in mind when interpreting the results.

### Relative To JCTools And ConcurrentHashMap

| Scenario | Comparison | Result |
|---|---|---:|
| Hot `get(existing)` vs JCTools | `630.64 / 113.40` | `5.6x` faster |
| Hot `get(existing)` vs ConcurrentHashMap | `630.64 / 121.71` | `5.2x` faster |
| Hot mixed 90/10 vs JCTools | `284.29 / 106.82` | `2.7x` faster |
| Hot mixed 90/10 vs ConcurrentHashMap | `284.29 / 86.73` | `3.3x` faster |
| Hot contended `put(existing)` vs JCTools | `79.14 / 103.98` | `0.76x`, JCTools faster |
| Hot striped `put(existing)` vs JCTools | `399.57 / 257.85` | `1.55x` faster |
| Hot striped `setExisting` vs JCTools replace | `415.30 / 320.63` | `1.30x` faster |

Summary: `ConcurrentLong2ReferenceMap` dominates read-heavy and mixed workloads. In the synthetic worst case where all threads repeatedly overwrite the same small key set, JCTools still wins. In striped overwrite, where threads operate on separate key ranges, `ConcurrentLong2ReferenceMap` wins because it performs direct page-slot access without hashing or probing.

## Benchmark Commands

Compile tests and benchmark classes:

```powershell
.\gradlew.bat test benchmarkClasses
```

Quick full run:

```powershell
.\gradlew.bat benchmark `
  "-PbenchmarkInclude=Long2ReferenceMapConcurrentBenchmark" `
  "-PbenchmarkArgs=-wi 1 -i 3 -f 1" `
  "-PbenchmarkResultFormat=csv" `
  "-PbenchmarkResultFile=build/reports/jmh/long2reference-map-concurrent.csv"
```

More serious run:

```powershell
.\gradlew.bat benchmark `
  "-PbenchmarkInclude=Long2ReferenceMapConcurrentBenchmark" `
  "-PbenchmarkArgs=-wi 5 -i 10 -f 2" `
  "-PbenchmarkResultFormat=csv" `
  "-PbenchmarkResultFile=build/reports/jmh/long2reference-map-concurrent-full.csv"
```

Separate read-path regression check:

```powershell
.\gradlew.bat benchmark `
  "-PbenchmarkInclude=Long2ReferenceMapConcurrentBenchmark.mt_get_existing" `
  "-PbenchmarkArgs=-wi 1 -i 3 -f 1" `
  "-PbenchmarkResultFormat=csv" `
  "-PbenchmarkResultFile=build/reports/jmh/regression-get.csv"
```

## Examples

### Entity Store

```java
import net.sixik.concurrent_library.collections.maps.long2reference.ConcurrentLong2ReferenceMap;

record EntityView(long id, int x, int y) {}

ConcurrentLong2ReferenceMap<EntityView> entities = new ConcurrentLong2ReferenceMap<>(0L, 1_000_000L);

void spawn(long entityId, int x, int y) {
    entities.put(entityId, new EntityView(entityId, x, y));
}

EntityView read(long entityId) {
    return entities.get(entityId);
}

void move(long entityId, int x, int y) {
    entities.put(entityId, new EntityView(entityId, x, y));
}

void despawn(long entityId) {
    entities.remove(entityId);
}
```

### Moving Hot Range

```java
ConcurrentLong2ReferenceMap<ChunkState> chunks = new ConcurrentLong2ReferenceMap<>(playerChunkBase, 16_384L, 12);

// The active chunk id range moved.
chunks.resize(newPlayerChunkBase, 16_384L);
```

Mappings outside the new hot range remain valid. They simply use the cold radix path until they become hot again.

### Cache With `computeIfAbsent`

```java
ConcurrentLong2ReferenceMap<Model> models = new ConcurrentLong2ReferenceMap<>(0L, 1_000_000L);

Model model = models.computeIfAbsent(modelId, id -> loadModel(id));
```

### Conditional Replace

```java
Session oldSession = sessions.get(sessionId);
Session newSession = oldSession.withStatus("CLOSED");

boolean changed = sessions.replace(sessionId, oldSession, newSession);
```

### Fast Known-Present Overwrite

```java
// Setup phase owns key lifecycle.
states.put(entityId, initialState);

// Hot update phase only replaces existing values.
boolean ok = states.setExisting(entityId, nextState);

if (!ok) {
    // Missing key here means a lifecycle bug or a caller-side race.
    states.put(entityId, nextState);
}
```

If absence is a normal case, use `put`, not `setExisting`.

## When To Use

Use `ConcurrentLong2ReferenceMap` when:

- keys are primitive `long` values;
- values are Java references;
- `null` can mean absence;
- you need mutable map-like behavior;
- keys can be sparse or distant;
- the active hot range can move;
- read throughput and mixed read/write throughput matter;
- you want to avoid `Long` boxing in the hot path.

Good fits:

- `entityId -> EntityState`;
- `instrumentId -> OrderBook`;
- `sessionId -> SessionView`;
- `chunkId -> ChunkState`;
- `connectionId -> ConnectionContext`;
- `nativeHandle -> JavaWrapper`.

## When Not To Use

Prefer another structure when:

- keys are not `long`;
- sorted iteration is required;
- real `null` values must be stored directly;
- every thread constantly overwrites the same tiny key set and JCTools already wins that contention pattern;
- the key range is compact, known in advance, and never changes, where fixed-range `Long2ReferenceTable` may be simpler and faster;
- strict snapshot or transactional traversal semantics are required.

## Practical Tuning

Start with defaults:

```java
ConcurrentLong2ReferenceMap<Value> map = new ConcurrentLong2ReferenceMap<>(baseKey, capacity);
```

Tune only after measuring.

Rules of thumb:

- keep the hot range around the keys that are read and written most often;
- call `resize(...)` when the active working set moves;
- keep `pageBits(12)` until memory profiling shows a problem;
- use `setExisting` only for lifecycle-owned overwrite loops;
- publish immutable values or replace references wholesale;
- use the primitive API in hot code and keep `asMapView()` for integration boundaries;
- check regressions with separate `get`, `mixed`, contended overwrite, and striped overwrite runs.

## Positioning

`ConcurrentLong2ReferenceMap` is not a fixed direct table with `resize` bolted on.

It is a dynamic sparse map with a direct hot accelerator.

That gives a useful balance:

- much faster reads and mixed workloads than general-purpose maps;
- safer memory behavior than fixed direct tables for sparse distant keys;
- cheap hot-range changes;
- familiar enough API for application code;
- expert overwrite path when the caller can accept a narrower contract.

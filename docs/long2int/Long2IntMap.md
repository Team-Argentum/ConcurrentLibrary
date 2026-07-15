# Long2IntMap

`Long2IntMap` is a concurrent dynamic `long -> int` map backed by direct off-heap memory.

It is the mutable concurrent part of the `Long2Int` collection family. It is built for primitive keys, primitive values, no boxing in hot paths, and direct low-level control over probing, publication, resize, and reclamation.

Core idea:

```text
storage      = direct-memory open-addressed table
metadata     = byte control array for SWAR probing
entry slot   = 16 bytes: long key, int value, int state
growth       = writer-assisted migration into a new primary table
deletion     = delete marker for the same key, cleaned by rebuild/resize
reclamation  = either retain retired tables until close() or hazard-protected reclaim
```

`Long2IntMap` is not a Java `Map<Long, Integer>` replacement. It intentionally exposes a narrower primitive API so hot operations avoid key boxing, value boxing, iterator machinery, node allocation, and general-purpose map overhead.

## Why It Exists

General-purpose concurrent maps are flexible, but `long -> int` hot paths pay costs that are avoidable when the key and value types are fixed:

- boxed `Long` keys and boxed `Integer` values;
- object allocation and GC pressure;
- general-purpose hashing and node/table layouts;
- reference chasing instead of dense direct-memory slots;
- APIs that must support nullable object semantics, views, and iterators.

`Long2IntMap` keeps the surface narrow:

- primitive-only `long` and `int` operations;
- no heap allocation in core point operations;
- off-heap control and entry arrays;
- SWAR control-byte scanning;
- per-slot atomic state transitions;
- dynamic resize and delete support;
- optional managed reclamation for bounded retired direct memory.

## Quick Start

```java
import net.sixik.concurrent_library.collections.maps.long2int.ConcurrentLong2IntMap;
import net.sixik.concurrent_library.collections.maps.long2int.Long2IntMap;

Long2IntMap counts = new ConcurrentLong2IntMap(1_000_000);

counts.put(42L, 7);

int current = counts.getOrDefault(42L, -1);

counts.addIfPresent(42L, 3);

boolean changed = counts.compareAndSet(42L, 10, 11);

int removed = counts.removeAndGetOld(42L, -1);

counts.close();
```

`expectedSize` is an expected number of mappings, not the exact internal capacity. The implementation rounds to a power-of-two table sized for the load factor policy.

```java
Long2IntMap map = new ConcurrentLong2IntMap(100_000);
System.out.println(map.capacity());
```

## Constructors

`Long2IntMap` is an interface, so it cannot be instantiated directly. Use concrete public constructors for the primary API:

```java
Long2IntMap fastest = new ConcurrentLong2IntMap(expectedSize);
Long2IntMap managed = new ManagedConcurrentLong2IntMap(expectedSize);
```

The broader family includes these related structures:

| Creation API | Type | Use case |
|---|---|---|
| `new ImmutableDirectLong2IntLookup(keys, values)` | `Long2IntLookup` | Immutable direct lookup built from arrays. |
| `new FixedDirectLong2IntAppendMap(expectedSize)` | `Long2IntAppendMap` | Fixed-capacity append/update map without remove or resize. |
| `new ConcurrentLong2IntMap(expectedSize)` | `Long2IntMap` | Fastest dynamic concurrent map; retired tables remain until `close()`. |
| `new ManagedConcurrentLong2IntMap(expectedSize)` | `Long2IntMap` | Dynamic concurrent map with hazard-pointer reclamation. |

## Dynamic Or Managed

`ConcurrentLong2IntMap` and `ManagedConcurrentLong2IntMap` share the same table layout, probing logic, mutation logic, resize protocol, and public API. They differ only in how old direct-memory tables are protected and reclaimed after resize.

### `ConcurrentLong2IntMap`

`ConcurrentLong2IntMap` is the fastest dynamic mode.

Retired tables are appended to an internal retired list and freed only when the map is closed.

Use it when:

- resize count is low or bounded;
- the map lifetime is clear;
- peak speed is the priority;
- temporary extra direct memory after resize is acceptable.

```java
try (Long2IntMap map = new ConcurrentLong2IntMap(1_000_000)) {
    map.put(1L, 10);
}
```

### `ManagedConcurrentLong2IntMap`

`ManagedConcurrentLong2IntMap` adds hazard-pointer protection around current table views.

When resize finishes, retired tables can be reclaimed once no registered thread protects a view containing that table.

Use it when:

- repeated resize/rebuild is expected;
- direct-memory retention must stay bounded;
- the workload can afford the small hazard-pointer read/write cost;
- long-running services need safer memory behavior.

```java
try (Long2IntMap map = new ManagedConcurrentLong2IntMap(1_000_000)) {
    map.put(1L, 10);
}
```

### Practical Rule

Start with `ConcurrentLong2IntMap` for maximum throughput when memory retention is acceptable. Use `ManagedConcurrentLong2IntMap` for services that resize many times or must release retired tables before `close()`.

## Data Model

Each table owns two direct-memory regions:

```text
ctrl[]    = one byte per slot, 64-byte aligned
entries[] = one 16-byte entry per slot, 64-byte aligned
```

Entry layout:

```text
entry + 0   long key
entry + 8   int value
entry + 12  int state
```

States:

| State | Meaning |
|---:|---|
| `EMPTY` | Slot has never held a key. |
| `RESERVED` | A writer owns a short insertion/reinsertion window. |
| `LIVE` | Slot contains a visible mapping. |
| `DELETE` | Slot contains a delete marker for the same key. |

Control bytes accelerate probing. They contain a compact live tag, delete tag, or zero for empty. The control byte is a search hint; slot state remains authoritative.

New unrelated keys are inserted only into true `EMPTY` slots. Deleted slots are not reused for different keys. This keeps probing and delete semantics simpler and avoids tombstone reuse races. Delete pressure is cleaned by rebuild/resize.

## Core API

| Method | Meaning |
|---|---|
| `get(long key)` | Returns the current value or `0` when absent. |
| `getOrDefault(long key, int defaultValue)` | Returns the value or the provided fallback when absent. |
| `containsKey(long key)` | Checks whether a live mapping exists. |
| `size()` | Concurrently observed mapping count; not a linearizable global snapshot. |
| `put(long key, int value)` | Inserts or updates. Returns `true` unless a fixed-capacity ancestor cannot accept a new key. |
| `putIfAbsent(long key, int value)` | Inserts only when absent. Returns `true` if inserted. |
| `compareAndSet(long key, int expected, int update)` | Atomic value CAS for a live existing key. |
| `replace(long key, int expected, int update)` | Alias for conditional replacement. |
| `addIfPresent(long key, int delta)` | Adds only when the key is already present. Does not insert implicit zero. |
| `getAndAddIfPresent(long key, int delta, int missingReturn)` | Atomic get-and-add for existing key, or fallback when absent. |
| `remove(long key)` | Removes a live mapping if present. |
| `removeAndGetOld(long key, int missingReturn)` | Removes and returns the previous value, or fallback when absent. |
| `capacity()` | Current primary table capacity. During resize this does not include the old table. |
| `resizeInProgress()` | Returns whether writer-assisted migration is active. |
| `completeResize()` | Completes current resize/migration in the caller thread. |
| `close()` | Frees direct memory owned by the map. Required for deterministic cleanup. |

Diagnostics are intentionally outside the hot interface:

```java
Long2IntStats stats = map.inspector().stats();
Long2IntHealth health = map.inspector().healthCheck();
long exactSize = map.inspector().exactSize();
```

## Missing Value Policy

`int` cannot use `null` to represent absence. The default `get(long key)` therefore returns `0` for missing keys.

Use `getOrDefault` whenever `0` is a valid domain value:

```java
static final int MISSING = Integer.MIN_VALUE;

int value = map.getOrDefault(key, MISSING);

if (value == MISSING) {
    // absent
}
```

For removals, prefer `removeAndGetOld(key, missingReturn)` when the old value matters:

```java
int old = map.removeAndGetOld(key, MISSING);
```

## Updates And Counters

### Normal `put`

Use `put` for normal map semantics:

```java
map.put(accountId, balance);
```

For an existing clean key in a non-resizing table, the implementation first tries a fast overwrite path. If that path cannot prove the key is still live in the current view, it falls back to the full mutation path.

`put` is safe for inserts and updates. It handles size accounting, resize checks, delete markers, and view changes.

### Conditional CAS

Use `compareAndSet` or `replace` when the caller needs atomic conditional replacement:

```java
boolean ok = map.compareAndSet(accountId, oldBalance, newBalance);
```

This returns `false` when the key is absent or the current value differs from `expected`.

### Add If Present

Use add-if-present operations for counters where absent keys must not silently become zero:

```java
boolean updated = map.addIfPresent(counterId, 1);
int previous = map.getAndAddIfPresent(counterId, 1, Integer.MIN_VALUE);
```

These operations deliberately do not insert absent keys. If missing should mean zero, initialize the key explicitly first:

```java
map.putIfAbsent(counterId, 0);
map.addIfPresent(counterId, 1);
```

## Remove And Resize

`remove` turns a live slot into a delete marker for the same key. The slot is not reused for unrelated keys.

Resize/rebuild can start when:

- used slots approach the table load threshold;
- delete markers become too common;
- insertion cannot find an acceptable empty slot.

Migration is writer-assisted. Threads that encounter an active resize help copy chunks from the old table to the new primary table. `completeResize()` lets a caller finish the migration explicitly, which is useful after bulk inserts or before read-heavy phases.

```java
for (int i = 0; i < 1_000_000; i++) {
    map.put(i, i);
}

map.completeResize();
```

## Concurrency Model

Point operations are thread-safe.

The map uses per-slot state transitions and volatile/CAS operations around values and states. Inserts reserve a slot, publish key/value/control metadata in a defined order, and then publish the live state. Readers validate state and key before returning a value.

Important semantics:

- no Java heap allocation in core point operations;
- no global write lock or shard lock on the single-key hot paths;
- `size()` is concurrently observed, not a linearizable global snapshot;
- traversal APIs are not part of `Long2IntMap`;
- direct memory must be released with `close()`;
- operations after `close()` throw `IllegalStateException`.

`ConcurrentLong2IntMap` protects old tables by retaining them until close. `ManagedConcurrentLong2IntMap` protects old tables with hazard pointers and frees retired tables once safe.

## Diagnostics

Use `map.inspector()` for statistics and health checks:

```java
Long2IntInspector inspector = map.inspector();

Long2IntStats stats = inspector.stats();
Long2IntHealth health = inspector.healthCheck();
long exact = inspector.exactSize();
```

`Long2IntStats` fields:

| Field | Meaning |
|---|---|
| `size` | Current concurrently observed size counter. |
| `exactSizeIfComputed` | Exact size when supplied by a caller; currently `-1` for normal stats. |
| `capacity` | Combined visible table capacity for dynamic maps. |
| `shards` | Always `1` for this implementation family. |
| `offHeapBytes` | Direct memory used by visible table storage. |
| `retiredBytes` | Direct memory held by retired tables. |
| `deleteCount` | Number of delete markers. |
| `usedSlots` | Slots that are live or deleted. |
| `loadFactor` | Used slots divided by capacity. |
| `resizeInProgress` | Whether migration is active. |
| `resizeCount` | Number of grow resizes. |
| `rebuildCount` | Number of same-capacity rebuilds. |
| `reservedWaitCount` | Waits on short `RESERVED` slot windows. |
| `reservedParkCount` | Yield events during reserved-slot waits. |
| `failedCasCount` | Failed CAS attempts counted by mutation paths. |

`healthCheck()` verifies internal table invariants such as alignment and control/state consistency. It is intended for tests and diagnostics, not hot code.

## Performance

The numbers below are from focused JMH regression runs in this project:

```text
JDK: GraalVM JDK 25.0.3
JMH: throughput mode
Threads: 4
Warmup/measurement: -wi 3 -i 5 -w 1s -r 1s -f 1
Benchmark include: Long2IntBenchmark.long2int_managed_multithread
Result file: build/reports/jmh/long2int-managed-final-no-fence.json
```

### Managed Throughput, M ops/s

| Scenario | Data set | Fill ratio | Throughput |
|---|---|---:|---:|
| `get(existing)` | synthetic | 0.50 | 33.86 |
| `get(existing)` | synthetic | 0.90 | 35.72 |
| `get(existing)` | large | 0.50 | 26.80 |
| `get(existing)` | large | 0.90 | 27.45 |
| `put(existing)` | synthetic | 0.50 | 34.26 |
| `put(existing)` | synthetic | 0.90 | 34.20 |
| `put(existing)` | large | 0.50 | 34.34 |
| `put(existing)` | large | 0.90 | 24.39 |

Large-data runs were noisy in the latest focused measurements. Treat these as regression-check numbers. For final comparisons against JCTools or other libraries, increase forks and measurement time.

## Benchmark Commands

Compile tests and benchmark classes:

```powershell
.\gradlew.bat test benchmarkClasses
```

Focused managed run:

```powershell
.\gradlew.bat benchmark `
  "-PbenchmarkInclude=Long2IntBenchmark.long2int_managed_multithread" `
  "-PbenchmarkResultFile=build/reports/jmh/long2int-managed-final-no-fence.json" `
  "-PbenchmarkArgs=-wi 3 -i 5 -r 1s -w 1s -f 1"
```

More serious comparison run:

```powershell
.\gradlew.bat benchmark `
  "-PbenchmarkInclude=Long2IntBenchmark" `
  "-PbenchmarkResultFile=build/reports/jmh/long2int-full.json" `
  "-PbenchmarkArgs=-wi 5 -i 10 -r 2s -w 2s -f 3"
```

Use the same JVM, same Blackhole mode, same thread count, and same benchmark parameters when comparing with JCTools or Trivago wrappers.

## Examples

### Frequency Counter With Explicit Initialization

```java
Long2IntMap counts = new ConcurrentLong2IntMap(1_000_000);

void register(long id) {
    counts.putIfAbsent(id, 0);
}

void increment(long id) {
    if (!counts.addIfPresent(id, 1)) {
        counts.putIfAbsent(id, 1);
    }
}

int read(long id) {
    return counts.getOrDefault(id, 0);
}
```

### Score Table

```java
static final int MISSING_SCORE = Integer.MIN_VALUE;

Long2IntMap scores = new ManagedConcurrentLong2IntMap(100_000);

scores.put(playerId, 1200);

int score = scores.getOrDefault(playerId, MISSING_SCORE);

boolean promoted = scores.compareAndSet(playerId, 1200, 1300);

int removed = scores.removeAndGetOld(playerId, MISSING_SCORE);
```

### Bulk Load Then Read Phase

```java
Long2IntMap index = new ConcurrentLong2IntMap(10_000_000);

for (int i = 0; i < keys.length; i++) {
    index.put(keys[i], values[i]);
}

index.completeResize();

// Read-heavy phase starts after migration is complete.
int value = index.getOrDefault(queryKey, -1);
```

### Managed Long-Running Service

```java
Long2IntMap map = new ManagedConcurrentLong2IntMap(1_000_000);

try {
    runService(map);
} finally {
    map.close();
}
```

## When To Use

Use `Long2IntMap` when:

- keys are primitive `long` values;
- values are primitive `int` values;
- `0` can either mean missing or you can provide an explicit missing sentinel;
- mutable concurrent point operations are required;
- direct memory lifecycle is acceptable;
- avoiding boxing and allocation is a priority;
- throughput matters more than broad Java Collections compatibility.

Good fits:

- `entityId -> componentIndex`;
- `instrumentId -> bookIndex`;
- `sessionId -> stateCode`;
- `chunkId -> loadedFlag`;
- `orderId -> priceLevelIndex`;
- `nativeHandle -> compactStatus`.

## When Not To Use

Prefer another structure when:

- keys are not `long`;
- values are not naturally `int`;
- sorted iteration is required;
- boxed `Map<Long, Integer>` compatibility is more important than hot-path speed;
- nullability or object values are required, where `ConcurrentLong2ReferenceMap<V>` is a better match;
- direct memory lifecycle cannot be controlled;
- strict snapshot traversal or transactional multi-key behavior is required.

## Practical Tuning

Start with a realistic expected size:

```java
Long2IntMap map = new ConcurrentLong2IntMap(expectedMappings);
```

Rules of thumb:

- call `completeResize()` after large bulk-load phases;
- use `ManagedConcurrentLong2IntMap` when repeated resize must not retain old tables until close;
- use `ConcurrentLong2IntMap` when peak speed is more important than retired-memory bounds;
- use `FixedDirectLong2IntAppendMap` when the table never removes or resizes;
- use `ImmutableDirectLong2IntLookup` for immutable data;
- use `getOrDefault(...)` with a domain-specific sentinel when `0` is a valid value;
- inspect `deleteCount`, `usedSlots`, `loadFactor`, and `retiredBytes` during stress tests;
- compare changes with JMH before keeping micro-optimizations.

## Positioning

`Long2IntMap` is a specialized primitive concurrent table, not a general collection facade.

That gives it a narrow but valuable position:

- much less allocation than boxed maps;
- compact direct-memory storage;
- fast primitive point operations;
- dynamic growth and delete support;
- explicit memory lifecycle;
- a managed variant for bounded retired table memory.

Keep the API primitive and focused. Put richer behavior in wrappers when needed, so the single-key hot paths stay small and fast.

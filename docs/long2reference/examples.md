# Long2Reference Examples

This file keeps examples short and focused. Each block shows one idea and can be pasted into a method or test after adding the imports shown here.

```java
import net.sixik.concurrent_library.long2reference.*;
```

## 1. Dense Table

Use a dense table when IDs are compact and known.

```java
record UserSession(long id, String userName) {}

DenseChecked<UserSession> sessions = Long2Reference.dense(1_000L, 128);

sessions.put(1_001L, new UserSession(1_001L, "alice"));
sessions.put(1_002L, new UserSession(1_002L, "bob"));

UserSession session = sessions.get(1_001L);
sessions.delete(1_002L);

boolean stillPresent = sessions.containsKey(1_002L); // false
```

Valid keys in this example are `1000..1127`.

## 2. Auto Layout

Use `concurrent(...)` when application code does not care whether the table is dense or paged.

```java
record Entity(long id, String name) {}

long baseId = 0L;
long capacity = 5_000_000L;

Long2Reference<Entity> entities = Long2Reference.concurrent(baseId, capacity);

entities.put(10L, new Entity(10L, "player"));
entities.putIfAbsent(11L, new Entity(11L, "npc"));

Entity entity = entities.get(10L);
```

This is the recommended default for most code.

## 3. Paged Table

Use `paged(...)` when the logical capacity can be larger than a single Java array.

```java
record Handle(long id, String resource) {}

PagedChecked<Handle> handles = Long2Reference.paged(0L, 10_000_000_000L);

long id = 8_000_000_000L;
handles.put(id, new Handle(id, "native-buffer"));

Handle handle = handles.get(id);
```

Paged tables allocate pages during construction. A large logical range still has a real memory cost.

## 4. Slot API

Use slots when the same key is accessed repeatedly in a hot path.

```java
record OrderBook(long instrumentId, long sequence) {}

DenseChecked<OrderBook> books = Long2Reference.dense(0L, 1_000_000);
long instrumentId = 42L;

int slot = books.checkedSlot(instrumentId);

books.putAt(slot, new OrderBook(instrumentId, 1L));
OrderBook current = books.getAt(slot);
books.deleteAt(slot);
```

For paged tables, use `long` slots.

```java
PagedChecked<OrderBook> books = Long2Reference.paged(0L, 10_000_000_000L);

long key = 8_000_000_000L;
long slot = books.checkedSlotLong(key);

books.putAt(slot, new OrderBook(key, 1L));
OrderBook current = books.getAt(slot);
```

## 5. Expected Invalid Keys

Use `trySlot(...)` when invalid keys are normal input, not programming errors.

```java
DenseChecked<String> table = Long2Reference.dense(100L, 10);

long candidateKey = 150L;
int slot = table.trySlot(candidateKey);

if (slot >= 0) {
    table.putAt(slot, "accepted");
}
```

For paged tables, use `trySlotLong(...)`.

```java
long slot = paged.trySlotLong(candidateKey);
if (slot >= 0L) {
    paged.putAt(slot, value);
}
```

## 6. Atomic Insert

Use `putIfAbsent(...)` when only one writer should initialize a slot.

```java
record Connection(long id, String remoteAddress) {}

Long2Reference<Connection> connections = Long2Reference.concurrent(0L, 1024L);

boolean inserted = connections.putIfAbsent(
        7L,
        new Connection(7L, "127.0.0.1:25565")
);

if (!inserted) {
    Connection existing = connections.get(7L);
}
```

Base variants reject `null` values. Use `nullable(...)` or `stateful(...)` if present-null is a real state.

## 7. Atomic Replacement

Use immutable values and replace the reference with `compareAndSet(...)`.

```java
record AccountView(long version, long balance) {}

Long2Reference<AccountView> accounts = Long2Reference.concurrent(0L, 128L);
accounts.put(3L, new AccountView(1L, 100L));

for (;;) {
    AccountView oldValue = accounts.get(3L);
    AccountView newValue = new AccountView(
            oldValue.version() + 1L,
            oldValue.balance() - 25L
    );

    if (accounts.compareAndSet(3L, oldValue, newValue)) {
        break;
    }
}
```

This avoids mutating an object that other threads may already see.

## 8. Delete vs Remove

Use `delete(...)` when the old value is not needed.

```java
table.delete(key);
```

Use `remove(...)` when cleanup needs the old object.

```java
Resource removed = table.remove(key);
if (removed != null) {
    removed.close();
}
```

`remove(...)` is an atomic get-and-clear operation, so it is heavier than `delete(...)`.

## 9. Nullable Values

Use `nullable(...)` when `null` is a real stored value.

```java
NullableLong2Reference<String> table = Long2Reference.nullable(0L, 8L);

table.put(1L, null);

boolean present = table.containsKey(1L); // true
String value = table.get(1L);            // null
long count = table.countByScan();        // 1
```

With nullable tables, `get(key) == null` does not prove the key is absent. Use `containsKey(key)`.

## 10. Stateful Values

Use `stateful(...)` when the domain needs to distinguish never-set, present, present-null, and deleted.

```java
StatefulLong2Reference<String> table = Long2Reference.stateful(0L, 8L);

Long2ReferenceState initial = table.state(3L); // NEVER_SET

table.put(3L, null);
Long2ReferenceState withNull = table.state(3L); // PRESENT_NULL

table.delete(3L);
Long2ReferenceState deleted = table.state(3L); // DELETED

table.putIfAbsent(3L, "after-delete");
Long2ReferenceState present = table.state(3L); // PRESENT

String value = table.get(3L); // after-delete
```

Stateful tables use sentinel references internally. They are more expressive than base tables but less minimal.

## 11. Exact Size

Use `counting(...)` when frequent exact size checks matter more than write speed.

```java
CountingLong2Reference<String> table = Long2Reference.counting(0L, 8L);

boolean emptyBefore = table.isEmpty(); // true

table.put(2L, "two");
table.put(2L, "replacement");

long sizeAfterPut = table.size(); // 1

table.remove(2L);
long sizeAfterRemove = table.size(); // 0
```

Counting tables use CAS loops and an `AtomicLong`, so writes are more expensive than in base variants.

## 12. Runtime Table Replacement

Use `Long2Reference.Ref` when a table reference can be replaced while readers are running.

```java
record Item(long id, String name) {}

Long2Reference.Ref<Long2Reference<Item>> ref = Long2Reference.ref(
        Long2Reference.concurrent(0L, 1024L)
);

Long2Reference<Item> oldTable = ref.get();
Long2Reference<Item> newTable = Long2Reference.concurrent(0L, 2048L);

long base = oldTable.baseKey();
long slotsToCopy = Math.min(oldTable.capacity(), newTable.capacity());

for (long slot = 0; slot < slotsToCopy; slot++) {
    long key = base + slot;
    Item item = oldTable.get(key);
    if (item != null) {
        newTable.put(key, item);
    }
}

ref.set(newTable);
```

`Ref` safely publishes the replacement. It does not migrate values for you.

## 13. Builder Variants

Use the builder only for expert configuration.

```java
Long2Reference<String> strictDense = Long2Reference.builder()
        .range(0L, 1024L)
        .dense()
        .strict()
        .build();

Long2Reference<String> plainPaged = Long2Reference.builder()
        .range(0L, 1024L)
        .paged()
        .plain()
        .build();

Long2Reference<String> nullableCounting = Long2Reference.builder()
        .range(0L, 1024L)
        .nullable()
        .counting()
        .build();
```

Use `plain()` only for single-threaded or externally synchronized access.

## 14. Diagnostics

Diagnostics are construction-time hints for memory and layout choices.

```java
Long2ReferenceDiagnostics.setSink(System.out::println);

Long2Reference<String> table = Long2Reference.builder()
        .range(0L, 100_000_000L)
        .diagnostics(true)
        .build();

table.put(1L, "ready");
```

Diagnostics are advisory. They should never be part of correctness logic.

## 15. Cross-Checking During Migration

When replacing an existing map, test a small range against a familiar implementation.

```java
DenseChecked<String> table = Long2Reference.dense(100L, 16);
Map<Long, String> expected = new ConcurrentHashMap<>();

for (long key = 100L; key < 116L; key++) {
    String value = "v" + key;
    table.put(key, value);
    expected.put(key, value);
}

for (long key = 100L; key < 116L; key++) {
    if (!expected.get(key).equals(table.get(key))) {
        throw new AssertionError("Mismatch at key " + key);
    }
}
```

This is useful when replacing a map inside one subsystem.

package net.sixik.concurrent_library.pools;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

public abstract class LockFreeObjectPool<T> implements ObjectPool<T> {

    public static final int DEFAULT_SIZE = 32;

    protected static class Node<T> {
        final T item;

        @Nullable Node<T> next;

        Node(T item) {
            this.item = item;
        }

        Node(T item, @Nullable Node<T> next) {
            this.item = item;
            this.next = next;
        }
    }

    protected final AtomicReference<Node<T>> head = new AtomicReference<>();

    protected final int size;

    public LockFreeObjectPool() {
        this(DEFAULT_SIZE);
    }

    public LockFreeObjectPool(int size) {
        this.size = size;
        Node<T> currentHead = null;
        for (int i = 0; i < size; i++) {
            currentHead = new Node<>(createNew(), currentHead);
        }
        head.set(currentHead);
    }

    @Override
    public T alloc() {
        while (true) {
            Node<T> currentHead = head.get();
            if (currentHead == null) {
                T newObj = createNew();
                preInit(newObj);
                return newObj;
            }
            Node<T> newHead = currentHead.next;
            if (head.compareAndSet(currentHead, newHead)) {
                T obj = currentHead.item;
                preInit(obj);
                return obj;
            }
        }
    }

    @Override
    public void release(T object) {
        postInit(object);
        Node<T> newHead = new Node<>(object);
        while (true) {
            Node<T> currentHead = head.get();
            newHead.next = currentHead;
            if (head.compareAndSet(currentHead, newHead)) {
                return;
            }
        }
    }
}

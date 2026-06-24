package net.sixik.concurrent_library.pools;

public abstract class SynchronizedObjectPool<T> extends SingleTreadObjectPool<T> {

    public SynchronizedObjectPool() {
        this(DEFAULT_SIZE);
    }

    public SynchronizedObjectPool(int size) {
        super(size);
    }

    @Override
    public T alloc() {
        final T object;
        synchronized (this) {
            if (this.allocated >= this.size) {
                object = createNew();
                return object;
            }

            final int index = this.allocated++;
            object = (T) this.objects[index];
            this.objects[index] = null;
        }

        preInit(object);
        return object;
    }

    @Override
    public void release(T object) {
        synchronized (this) {
            if (this.allocated == 0) {
                return;
            }

            postInit(object);
            this.objects[--this.allocated] = object;
        }
    }
}

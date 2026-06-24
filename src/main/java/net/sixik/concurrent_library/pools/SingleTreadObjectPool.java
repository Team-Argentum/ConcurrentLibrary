package net.sixik.concurrent_library.pools;

public abstract class SingleTreadObjectPool<T> implements ObjectPool<T> {

    protected final int size;
    protected final Object[] objects;
    protected int allocated = 0;

    public SingleTreadObjectPool() {
        this(DEFAULT_SIZE);
    }

    public SingleTreadObjectPool(int size) {
        this.size = size;
        this.objects = new Object[size];
        for (int i = 0; i < size; i++) {
            this.objects[i] = createNew();
        }
    }

    @Override
    public T alloc() {
        final T object;
        if (this.allocated >= this.size) {
            object = createNew();
            return object;
        }

        final int index = this.allocated++;
        object = (T) this.objects[index];
        this.objects[index] = null;

        preInit(object);
        return object;
    }

    @Override
    public void release(T object) {
        if (this.allocated == 0) {
            return;
        }

        postInit(object);
        this.objects[--this.allocated] = object;
    }
}

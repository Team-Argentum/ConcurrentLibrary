package net.sixik.concurrent_library.eventbus;

public class EventPtrInvoker<T> extends EventPtr<T> {

    public EventPtrInvoker(DODEventBus.EventType<T> eventType) {
        super(eventType);
    }

    public void invoke(T event) {
        DODEventBus.DEFAULT_BUS.fire(eventType, event);
    }
}

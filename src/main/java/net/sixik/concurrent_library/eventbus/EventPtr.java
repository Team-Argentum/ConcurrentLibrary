package net.sixik.concurrent_library.eventbus;

public class EventPtr<T> {

    protected final DODEventBus.EventType<T> eventType;

    public EventPtr(DODEventBus.EventType<T> eventType) {
        this.eventType = eventType;
    }

    public void subscribe(DODEventBus.EventListener<T> listener) {
        DODEventBus.DEFAULT_BUS.subscribe(eventType, listener);
    }

    public void unsubscribe(DODEventBus.EventListener<T> listener) {
        DODEventBus.DEFAULT_BUS.unsubscribe(eventType, listener);
    }
}

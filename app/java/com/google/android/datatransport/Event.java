package com.google.android.datatransport;

/** 空实现（stub）：承载 payload，但不会被真正发送。 */
public final class Event<T> {
    private final T payload;

    private Event(T payload) {
        this.payload = payload;
    }

    public static <T> Event<T> ofData(T payload) {
        return new Event<>(payload);
    }

    public T getPayload() {
        return payload;
    }
}

package com.google.android.datatransport.runtime;

import android.content.Context;

import com.google.android.datatransport.Encoding;
import com.google.android.datatransport.Event;
import com.google.android.datatransport.Transport;
import com.google.android.datatransport.TransportFactory;
import com.google.android.datatransport.Transformer;

/**
 * 空实现（stub）：MediaPipe 的 RemoteLoggingClient 构造时会调用
 * TransportRuntime.initialize(context) / getInstance() / newFactory(destination)，
 * 这里全部返回「丢弃一切」的对象，保证不联网、不上报、不抛错。
 */
public final class TransportRuntime {

    private static final TransportRuntime INSTANCE = new TransportRuntime();
    private static final TransportFactory NOOP_FACTORY = new TransportFactory() {
        @Override
        public <T> Transport<T> getTransport(String name, Class<T> payloadType,
                                             Encoding encoding, Transformer<T, byte[]> transformer) {
            return event -> { /* 离线：丢弃 */ };
        }
    };

    private TransportRuntime() {
    }

    public static void initialize(Context context) {
        // no-op
    }

    public static TransportRuntime getInstance() {
        return INSTANCE;
    }

    public TransportFactory newFactory(Destination destination) {
        return NOOP_FACTORY;
    }
}

package com.google.android.datatransport;

/** 空实现（stub）：只保留 MediaPipe 实际调用的 4 参签名。 */
public interface TransportFactory {
    <T> Transport<T> getTransport(String name, Class<T> payloadType,
                                  Encoding encoding, Transformer<T, byte[]> transformer);
}

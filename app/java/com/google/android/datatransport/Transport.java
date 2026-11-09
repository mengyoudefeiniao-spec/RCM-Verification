package com.google.android.datatransport;

/**
 * 空实现（stub）：本 App 完全离线，不需要任何遥测上报。
 * MediaPipe Tasks 的 TaskRunner 会无条件创建 TasksStatsProtoLogger → RemoteLoggingClient
 * → datatransport，这里用等价签名的空实现替代真实依赖，保证调用成功且不联网。
 */
public interface Transport<T> {
    void send(Event<T> event);
}

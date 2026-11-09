package com.google.android.datatransport;

/** 空实现（stub）：MediaPipe 用它把 proto 编码成字节，这里原样不调用。 */
public interface Transformer<T, U> {
    U apply(T value);
}

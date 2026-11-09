package com.google.android.datatransport.cct;

import com.google.android.datatransport.runtime.Destination;

/** 空实现（stub）：只是一个上报目标常量，配合 TransportRuntime 使用。 */
public final class CCTDestination implements Destination {

    public static final CCTDestination INSTANCE = new CCTDestination();

    private CCTDestination() {
    }
}

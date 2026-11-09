package com.google.android.datatransport;

/** 空实现（stub）。 */
public final class Encoding {
    private final String name;

    private Encoding(String name) {
        this.name = name;
    }

    public static Encoding of(String name) {
        return new Encoding(name);
    }

    public String getName() {
        return name;
    }
}

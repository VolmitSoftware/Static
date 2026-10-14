package com.volmit.staticstats.privacy;

import java.util.Locale;

public enum Visibility {
    PRIVATE,
    SERVER,
    PUBLIC;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}

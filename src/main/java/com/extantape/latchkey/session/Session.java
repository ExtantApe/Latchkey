package com.extantape.latchkey.session;

import java.util.UUID;

/** A signed-in browser: who it is, when it stops working, and when it signed in (Unix seconds). */
public record Session(UUID uuid, String name, long expires, long since) {
    public boolean live(long nowSeconds) { return nowSeconds < expires; }
}

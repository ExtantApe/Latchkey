package com.extantape.latchkey.session;

import java.util.UUID;

/** A signed-in browser: who it is, and when it stops working (Unix seconds). */
public record Session(UUID uuid, String name, long expires) {
    public boolean live(long nowSeconds) { return nowSeconds < expires; }
}

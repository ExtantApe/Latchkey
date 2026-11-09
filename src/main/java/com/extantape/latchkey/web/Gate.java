package com.extantape.latchkey.web;

import com.extantape.latchkey.invite.Invite;
import com.extantape.latchkey.setup.SafeSetup;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** What the web page needs from the server. The plugin does these on the server thread; tests use a fake. */
public interface Gate {
    /** Whitelists a redeemed invite's player and tells other plugins. True once done. */
    CompletableFuture<Boolean> admit(Invite invite);

    /** Whether this player may have a browser session right now: whitelisted, and not banned. */
    CompletableFuture<Boolean> mayHaveSession(UUID uuid);

    /** Whether a part may run right now. Rechecked on every request, so turning the whitelist off takes effect at once. */
    boolean allowed(SafeSetup.Part part);
}

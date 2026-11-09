package com.extantape.latchkey.cmd;

import com.extantape.latchkey.invite.InviteStore;
import com.extantape.latchkey.session.SessionStore;
import com.extantape.signalbox.OnceFiles;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** What the commands need from the plugin and the server. Tests use a fake. */
public interface Host {
    record Profile(UUID uuid, String name) {}

    /** A Mojang profile for a name or a uuid, looked up off the server thread. Empty if there's no such player. */
    CompletableFuture<Optional<Profile>> resolve(String nameOrUuid);

    InviteStore invites();
    SessionStore sessions();
    OnceFiles once();
    Duration defaultInviteLifetime();
    boolean redemptionOpen();

    /** One line per problem, with its fix, and what's running. */
    record Status(boolean landing, boolean redemption, boolean signIn, boolean whitelist, String publicUrl, List<String> problems) {}
    Status status();

    /** Rereads config.yml and reruns every safe-setup check. */
    void reload();

    String version();

    /** Runs on the server thread, for answering after a lookup. */
    void sync(Runnable r);
}

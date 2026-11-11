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
    record Status(boolean lobby, boolean landing, boolean redemption, boolean signIn, boolean whitelist, String publicUrl, List<String> problems,
                  com.extantape.latchkey.web.LandingHealth.Result health) {}
    Status status();

    /**
     * The playit preset: the tunnel to create, and what's on this machine now. Null when public-url isn't an https://
     * address yet, since there's nothing to point a tunnel at.
     */
    record Tunnel(String publicUrl, String mapUrl, List<String> steps, com.extantape.latchkey.web.TunnelPreset.Seen seen,
                  com.extantape.latchkey.web.LandingHealth.Result health) {}
    Tunnel tunnel();

    /** Writes plugins/Latchkey/Caddyfile and returns where. Never overwrites: refuses with a reason instead. */
    String writeCaddyfile() throws Refusal;

    final class Refusal extends Exception {
        public final String code;
        public Refusal(String code, String message) { super(message); this.code = code; }
    }

    /**
     * What the plugins that own profiles (Usher) say about an invite's profiles. Asked on the server thread.
     *
     * @param asked      every question raised, answered or not, by id
     * @param unanswered the questions still needing a yes
     * @param checkedBy  which plugins looked; empty means nobody will apply the profiles
     */
    record ProfileCheck(String refusalCode, String refusalMessage, java.util.Map<String, String> asked,
                        java.util.Map<String, String> unanswered, List<String> checkedBy) {
        public static ProfileCheck nobody() { return new ProfileCheck(null, null, java.util.Map.of(), java.util.Map.of(), List.of()); }
    }
    ProfileCheck checkProfiles(UUID uuid, String name, List<String> profiles, java.util.Set<String> confirmed);

    /** Rereads config.yml and reruns every safe-setup check. */
    void reload();

    String version();

    /** Runs on the server thread, for answering after a lookup. */
    void sync(Runnable r);
}

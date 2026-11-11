package com.extantape.latchkey.cmd;

import com.extantape.latchkey.invite.Invite;
import com.extantape.latchkey.invite.InviteStore;
import com.extantape.signalbox.Answer;
import com.extantape.signalbox.Args;
import com.extantape.signalbox.Describe;
import com.extantape.signalbox.Errors;
import com.extantape.signalbox.Request;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code /latchkey …}. The same commands serve people and tools: with {@code --json <id>} each answers with one
 * Signalbox line, and without it, in plain words. Commands are the only admin interface (owner, 2026-11-09).
 *
 * <p>Invite codes never reach the server log. In game the code goes privately to the admin who asked; anywhere else it
 * goes to a once-file that only the server's user can read, and the answer only names the file.
 */
public final class LatchkeyCommands {
    public static final String P_CREATE = "latchkey.invite.create";
    public static final String P_LIST = "latchkey.invite.list";
    public static final String P_REVOKE = "latchkey.invite.revoke";
    public static final String P_SESSIONS = "latchkey.session.manage";
    public static final String P_STATUS = "latchkey.status";
    public static final String P_RELOAD = "latchkey.reload";
    public static final int MAX_LIMIT = 100;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final Host host;
    private final Logger log;

    public LatchkeyCommands(Host host, Logger log) {
        this.host = host;
        this.log = log;
    }

    public static List<Describe.Command> described() {
        return List.of(
                new Describe.Command("invite create", P_CREATE, List.of(Describe.Arg.of("player", "player"),
                        Describe.Arg.listOf("profiles", "string", List.of("none")).asOptional(), Describe.Arg.of("lifetime", "duration").asOptional()), List.of(), true),
                new Describe.Command("invite list", P_LIST, List.of(Describe.Arg.of("limit", "int").asOptional(), Describe.Arg.of("cursor", "string").asOptional()), List.of(), false),
                new Describe.Command("invite revoke", P_REVOKE, List.of(Describe.Arg.of("invite", "string")), List.of(), false),
                new Describe.Command("session list", P_SESSIONS, List.of(Describe.Arg.of("player", "player")), List.of(), false),
                new Describe.Command("session revoke", P_SESSIONS, List.of(Describe.Arg.of("player", "player")), List.of(), false),
                new Describe.Command("status", P_STATUS, List.of(), List.of(), false),
                new Describe.Command("reload", P_RELOAD, List.of(), List.of(), false),
                new Describe.Command("describe", P_STATUS, List.of(), List.of(), false));
    }

    public void run(Caller c, String[] args) {
        Request r;
        try {
            r = Request.parse(args);
        } catch (Request.Bad bad) {
            fail(c, bad.id, bad.code, bad.getMessage());
            return;
        }
        List<String> t = r.tokens();
        String a = t.isEmpty() ? "" : t.get(0);
        String b = t.size() > 1 ? t.get(1) : "";
        try {
            switch (a) {
                case "invite" -> {
                    switch (b) {
                        case "create" -> { if (allowed(c, r, P_CREATE)) create(c, r, t.subList(2, t.size())); }
                        case "list" -> { if (allowed(c, r, P_LIST)) list(c, r, t.subList(2, t.size())); }
                        case "revoke" -> { if (allowed(c, r, P_REVOKE)) revoke(c, r, t.subList(2, t.size())); }
                        default -> usage(c, r);
                    }
                }
                case "session" -> {
                    switch (b) {
                        case "list" -> { if (allowed(c, r, P_SESSIONS)) sessions(c, r, t.subList(2, t.size()), false); }
                        case "revoke" -> { if (allowed(c, r, P_SESSIONS)) sessions(c, r, t.subList(2, t.size()), true); }
                        default -> usage(c, r);
                    }
                }
                case "status" -> { if (allowed(c, r, P_STATUS) && none(c, r, t.subList(1, t.size()))) status(c, r); }
                case "reload" -> { if (allowed(c, r, P_RELOAD) && none(c, r, t.subList(1, t.size()))) { host.reload(); status(c, r); } }
                case "describe" -> {
                    if (allowed(c, r, P_STATUS) && none(c, r, t.subList(1, t.size()))) {
                        if (r.json()) c.line(Answer.ok(r.id(), Describe.body("Latchkey", host.version(), described())));
                        else for (Describe.Command d : described()) c.tell("/latchkey " + d.name() + " " + argList(d), false);
                    }
                }
                default -> usage(c, r);
            }
        } catch (Request.Bad bad) {
            fail(c, r.id(), bad.code, bad.getMessage());
        } catch (Refusal no) {
            fail(c, r.id(), no.code, no.getMessage());
        } catch (IOException | RuntimeException e) {
            log.log(Level.WARNING, "A Latchkey command failed", e);
            fail(c, r.id(), Errors.INTERNAL, "That didn't work. The server log has the details.");
        }
    }

    // --- invite create ---

    private void create(Caller c, Request r, List<String> t) throws Request.Bad, Refusal {
        if (t.isEmpty() || t.size() > 3) throw new Refusal(Errors.BAD_ARGUMENT, "Use: invite create <player> [profiles|none] [lifetime].");
        String who = Args.player(t.get(0));
        List<String> profiles = t.size() < 2 || t.get(1).equals("none") ? List.of() : Args.list(t.get(1));
        // Usher profile ids are lowercase letters, numbers, dashes and underscores (such as grown_sky).
        for (String prof : profiles) if (!prof.matches("[a-z0-9_-]{1,32}"))
            throw new Refusal(Errors.BAD_ARGUMENT, "Profile names are lowercase letters, numbers, dashes and underscores: " + prof + " isn't one.");
        Duration life = t.size() < 3 ? host.defaultInviteLifetime() : Args.duration(t.get(2));
        if (life.compareTo(InviteStore.MAX_LIFETIME) > 0 || life.toSeconds() < 60)
            throw new Refusal(Errors.BAD_ARGUMENT, "An invite lasts from 1 minute to 30 days.");
        if (!host.redemptionOpen()) {
            fail(c, r.id(), "not-open", "Invites can't be used on this server right now, so none are made. /latchkey status says why.");
            return;
        }
        host.resolve(who).whenComplete((found, err) -> host.sync(() -> {
            try {
                if (err != null || found == null) {
                    fail(c, r.id(), Errors.BUSY, "Couldn't look that player up just now. Try again in a minute.");
                } else if (found.isEmpty()) {
                    fail(c, r.id(), Errors.NOT_FOUND, "There's no Minecraft account called " + who + ".");
                } else {
                    finishCreate(c, r, found.get(), profiles, life);
                }
            } catch (IOException | RuntimeException e) {
                log.log(Level.WARNING, "Couldn't make an invite", e);
                fail(c, r.id(), Errors.INTERNAL, "That didn't work. The server log has the details.");
            }
        }));
    }

    private void finishCreate(Caller c, Request r, Host.Profile p, List<String> profiles, Duration life) throws IOException {
        java.util.Set<String> confirmed = java.util.Set.of();
        String warning = null;
        if (!profiles.isEmpty()) {
            Host.ProfileCheck check = host.checkProfiles(p.uuid(), p.name(), profiles, r.yes());
            if (check.refusalCode() != null) {
                fail(c, r.id(), "profiles-refused", check.refusalMessage());
                return;
            }
            if (!check.unanswered().isEmpty()) {
                if (r.json()) {
                    List<com.extantape.signalbox.Confirm.Question> qs = new ArrayList<>();
                    check.unanswered().forEach((id, q) -> qs.add(new com.extantape.signalbox.Confirm.Question(id, q)));
                    c.line(Answer.needs(r.id(), qs));
                } else {
                    c.tell("No invite was made yet. These need a yes first:", true);
                    check.unanswered().forEach((id, q) -> c.tell("  " + id + ": " + q, false));
                    c.tell("If that's all right, run the same command again with --yes " + String.join(",", check.unanswered().keySet()) + " at the end.", false);
                }
                return;
            }
            java.util.Set<String> kept = new java.util.TreeSet<>(r.yes());
            kept.retainAll(check.asked().keySet());
            confirmed = kept;
            if (check.checkedBy().isEmpty()) {
                warning = "No plugin checked these profiles, so they won't be applied when the invite is used: it gives lobby-only access. Is Usher installed?";
                log.warning("Invite for " + p.name() + " names profiles, but no plugin checked them. " + warning);
            }
        }
        InviteStore.Created made = host.invites().create(p.uuid(), p.name(), profiles, confirmed, life);
        Invite inv = made.invite();
        log.info("Invite " + inv.id() + " made for " + inv.name() + ".");
        Map<String, Object> f = fields(inv);
        if (warning != null) f.put("warning", warning);
        if (!r.json() && warning != null) c.tell(warning, true);
        if (r.json()) {
            String file = host.once().write(r.id(), "invite-code", made.code(), inv.expires() / 1000);
            c.line(Answer.secret(r.id(), file, f));
            return;
        }
        String profileText = inv.profiles().isEmpty() ? "lobby only" : String.join(", ", inv.profiles());
        if (c.inGame()) {
            c.tell("Invite for " + inv.name() + " (" + profileText + "), until " + WHEN.format(Instant.ofEpochMilli(inv.expires())) + ":", false);
            c.tell("Code: " + made.code(), false);
            c.tell("Shown once. Send it to them privately. Invite id " + inv.id() + ".", false);
        } else {
            // The console is logged, so the code goes to a file only the server's user can read.
            String file = host.once().write("invite-" + inv.id(), "invite-code", made.code(), inv.expires() / 1000);
            c.tell("Invite " + inv.id() + " made for " + inv.name() + " (" + profileText + "). The code is in plugins/Latchkey/"
                    + file + " for 5 minutes; it isn't shown here because the console is logged.", false);
        }
    }

    // --- invite list / revoke ---

    private void list(Caller c, Request r, List<String> t) throws Request.Bad, Refusal {
        if (t.size() > 2) throw new Refusal(Errors.BAD_ARGUMENT, "Use: invite list [limit] [cursor].");
        int limit = t.isEmpty() ? 20 : Args.integer(t.get(0));
        // The cursor is a string to the caller, as the spec says; here it's a position in the list.
        if (t.size() >= 2 && !t.get(1).matches("\\d{1,9}")) throw new Refusal(Errors.BAD_ARGUMENT, "That cursor isn't one Latchkey handed out. Use next from the last page.");
        int cursor = t.size() < 2 ? 0 : Integer.parseInt(t.get(1));
        if (limit < 1 || limit > MAX_LIMIT || cursor < 0) throw new Refusal(Errors.BAD_ARGUMENT, "limit is 1 to " + MAX_LIMIT + " and cursor 0 or more.");
        List<Invite> all = new ArrayList<>(host.invites().all());
        all.sort((x, y) -> Long.compare(y.created(), x.created()));
        List<Invite> page = all.subList(Math.min(cursor, all.size()), Math.min(cursor + limit, all.size()));
        if (r.json()) {
            c.line(Answer.page(r.id(), page.stream().map(LatchkeyCommands::fields).toList(),
                    cursor + page.size() < all.size() ? Integer.toString(cursor + page.size()) : null));
            return;
        }
        if (all.isEmpty()) { c.tell("No invites yet.", false); return; }
        long now = System.currentTimeMillis();
        for (Invite i : page) {
            c.tell(i.id() + "  " + i.name() + "  " + state(i, now) + "  " + (i.profiles().isEmpty() ? "lobby only" : String.join(",", i.profiles())), false);
        }
        if (cursor + page.size() < all.size()) c.tell("More: /latchkey invite list " + limit + " " + (cursor + page.size()), false);
    }

    private void revoke(Caller c, Request r, List<String> t) throws Request.Bad, Refusal, IOException {
        if (t.size() != 1 || !t.get(0).matches("[0-9a-f]{8}")) throw new Refusal(Errors.BAD_ARGUMENT, "Use: invite revoke <invite id>, from /latchkey invite list.");
        String id = t.get(0);
        boolean exists = host.invites().all().stream().anyMatch(i -> i.id().equals(id));
        if (!exists) { fail(c, r.id(), Errors.NOT_FOUND, "There's no invite " + id + "."); return; }
        boolean done = host.invites().revoke(id);
        if (!done) { fail(c, r.id(), "not-pending", "Invite " + id + " was already used or revoked."); return; }
        log.info("Invite " + id + " revoked.");
        if (r.json()) c.line(Answer.ok(r.id(), Map.of("invite", id, "status", "revoked")));
        else c.tell("Invite " + id + " revoked. Its code no longer works.", false);
    }

    // --- sessions ---

    private void sessions(Caller c, Request r, List<String> t, boolean revoke) throws Request.Bad, Refusal {
        if (t.size() != 1) throw new Refusal(Errors.BAD_ARGUMENT, "Use: session " + (revoke ? "revoke" : "list") + " <player>.");
        String who = Args.player(t.get(0));
        host.resolve(who).whenComplete((found, err) -> host.sync(() -> {
            try {
                if (err != null || found == null) { fail(c, r.id(), Errors.BUSY, "Couldn't look that player up just now. Try again in a minute."); return; }
                if (found.isEmpty()) { fail(c, r.id(), Errors.NOT_FOUND, "There's no Minecraft account called " + who + "."); return; }
                Host.Profile p = found.get();
                if (revoke) {
                    int n = host.sessions().signOutEverywhere(p.uuid());
                    log.info("Ended " + n + " browser session(s) for " + p.name() + " by command.");
                    if (r.json()) c.line(Answer.ok(r.id(), Map.of("uuid", p.uuid().toString(), "name", p.name(), "ended", n)));
                    else c.tell(n == 0 ? p.name() + " wasn't signed in anywhere." : "Signed " + p.name() + " out of " + n + " browser" + (n == 1 ? "" : "s") + ".", false);
                } else {
                    int n = host.sessions().count(p.uuid());
                    if (r.json()) c.line(Answer.ok(r.id(), Map.of("uuid", p.uuid().toString(), "name", p.name(), "sessions", n)));
                    else c.tell(p.name() + " is signed in on " + n + " browser" + (n == 1 ? "" : "s") + ".", false);
                }
            } catch (IOException | RuntimeException e) {
                log.log(Level.WARNING, "A session command failed", e);
                fail(c, r.id(), Errors.INTERNAL, "That didn't work. The server log has the details.");
            }
        }));
    }

    // --- status ---

    private void status(Caller c, Request r) {
        Host.Status s = host.status();
        if (r.json()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lobby", s.lobby());
            m.put("landing", s.landing());
            m.put("redemption", s.redemption());
            m.put("signIn", s.signIn());
            m.put("whitelist", s.whitelist());
            m.put("publicUrl", s.publicUrl());
            m.put("problems", s.problems());
            m.put("health", healthMap(s.health()));
            c.line(Answer.ok(r.id(), m));
            return;
        }
        c.tell("Landing page: " + onOff(s.landing()) + (s.landing() && s.publicUrl() != null ? " at " + s.publicUrl() : ""), !s.landing());
        c.tell("Lobby: " + onOff(s.lobby()) + ".", false);
        c.tell("Invites: " + onOff(s.redemption()) + ". Sign-in: " + onOff(s.signIn()) + ". Whitelist: " + onOff(s.whitelist()) + ".", false);
        for (String p : s.problems()) c.tell(p, true);
        if (s.landing()) c.tell(healthLine(s.health()), s.health() != null && !s.health().healthy());
    }

    static String healthLine(com.extantape.latchkey.web.LandingHealth.Result h) {
        if (h == null) return "Landing health: not checked yet (the first check runs 20 seconds after start, if health-check-minutes isn't 0).";
        String when = WHEN.format(Instant.ofEpochMilli(h.checkedAt()));
        return h.healthy() ? "Landing health (" + when + "): answers over HTTPS from outside, and visitors can be told apart."
                : "Landing health (" + when + "): " + h.problem();
    }

    static Map<String, Object> healthMap(com.extantape.latchkey.web.LandingHealth.Result h) {
        if (h == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkedAt", Instant.ofEpochMilli(h.checkedAt()).toString());
        m.put("reachable", h.reachable());
        m.put("https", h.https());
        m.put("thisServer", h.thisServer());
        m.put("visitorsToldApart", h.visitorsTold());
        m.put("status", h.status());
        m.put("problem", h.problem());
        return m;
    }

    // --- helpers ---

    /** A command refused with a Signalbox error code and plain words. */
    static final class Refusal extends Exception {
        final String code;
        Refusal(String code, String message) { super(message); this.code = code; }
    }

    private boolean allowed(Caller c, Request r, String permission) {
        if (c.has(permission)) return true;
        fail(c, r.id(), Errors.NO_PERMISSION, "You don't have permission for that.");
        return false;
    }

    private boolean none(Caller c, Request r, List<String> extra) {
        if (extra.isEmpty()) return true;
        fail(c, r.id(), Errors.BAD_ARGUMENT, "That command takes no arguments.");
        return false;
    }

    private void usage(Caller c, Request r) {
        if (r.json()) { c.line(Answer.error(r.id(), Errors.UNKNOWN_COMMAND, "Unknown command. /latchkey describe lists them.")); return; }
        c.tell("Latchkey commands:", false);
        for (Describe.Command d : described()) if (c.has(d.permission())) c.tell("/latchkey " + d.name() + " " + argList(d), false);
    }

    private static void fail(Caller c, String id, String code, String message) {
        if (id != null) c.line(Answer.error(id, code, message));
        else c.tell(message, true);
    }

    private static String argList(Describe.Command d) {
        StringBuilder b = new StringBuilder();
        for (Describe.Arg a : d.args()) b.append(b.length() > 0 ? " " : "").append('<').append(a.name()).append('>');
        return b.toString();
    }

    private static Map<String, Object> fields(Invite i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("invite", i.id());
        m.put("uuid", i.uuid().toString());
        m.put("name", i.name());
        m.put("profiles", i.profiles());
        m.put("confirmed", new java.util.TreeSet<>(i.confirmed()));
        m.put("status", i.status().name().toLowerCase());
        m.put("created", i.created() / 1000);
        m.put("expires", i.expires() / 1000);
        return m;
    }

    private static String state(Invite i, long now) {
        if (i.status() == Invite.Status.PENDING && now >= i.expires()) return "expired";
        if (i.status() == Invite.Status.PENDING) return "open until " + WHEN.format(Instant.ofEpochMilli(i.expires()));
        return i.status().name().toLowerCase();
    }

    private static String onOff(boolean b) { return b ? "on" : "off"; }
}

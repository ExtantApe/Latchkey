package com.extantape.latchkey.web;

import com.extantape.latchkey.SignInLinks;
import com.extantape.latchkey.invite.Invite;
import com.extantape.latchkey.invite.InviteStore;
import com.extantape.latchkey.session.Session;
import com.extantape.latchkey.session.Handoffs;
import com.extantape.latchkey.session.SessionStore;
import com.extantape.latchkey.setup.SafeSetup;
import com.extantape.latchkey.setup.SafeSetup.Part;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The landing page: redeem an invite, sign a browser in with a link from the game, sign out. Built on the JDK's own HTTP
 * server, so nothing extra ships. Only run when {@link SafeSetup} allows {@link Part#LANDING}.
 *
 * <p>Every Latchkey response carries the same security headers. Forms need a CSRF token and, if the browser says where
 * the post came from, it must be this site. Invite and sign-in tries are rate limited, and every failed invite gets the
 * same answer after the same minimum time. Nothing personal is logged.
 */
public final class LandingServer {
    public static final String SESSION_COOKIE = "latchkey_session";
    public static final String MAP_AUDIENCE = "map";
    static final Duration MIN_ANSWER = Duration.ofMillis(400);
    private static final int MAX_BODY = 4096;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Set<String> HOP = Set.of("connection", "keep-alive", "proxy-connection", "transfer-encoding", "te",
            "trailer", "upgrade", "host", "content-length", "expect", "http2-settings");

    private final SafeSetup.Settings settings;
    private final PageSettings page;
    private final InviteStore invites;
    private final SessionStore sessions;
    private final SignInLinks links;
    private final Gate gate;
    private final Logger log;
    private final LongSupplier clock;
    private final RateLimiter limiter;
    private final ClientAddress clients;
    private final Csrf csrf = new Csrf();
    private final Handoffs handoffs;
    private volatile LandingHealth health;
    private final String origin;
    /** The path everything lives under: "" at the root of the host, or such as "/join". */
    private final String base;
    private final String cookiePath;
    private HttpServer server;
    private ExecutorService pool;
    private HttpClient forwarder;

    public LandingServer(SafeSetup.Settings settings, InviteStore invites, SessionStore sessions, SignInLinks links,
                         Gate gate, Logger log, LongSupplier clockMillis) {
        this(settings, PageSettings.none(), invites, sessions, links, gate, log, clockMillis, new Handoffs(clockMillis));
    }

    public LandingServer(SafeSetup.Settings settings, PageSettings page, InviteStore invites, SessionStore sessions, SignInLinks links,
                         Gate gate, Logger log, LongSupplier clockMillis, Handoffs handoffs) {
        this.handoffs = handoffs;
        this.settings = settings;
        this.invites = invites;
        this.sessions = sessions;
        this.links = links;
        this.gate = gate;
        this.log = log;
        this.clock = clockMillis;
        this.limiter = new RateLimiter(settings.perIpPer10Minutes(), settings.siteWidePerMinute(), clockMillis);
        this.clients = new ClientAddress(settings.trustedProxies());
        URI u = settings.publicUrl();
        this.origin = u.getScheme() + "://" + u.getRawAuthority();
        this.base = settings.base();
        this.cookiePath = base.isEmpty() ? "/" : base;
        this.page = page.withBase(base);
    }

    public void start() throws IOException {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral(settings.bind()), settings.port()), 64);
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        if (!settings.forward().isEmpty()) {
            forwarder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                    .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
        }
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    /** Lets the health check's one-off request through. Null turns that path into a plain 404. */
    public void health(LandingHealth h) { this.health = h; }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
        if (forwarder != null) forwarder.close();
    }

    private void handle(HttpExchange x) {
        // Closed in finally, not by try-with-resources: that closes it before the catch below can still answer.
        try {
            String path = x.getRequestURI().getRawPath();
            if (!base.isEmpty()) {
                // Only the page's own path is served. The bare path gets its trailing slash, so relative parts work.
                if (path.equals(base)) { if (only(x, "GET")) redirect(x, base + "/"); return; }
                if (!path.startsWith(base + "/")) { send(x, 404, Html.plain("Not found", "There's nothing here.")); return; }
                path = path.substring(base.length());
            }
            if (path.startsWith(LandingHealth.PATH)) {
                LandingHealth h = health;
                String visitor = clients.of(x.getRemoteAddress().getAddress(), x.getRequestHeaders().getFirst("X-Forwarded-For"));
                if (x.getRequestMethod().equals("GET") && h != null && h.answer(path.substring(LandingHealth.PATH.length()), visitor)) {
                    securityHeaders(x.getResponseHeaders());
                    x.sendResponseHeaders(204, -1);
                } else {
                    send(x, 404, Html.plain("Not found", "There's nothing here."));
                }
                return;
            }
            for (Map.Entry<String, URI> f : settings.forward().entrySet()) {
                if (path.equals(f.getKey()) || path.startsWith(f.getKey() + "/")) {
                    forward(x, f.getValue());
                    return;
                }
            }
            String method = x.getRequestMethod();
            switch (path) {
                case "/" -> { if (only(x, "GET")) home(x, null, ""); }
                case "/invite" -> { if (only(x, "POST")) checkCode(x); }
                case "/invite/confirm" -> { if (only(x, "POST")) redeem(x); }
                case "/logo" -> { if (only(x, "GET")) logo(x); }
                case "/signin" -> {
                    if (method.equals("GET")) signInPage(x, null, "");
                    else if (only(x, "POST")) signIn(x);
                }
                case "/signout" -> { if (only(x, "POST")) signOut(x); }
                case "/map" -> { if (only(x, "POST")) toMap(x); }
                default -> send(x, 404, Html.plain("Not found", "There's nothing here."));
            }
        } catch (RuntimeException | IOException e) {
            // Never a stack trace to the visitor. The log gets the kind of failure, not the request.
            log.log(Level.WARNING, "Landing page error: " + e.getClass().getSimpleName());
            try { send(x, 500, Html.plain("Something went wrong", "Try again in a moment.")); } catch (IOException | RuntimeException ignored) {}
        } finally {
            x.close();
        }
    }

    // --- pages ---

    private void home(HttpExchange x, String msg, String kind) throws IOException {
        String form = formCookie(x);
        Optional<Session> s = currentSession(x);
        if (s.isPresent() && msg == null) {
            // Only a signed-in browser ever gets the page with the server's address in it.
            x.getResponseHeaders().set("Cache-Control", "no-store");
            send(x, 200, Html.welcome(page, s.get().name(), csrf.tokenFor(form)), Html.cspWithMap(page.mapUrl()));
            return;
        }
        send(x, 200, Html.home(page, csrf.tokenFor(form), gate.allowed(Part.REDEMPTION), gate.allowed(Part.SIGNIN), msg, kind));
    }

    private void logo(HttpExchange x) throws IOException {
        if (page.logo() == null) { send(x, 404, Html.plain("Not found", "There's nothing here.")); return; }
        Headers h = x.getResponseHeaders();
        securityHeaders(h);
        h.set("Content-Type", page.logoType());
        h.set("Cache-Control", "public, max-age=3600");
        x.sendResponseHeaders(200, page.logo().length);
        x.getResponseBody().write(page.logo());
    }

    private void signInPage(HttpExchange x, String msg, String kind) throws IOException {
        String form = formCookie(x);
        send(x, 200, Html.signIn(page, csrf.tokenFor(form), gate.allowed(Part.SIGNIN), msg, kind));
    }

    /** Step one: a code. If it matches an open invite, say whose it is; nothing is used up yet. */
    private void checkCode(HttpExchange x) throws IOException {
        long started = System.nanoTime();
        Map<String, String> f = guardedForm(x);
        if (f == null) return;
        if (!gate.allowed(Part.REDEMPTION)) { home(x, "Invites aren't open on this server right now.", "bad"); return; }
        String code = f.getOrDefault("code", "");
        Optional<Invite> open = code.length() <= 16 ? invites.peek(code) : Optional.empty();
        waitOut(started);
        if (open.isEmpty()) {
            sendHome(x, 400, "That code doesn't match an open invite. Check it and try again.", "bad");
            return;
        }
        x.getResponseHeaders().set("Cache-Control", "no-store");
        send(x, 200, Html.confirm(page, csrf.tokenFor(formCookie(x)), open.get().name(), code));
    }

    /** Step two: "Yes, that's me". Uses the invite, lets them on the server, and signs this browser in. */
    private void redeem(HttpExchange x) throws IOException {
        long started = System.nanoTime();
        Map<String, String> f = guardedForm(x);
        if (f == null) return;
        if (!gate.allowed(Part.REDEMPTION)) { home(x, "Invites aren't open on this server right now.", "bad"); return; }
        String code = f.getOrDefault("code", "");
        Optional<Invite> used = code.length() <= 16 ? invites.redeem(code) : Optional.empty();
        if (used.isEmpty()) {
            waitOut(started);
            sendHome(x, 400, "That code doesn't match an open invite. Check it and try again.", "bad");
            return;
        }
        boolean admitted;
        try {
            admitted = gate.admit(used.get()).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            admitted = false;
        }
        waitOut(started);
        if (!admitted) {
            log.warning("Invite " + used.get().id() + " was used but the player couldn't be added to the whitelist. Add them by hand.");
            sendHome(x, 500, "Your invite worked, but the server couldn't add you. Tell whoever invited you.", "bad");
            return;
        }
        if (gate.allowed(Part.SIGNIN)) {
            SessionStore.Created c = sessions.signIn(used.get().uuid(), used.get().name(), settings.sessionLifetime(), cookie(x, SESSION_COOKIE));
            x.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + c.cookie() + "; Path=" + cookiePath + "; Max-Age="
                    + settings.sessionLifetime().toSeconds() + "; HttpOnly; Secure; SameSite=Strict");
            redirect(x, base + "/");
            return;
        }
        sendHome(x, 200, "You're in, " + used.get().name() + ". Join the server in Minecraft.", "ok");
    }

    private void signIn(HttpExchange x) throws IOException {
        long started = System.nanoTime();
        Map<String, String> f = guardedForm(x);
        if (f == null) return;
        if (!gate.allowed(Part.SIGNIN)) { signInPage(x, "Sign-in isn't open on this server right now.", "bad"); return; }
        String link = f.getOrDefault("link", "").trim();
        int hash = link.lastIndexOf('#');
        String token = hash >= 0 ? link.substring(hash + 1) : link;
        Optional<SignInLinks.Pending> p = links.consume(token, Instant.ofEpochMilli(clock.getAsLong()));
        boolean may = false;
        if (p.isPresent()) {
            try { may = gate.mayHaveSession(p.get().uuid()).get(10, TimeUnit.SECONDS); } catch (Exception e) { may = false; }
        }
        if (!may) {
            waitOut(started);
            String form = formCookie(x);
            send(x, 400, Html.signIn(page, csrf.tokenFor(form), true,
                    "That link didn't work. Each link works once, for two minutes. Type /signin in game for a new one.", "bad"));
            return;
        }
        SessionStore.Created c = sessions.signIn(p.get().uuid(), p.get().name(), settings.sessionLifetime(), cookie(x, SESSION_COOKIE));
        x.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + c.cookie() + "; Path=" + cookiePath + "; Max-Age="
                + settings.sessionLifetime().toSeconds() + "; HttpOnly; Secure; SameSite=Strict");
        redirect(x, base + "/");
    }

    /**
     * "Open the map": a one-time token for this browser's session, sent to the map's own address after {@code #} so it
     * never reaches a server log there. Only for a signed-in browser, and only when a map address is set.
     */
    private void toMap(HttpExchange x) throws IOException {
        if (page.mapUrl().isEmpty()) { send(x, 404, Html.plain("Not found", "There's nothing here.")); return; }
        if (guardedForm(x) == null) return;
        String cookie = cookie(x, SESSION_COOKIE);
        Optional<Session> s = currentSession(x);
        if (s.isEmpty()) { sendHome(x, 403, "Sign in first, then open the map.", "error"); return; }
        String token = handoffs.issue(s.get(), SessionStore.refFor(cookie), MAP_AUDIENCE);
        redirect(x, page.mapUrl() + "/handoff#" + token);
    }

    private void signOut(HttpExchange x) throws IOException {
        if (guardedForm(x) == null) return;
        sessions.signOut(cookie(x, SESSION_COOKIE));
        x.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=; Path=" + cookiePath + "; Max-Age=0; HttpOnly; Secure; SameSite=Strict");
        redirect(x, base + "/");
    }

    // --- checks every form post goes through ---

    /** The form, or null after answering for a post that's from elsewhere, too big, or over the rate limit. */
    private Map<String, String> guardedForm(HttpExchange x) throws IOException {
        Headers h = x.getRequestHeaders();
        String from = h.getFirst("Origin");
        String site = h.getFirst("Sec-Fetch-Site");
        if ((from != null && !from.equals(origin)) || (site != null && !site.equals("same-origin") && !site.equals("none"))) {
            send(x, 403, Html.plain("Not allowed", "That form came from another site."));
            return null;
        }
        String visitor = clients.of(x.getRemoteAddress().getAddress(), h.getFirst("X-Forwarded-For"));
        long wait = limiter.tryAcquire(visitor);
        if (wait > 0) {
            x.getResponseHeaders().set("Retry-After", Long.toString(wait));
            send(x, 429, Html.plain("Too many tries", "Wait " + minutes(wait) + " and try again."));
            return null;
        }
        String type = h.getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            send(x, 415, Html.plain("Not allowed", "Use the form on this page."));
            return null;
        }
        byte[] body = readUpTo(x.getRequestBody(), MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            send(x, 413, Html.plain("Too long", "Use the form on this page."));
            return null;
        }
        Map<String, String> f = parseForm(new String(body, StandardCharsets.UTF_8));
        if (f == null || !csrf.valid(cookie(x, Csrf.COOKIE), f.get("csrf"))) {
            send(x, 403, Html.plain("Page expired", "Go back, reload the page and try again."));
            return null;
        }
        return f;
    }

    private Optional<Session> currentSession(HttpExchange x) throws IOException {
        Optional<Session> s = sessions.lookup(cookie(x, SESSION_COOKIE));
        if (s.isEmpty() || !gate.allowed(Part.SIGNIN)) return Optional.empty();
        return s;
    }

    /** The visitor's form cookie, setting a new one when they have none. */
    private String formCookie(HttpExchange x) {
        String c = cookie(x, Csrf.COOKIE);
        if (Csrf.wellFormed(c)) return c;
        c = Csrf.newCookieValue();
        x.getResponseHeaders().add("Set-Cookie", Csrf.COOKIE + "=" + c + "; Path=" + cookiePath + "; HttpOnly; Secure; SameSite=Strict");
        return c;
    }

    // --- forwarding ---

    /** Passes the request through to a loopback service unchanged, adding nothing, and passes the answer back. */
    private void forward(HttpExchange x, URI target) throws IOException {
        String raw = x.getRequestURI().getRawPath() + (x.getRequestURI().getRawQuery() == null ? "" : "?" + x.getRequestURI().getRawQuery());
        URI to = URI.create(target.getScheme() + "://" + target.getRawAuthority() + raw);
        HttpRequest.Builder req = HttpRequest.newBuilder(to).timeout(Duration.ofSeconds(30));
        for (Map.Entry<String, List<String>> e : x.getRequestHeaders().entrySet()) {
            if (HOP.contains(e.getKey().toLowerCase(Locale.ROOT))) continue;
            for (String v : e.getValue()) req.header(e.getKey(), v);
        }
        String m = x.getRequestMethod();
        boolean hasBody = !(m.equals("GET") || m.equals("HEAD") || m.equals("DELETE") || m.equals("OPTIONS"));
        req.method(m, hasBody ? HttpRequest.BodyPublishers.ofInputStream(x::getRequestBody) : HttpRequest.BodyPublishers.noBody());
        HttpResponse<InputStream> res;
        try {
            res = forwarder.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(x, 502, Html.plain("Not available", "That page isn't answering. Try again in a moment."));
            return;
        } catch (IOException e) {
            send(x, 502, Html.plain("Not available", "That page isn't answering. Try again in a moment."));
            return;
        }
        for (Map.Entry<String, List<String>> e : res.headers().map().entrySet()) {
            if (HOP.contains(e.getKey().toLowerCase(Locale.ROOT)) || e.getKey().startsWith(":")) continue;
            for (String v : e.getValue()) x.getResponseHeaders().add(e.getKey(), v);
        }
        long length = res.headers().firstValueAsLong("content-length").orElse(-2);
        boolean empty = m.equals("HEAD") || res.statusCode() == 204 || res.statusCode() == 304;
        x.sendResponseHeaders(res.statusCode(), empty ? -1 : length >= 0 ? (length == 0 ? -1 : length) : 0);
        try (InputStream in = res.body(); OutputStream out = x.getResponseBody()) {
            if (!empty) in.transferTo(out);
        }
    }

    // --- small helpers ---

    private boolean only(HttpExchange x, String method) throws IOException {
        if (x.getRequestMethod().equals(method)) return true;
        x.getResponseHeaders().set("Allow", method);
        send(x, 405, Html.plain("Not allowed", "That isn't how this page works."));
        return false;
    }

    private void sendHome(HttpExchange x, int status, String msg, String kind) throws IOException {
        String form = formCookie(x);
        send(x, status, Html.home(page, csrf.tokenFor(form), gate.allowed(Part.REDEMPTION), gate.allowed(Part.SIGNIN), msg, kind));
    }

    private void redirect(HttpExchange x, String where) throws IOException {
        securityHeaders(x.getResponseHeaders());
        x.getResponseHeaders().set("Location", where);
        x.sendResponseHeaders(303, -1);
    }

    static void securityHeaders(Headers h) {
        h.set("Content-Security-Policy", Html.CSP);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cache-Control", "no-store");
        h.set("Strict-Transport-Security", "max-age=31536000");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Cross-Origin-Resource-Policy", "same-origin");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), interest-cohort=()");
    }

    private static void send(HttpExchange x, int status, String html) throws IOException {
        send(x, status, html, Html.CSP);
    }

    private static void send(HttpExchange x, int status, String html, String csp) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        securityHeaders(x.getResponseHeaders());
        x.getResponseHeaders().set("Content-Security-Policy", csp);
        x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        x.sendResponseHeaders(status, b.length);
        try (OutputStream out = x.getResponseBody()) { out.write(b); }
    }

    private static void waitOut(long startedNanos) {
        long left = MIN_ANSWER.toNanos() - (System.nanoTime() - startedNanos);
        if (left > 0) {
            try { TimeUnit.NANOSECONDS.sleep(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    static String cookie(HttpExchange x, String name) {
        List<String> all = x.getRequestHeaders().get("Cookie");
        if (all == null) return null;
        String found = null;
        for (String line : all) for (String part : line.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(name)) {
                if (found != null) return null; // Two of the same cookie: trust neither.
                found = part.substring(eq + 1).trim();
            }
        }
        return found;
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> out = new HashMap<>();
        if (body.isEmpty()) return out;
        try {
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (out.put(k, v) != null) return null; // A field given twice is someone tampering.
                for (char c : v.toCharArray()) if (c < 0x20 || c == 0x7f) return null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return out;
    }

    private static byte[] readUpTo(InputStream in, int max) throws IOException {
        return in.readNBytes(max);
    }

    private static String minutes(long seconds) {
        long m = (seconds + 59) / 60;
        return m <= 1 ? "a minute" : m + " minutes";
    }
}

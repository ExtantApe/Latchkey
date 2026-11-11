package com.extantape.latchkey.web;

import com.extantape.latchkey.SignInLinks;
import com.extantape.latchkey.invite.Invite;
import com.extantape.latchkey.invite.InviteStore;
import com.extantape.latchkey.session.Handoffs;
import com.extantape.latchkey.session.SessionStore;
import com.extantape.latchkey.setup.SafeSetup;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LandingServerTest {
    static final String ORIGIN = "https://play.example.org";
    static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(System.currentTimeMillis());
    final List<Invite> admitted = new CopyOnWriteArrayList<>();
    final Set<SafeSetup.Part> off = Collections.synchronizedSet(EnumSet.noneOf(SafeSetup.Part.class));
    final Set<UUID> barred = Collections.synchronizedSet(new java.util.HashSet<>());
    InviteStore invites;
    SessionStore sessions;
    SignInLinks links;
    LandingServer server;
    Handoffs handoffs;
    static final String MAP = "https://map.example.org";
    HttpServer backend;
    final List<Map<String, List<String>>> backendSaw = new CopyOnWriteArrayList<>();
    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    final Gate gate = new Gate() {
        public CompletableFuture<Boolean> admit(Invite invite) { admitted.add(invite); return CompletableFuture.completedFuture(true); }
        public CompletableFuture<Boolean> mayHaveSession(UUID uuid) { return CompletableFuture.completedFuture(!barred.contains(uuid)); }
        public boolean allowed(SafeSetup.Part part) { return !off.contains(part); }
    };

    @BeforeEach void up() throws Exception { start(3, 60, Map.of()); }

    void start(int perIp, int site, Map<String, URI> forward) throws Exception {
        if (server != null) server.stop();
        invites = new InviteStore(dir.resolve("data"), now::get);
        sessions = new SessionStore(dir.resolve("data/sessions"), now::get);
        links = new SignInLinks(dir.resolve("data/pending"));
        var settings = new SafeSetup.Settings("127.0.0.1", 0, URI.create(ORIGIN), List.of("127.0.0.1"), perIp, site,
                Duration.ofDays(14), Duration.ofHours(24), forward);
        server = new LandingServer(settings, invites, sessions, links, gate, Logger.getLogger("test"), now::get);
        server.start();
    }

    static final String ADDRESS = "play.secret-address.test";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};

    /** A server with the owner's page: a logo, the play address and two steps. */
    void startWithPage(int perIp) throws Exception {
        if (server != null) server.stop();
        var settings = new SafeSetup.Settings("127.0.0.1", 0, URI.create(ORIGIN), List.of("127.0.0.1"), perIp, 600,
                Duration.ofDays(14), Duration.ofHours(24), Map.of());
        var page = new PageSettings("Example Realm", PNG, "image/png", ADDRESS,
                List.of(new PageSettings.Step("Accept the resource pack", "Choose Yes when asked."), new PageSettings.Step("The Lobby", "You start here.")), MAP, List.of());
        handoffs = new Handoffs(now::get);
        server = new LandingServer(settings, page, invites, sessions, links, gate, Logger.getLogger("test"), now::get, handoffs);
        server.start();
    }

    static boolean mentionsAddress(HttpResponse<String> r) {
        return r.body().contains(ADDRESS) || r.body().contains("secret-address") || r.headers().map().toString().contains("secret-address");
    }

    @AfterEach void down() {
        server.stop();
        if (backend != null) backend.stop(0);
    }

    // --- a tiny browser with a cookie jar ---

    final class Browser {
        final Map<String, String> jar = new LinkedHashMap<>();
        String forwardedFor;

        HttpResponse<String> get(String path) throws Exception { return send(req(path).GET()); }

        HttpResponse<String> post(String path, Map<String, String> form) throws Exception {
            StringBuilder b = new StringBuilder();
            for (var e : form.entrySet()) {
                if (b.length() > 0) b.append('&');
                b.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
            return post(path, b.toString(), "application/x-www-form-urlencoded");
        }

        HttpResponse<String> post(String path, String body, String type) throws Exception {
            return send(req(path).header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body)));
        }

        HttpRequest.Builder req(String path) {
            var r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
            if (!jar.isEmpty()) {
                StringBuilder c = new StringBuilder();
                jar.forEach((k, v) -> c.append(c.length() > 0 ? "; " : "").append(k).append('=').append(v));
                r.header("Cookie", c.toString());
            }
            if (forwardedFor != null) r.header("X-Forwarded-For", forwardedFor);
            return r;
        }

        HttpResponse<String> send(HttpRequest.Builder r) throws Exception {
            var res = http.send(r.build(), HttpResponse.BodyHandlers.ofString());
            for (String sc : res.headers().allValues("set-cookie")) {
                String kv = sc.split(";")[0];
                int eq = kv.indexOf('=');
                String k = kv.substring(0, eq), v = kv.substring(eq + 1);
                if (v.isEmpty() || sc.contains("Max-Age=0")) jar.remove(k); else jar.put(k, v);
            }
            return res;
        }

        String csrf(String page) throws Exception {
            Matcher m = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(get(page).body());
            assertTrue(m.find(), "the page has a form token");
            return m.group(1);
        }

        /** Step one only: type a code. 200 is the "This invite is for…" page; nothing is used up. */
        HttpResponse<String> check(String code) throws Exception {
            return post("/invite", Map.of("csrf", csrf("/"), "code", code));
        }

        /** Both steps, as a person would: the code, then "Yes, that's me". Answers with the last response. */
        HttpResponse<String> redeem(String code) throws Exception {
            var first = check(code);
            if (first.statusCode() != 200) return first;
            Matcher m = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(first.body());
            assertTrue(m.find(), "the confirm page has a form token");
            return post("/invite/confirm", Map.of("csrf", m.group(1), "code", code));
        }

        /** The old two-field call sites: the name isn't asked for any more (owner, 2026-11-10), so it's ignored. */
        HttpResponse<String> redeem(String code, String ignoredName) throws Exception { return redeem(code); }
    }

    String invite(String name, Duration life) throws Exception {
        return invites.create(ALEX, name, List.of("builders"), life).code();
    }

    static void hasSecurityHeaders(HttpResponse<?> r) {
        var h = r.headers();
        assertEquals(Html.CSP, h.firstValue("content-security-policy").orElse(null), "CSP on " + r.uri() + " " + r.statusCode());
        assertEquals("nosniff", h.firstValue("x-content-type-options").orElse(null));
        assertEquals("DENY", h.firstValue("x-frame-options").orElse(null));
        assertEquals("no-referrer", h.firstValue("referrer-policy").orElse(null));
        assertEquals("no-store", h.firstValue("cache-control").orElse(null));
        assertTrue(h.firstValue("strict-transport-security").isPresent());
    }

    // --- headers, methods, errors ---

    @Test void everyLatchkeyAnswerCarriesTheSecurityHeaders() throws Exception {
        var b = new Browser();
        hasSecurityHeaders(b.get("/"));
        hasSecurityHeaders(b.get("/signin"));
        hasSecurityHeaders(b.get("/nope"));
        hasSecurityHeaders(b.get("/invite"));
        hasSecurityHeaders(b.post("/invite", Map.of()));
        hasSecurityHeaders(b.redeem("AAAAAA", "Alex"));
    }

    @Test void theCspAllowsExactlyTheInlineStyleAndScript() throws Exception {
        String page = new Browser().get("/signin").body();
        String css = page.substring(page.indexOf("<style>") + 7, page.indexOf("</style>"));
        String js = page.substring(page.indexOf("<script>") + 8, page.indexOf("</script>"));
        assertTrue(Html.CSP.contains("'" + Html.sha256(css) + "'"));
        assertTrue(Html.CSP.contains("'" + Html.sha256(js) + "'"));
        assertTrue(Html.CSP.startsWith("default-src 'none'"));
    }

    @Test void anInternalFailureStillAnswersWithAPlainPageAndNoDetails() throws Exception {
        // Make the sessions folder unreadable as a folder, so looking up any session fails inside the server.
        java.nio.file.Path folder = dir.resolve("data/sessions");
        try (var files = java.nio.file.Files.list(folder)) { for (var f : files.toList()) java.nio.file.Files.delete(f); }
        java.nio.file.Files.delete(folder);
        java.nio.file.Files.writeString(folder, "not a folder");
        var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/"))
                .header("Cookie", LandingServer.SESSION_COOKIE + "=" + "A".repeat(43)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(500, r.statusCode());
        assertTrue(r.body().contains("Something went wrong"));
        assertFalse(r.body().contains("Exception") || r.body().contains("sessions"), "no details");
        hasSecurityHeaders(r);
    }

    @Test void wrongMethodsAndUnknownPathsAreRefused() throws Exception {
        var b = new Browser();
        assertEquals(405, b.get("/invite").statusCode());
        assertEquals(405, b.get("/signout").statusCode());
        assertEquals(404, b.get("/admin").statusCode());
        assertEquals(405, b.post("/", Map.of()).statusCode());
    }

    @Test void theFormsAreLabelledForScreenReaders() throws Exception {
        String home = new Browser().get("/").body();
        assertTrue(home.contains("<html lang=\"en\">"));
        assertTrue(home.contains("<label for=\"code\">") && home.contains("id=\"code\""));
        assertFalse(home.contains("id=\"name\""), "the code is the only box (owner, 2026-11-10)");
        String signin = new Browser().get("/signin").body();
        assertTrue(signin.contains("<label for=\"link\">") && signin.contains("id=\"link\""));
    }

    // --- invites ---

    @Test void aGoodInviteAsksFirstThenAdmitsThePlayerOnceAndSignsTheBrowserIn() throws Exception {
        start(100, 60, Map.of());
        String code = invite("Alex", Duration.ofDays(1));
        var b = new Browser();
        var asked = b.check(code.toLowerCase());
        assertEquals(200, asked.statusCode(), asked.body());
        assertTrue(asked.body().contains("This invite is for <strong>Alex</strong>"), asked.body());
        assertEquals(0, admitted.size(), "nothing happens until they say yes");
        assertEquals(Invite.Status.PENDING, invites.all().getFirst().status());
        var r = b.redeem(code.toLowerCase());
        assertEquals(303, r.statusCode(), r.body());
        assertTrue(b.jar.containsKey(LandingServer.SESSION_COOKIE), "the browser is signed in");
        assertTrue(b.get("/").body().contains("Welcome, <strong>Alex</strong>"));
        assertEquals(1, admitted.size());
        assertEquals(List.of("builders"), admitted.getFirst().profiles());
        assertEquals(400, new Browser().redeem(code).statusCode());
        assertEquals(1, admitted.size());
    }

    @Test void everyFailedInviteLooksAndTakesTheSame() throws Exception {
        String used = invite("Alex", Duration.ofDays(1));
        new Browser().redeem(used, "Alex");
        String revoked = invite("Alex", Duration.ofDays(1));
        invites.revoke(invites.all().getLast().id());
        String expired = invite("Alex", Duration.ofSeconds(1));
        now.addAndGet(5_000);
        start(100, 60, Map.of());

        List<String> bodies = new ArrayList<>();
        String[][] tries = {{"ZZZZZZ", ""}, {used, ""}, {revoked, ""}, {expired, ""}, {"x".repeat(17), ""}};
        for (String[] t : tries) {
            var b = new Browser();
            String token = b.csrf("/");
            long t0 = System.nanoTime();
            var r = b.post("/invite", Map.of("csrf", token, "code", t[0]));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(400, r.statusCode(), String.join(",", t));
            assertTrue(ms >= LandingServer.MIN_ANSWER.toMillis() - 5, "took " + ms + " ms");
            bodies.add(r.body().replaceAll("name=\"csrf\" value=\"[^\"]+\"", ""));
        }
        assertEquals(1, bodies.stream().distinct().count(), "every failure gives the same page");
        assertEquals(1, admitted.size());
    }

    @Test void twoBrowsersRacingOneCodeGetOneAdmission() throws Exception {
        start(100, 60, Map.of());
        String code = invite("Alex", Duration.ofDays(1));
        int n = 8;
        List<Browser> bs = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            var b = new Browser();
            bs.add(b);
            Matcher m = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(b.check(code).body());
            assertTrue(m.find(), "every browser reaches the confirm step");
            tokens.add(m.group(1));
        }
        ExecutorService ex = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            fs.add(ex.submit(() -> { go.await(); return bs.get(k).post("/invite/confirm", Map.of("csrf", tokens.get(k), "code", code)).statusCode(); }));
        }
        go.countDown();
        int ok = 0;
        for (var f : fs) if (f.get() == 303) ok++;
        ex.shutdown();
        assertEquals(1, ok);
        assertEquals(1, admitted.size());
    }

    @Test void invitesStayClosedWhenRedemptionIsStopped() throws Exception {
        String code = invite("Alex", Duration.ofDays(1));
        var b = new Browser();
        String token = b.csrf("/");
        off.add(SafeSetup.Part.REDEMPTION);
        var r = b.post("/invite", Map.of("csrf", token, "code", code));
        assertTrue(r.body().contains("Invites aren't open"));
        assertFalse(r.body().contains("id=\"code\""));
        assertEquals(0, admitted.size());
        assertEquals(Invite.Status.PENDING, invites.all().getFirst().status(), "the code wasn't used up");
    }

    // --- "Open the map": the one-time hand-off to the map's own address (D43) ---

    @Test void theMapButtonHandsASignedInBrowserOverOnceWithTheTokenAfterTheHash() throws Exception {
        startWithPage(100);
        String code = invite("Alex", Duration.ofDays(1));
        var b = new Browser();
        b.check(code);
        b.redeem(code);
        var welcome = b.get("/");
        assertTrue(welcome.body().contains("action=\"/map\""), "a signed-in browser gets the button");
        assertTrue(welcome.headers().firstValue("content-security-policy").orElseThrow().contains("form-action 'self' " + MAP),
                "the button may post on to the map, and only there");
        var r = b.post("/map", Map.of("csrf", b.csrf("/")));
        assertEquals(303, r.statusCode(), r.body());
        String where = r.headers().firstValue("location").orElseThrow();
        assertTrue(where.startsWith(MAP + "/handoff#"), where);
        String token = where.substring(where.indexOf('#') + 1);
        var g = handoffs.redeem(token, LandingServer.MAP_AUDIENCE);
        assertTrue(g.isPresent());
        assertEquals(ALEX, g.get().uuid());
        assertTrue(sessions.liveByRef(g.get().sessionRef()).isPresent(), "the map can ask whether the session is live");
        assertTrue(handoffs.redeem(token, LandingServer.MAP_AUDIENCE).isEmpty(), "a token works once");
        b.post("/signout", Map.of("csrf", b.csrf("/")));
        assertTrue(sessions.liveByRef(g.get().sessionRef()).isEmpty(), "signing out here ends it for the map too");
    }

    @Test void theMapButtonIsOnlyForASignedInBrowserAndOnlyFromThisSite() throws Exception {
        startWithPage(100);
        var stranger = new Browser();
        assertFalse(stranger.get("/").body().contains("action=\"/map\""), "no button before signing in");
        var r = stranger.post("/map", Map.of("csrf", stranger.csrf("/")));
        assertEquals(403, r.statusCode());
        assertFalse(r.headers().firstValue("location").isPresent());
        assertEquals(0, handoffs.outstanding(), "no token for a stranger");
        assertEquals(405, stranger.get("/map").statusCode(), "a link elsewhere can't make one");
        var noCsrf = stranger.post("/map", Map.of());
        assertEquals(403, noCsrf.statusCode());
    }

    @Test void withNoMapAddressThereIsNoButtonAndNoRoute() throws Exception {
        start(100, 60, Map.of());
        String code = invite("Alex", Duration.ofDays(1));
        var b = new Browser();
        b.check(code);
        b.redeem(code);
        assertFalse(b.get("/").body().contains("action=\"/map\""));
        assertEquals(404, b.post("/map", Map.of("csrf", b.csrf("/"))).statusCode());
    }

    // --- the welcome view and the play address (owner, 2026-11-10) ---

    @Test void thePlayAddressIsNeverInAnyAnswerToABrowserThatIsntSignedIn() throws Exception {
        startWithPage(1000);
        String code = invite("Alex", Duration.ofDays(1));
        String other = invites.create(SAM, "Sam", List.of(), Duration.ofDays(1)).code();
        var b = new Browser();
        List<HttpResponse<String>> seen = new ArrayList<>();
        seen.add(b.get("/"));
        seen.add(b.get("/signin"));
        seen.add(b.get("/nope"));
        seen.add(b.get("/invite"));
        seen.add(b.get("/invite/confirm"));
        seen.add(b.post("/", Map.of()));
        seen.add(b.post("/invite", Map.of()));
        seen.add(b.check("ZZZZZZ"));
        seen.add(b.check(other));                                   // the "This invite is for Sam" page
        seen.add(b.post("/invite/confirm", Map.of("csrf", b.csrf("/"), "code", "ZZZZZZ")));
        seen.add(b.post("/signin", Map.of("csrf", b.csrf("/signin"), "link", "nope")));
        seen.add(b.post("/signout", Map.of("csrf", b.csrf("/"))));
        for (var r : seen) assertFalse(mentionsAddress(r), r.uri() + " " + r.statusCode());
        var logo = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/logo")).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertFalse(new String(logo.body(), StandardCharsets.ISO_8859_1).contains("secret-address"));

        // Signed in by using the invite: now, and only now, the address is there.
        var in = new Browser();
        assertEquals(303, in.redeem(code).statusCode());
        var home = in.get("/");
        assertTrue(home.body().contains("<code id=\"addr\">" + ADDRESS + "</code>"), home.body());
        assertEquals("no-store", home.headers().firstValue("cache-control").orElse(null), "never kept by a shared cache");
        // Signing out takes it away again.
        in.post("/signout", Map.of("csrf", in.csrf("/")));
        assertFalse(mentionsAddress(in.get("/")));
    }

    @Test void theWelcomeShowsTheStepsAndItsScriptIsAllowedByHash() throws Exception {
        startWithPage(1000);
        var b = new Browser();
        assertEquals(303, b.redeem(invite("Alex", Duration.ofDays(1))).statusCode());
        String page = b.get("/").body();
        assertTrue(page.contains("Accept the resource pack") && page.contains("The Lobby"));
        assertTrue(page.contains("<img class=\"logo\" src=\"/logo\" alt=\"Example Realm\">"));
        String js = page.substring(page.indexOf("<script>") + 8, page.indexOf("</script>"));
        assertTrue(Html.CSP.contains("'" + Html.sha256(js) + "'"));
        assertTrue(page.contains("hidden>Copy</button>"), "the copy button only appears where the script can work");
    }

    @Test void theLogoIsServedAsAnImageWithTheSecurityHeaders() throws Exception {
        startWithPage(1000);
        var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/logo")).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, r.statusCode());
        assertEquals("image/png", r.headers().firstValue("content-type").orElse(null));
        assertEquals("nosniff", r.headers().firstValue("x-content-type-options").orElse(null));
        assertArrayEquals(PNG, r.body());
        start(3, 60, Map.of());
        assertEquals(404, new Browser().get("/logo").statusCode(), "no logo set, nothing served");
    }

    @Test void theConfirmStepUsesNothingUntilYes() throws Exception {
        startWithPage(1000);
        String code = invite("Alex", Duration.ofDays(1));
        var b = new Browser();
        for (int i = 0; i < 3; i++) assertEquals(200, b.check(code).statusCode());
        assertEquals(Invite.Status.PENDING, invites.all().getFirst().status(), "looking is free");
        assertEquals(0, admitted.size());
        assertEquals(403, b.post("/invite/confirm", Map.of("code", code)).statusCode(), "the yes needs a form token too");
        assertEquals(0, admitted.size());
    }

    // --- CSRF and where posts come from ---

    @Test void postsWithoutAMatchingFormTokenAreRefused() throws Exception {
        start(100, 60, Map.of());
        String code = invite("Alex", Duration.ofDays(1));
        var b = new Browser();
        b.csrf("/");
        assertEquals(403, b.post("/invite", Map.of("code", code, "name", "Alex")).statusCode());
        assertEquals(403, b.post("/invite", Map.of("csrf", "x", "code", code, "name", "Alex")).statusCode());
        var other = new Browser();
        String othersToken = other.csrf("/");
        assertEquals(403, b.post("/invite", Map.of("csrf", othersToken, "code", code, "name", "Alex")).statusCode());
        var noCookie = new Browser();
        assertEquals(403, noCookie.post("/invite", Map.of("csrf", othersToken, "code", code, "name", "Alex")).statusCode());
        assertEquals(0, admitted.size());
    }

    @Test void postsFromAnotherSiteAreRefused() throws Exception {
        String code = invite("Alex", Duration.ofDays(1));
        var b = new Browser();
        String token = b.csrf("/");
        String body = "csrf=" + token + "&code=" + code + "&name=Alex";
        var r1 = b.send(b.req("/invite").header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", "https://evil.example").POST(HttpRequest.BodyPublishers.ofString(body)));
        var r2 = b.send(b.req("/invite").header("Content-Type", "application/x-www-form-urlencoded")
                .header("Sec-Fetch-Site", "cross-site").POST(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(403, r1.statusCode());
        assertEquals(403, r2.statusCode());
        var r3 = b.send(b.req("/invite").header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", ORIGIN).header("Sec-Fetch-Site", "same-origin").POST(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(200, r3.statusCode());
    }

    @Test void oddBodiesAreRefused() throws Exception {
        start(100, 60, Map.of());
        var b = new Browser();
        String token = b.csrf("/");
        assertEquals(415, b.post("/invite", "{\"csrf\":\"" + token + "\"}", "application/json").statusCode());
        assertEquals(413, b.post("/invite", "csrf=" + token + "&code=" + "A".repeat(5000), "application/x-www-form-urlencoded").statusCode());
        assertEquals(403, b.post("/invite", "csrf=" + token + "&csrf=" + token, "application/x-www-form-urlencoded").statusCode());
        assertEquals(403, b.post("/invite", "csrf=" + token + "&name=a%0Ab", "application/x-www-form-urlencoded").statusCode());
    }

    // --- rate limits ---

    @Test void triesAreRateLimitedPerVisitor() throws Exception {
        var b = new Browser();
        for (int i = 0; i < 3; i++) assertEquals(400, b.redeem("ZZZZZZ", "Alex").statusCode());
        var r = b.redeem("ZZZZZZ", "Alex");
        assertEquals(429, r.statusCode());
        assertTrue(Long.parseLong(r.headers().firstValue("retry-after").orElseThrow()) > 0);
        hasSecurityHeaders(r);
        String code = invite("Alex", Duration.ofDays(1));
        assertEquals(429, b.redeem(code, "Alex").statusCode(), "even a right code waits");
        assertEquals(0, admitted.size());
        now.addAndGet(Duration.ofMinutes(10).toMillis() + 1000);
        assertEquals(303, b.redeem(code).statusCode(), "both steps fit in the allowance");
    }

    @Test void forwardedAddressesCountOnlyFromTrustedProxies() throws Exception {
        // The test client connects from 127.0.0.1, which is listed as a trusted proxy here.
        var a = new Browser();
        a.forwardedFor = "198.51.100.1";
        var c = new Browser();
        c.forwardedFor = "198.51.100.2";
        for (int i = 0; i < 3; i++) a.redeem("ZZZZZZ", "Alex");
        assertEquals(429, a.redeem("ZZZZZZ", "Alex").statusCode());
        assertEquals(400, c.redeem("ZZZZZZ", "Alex").statusCode(), "a different visitor behind the proxy has their own allowance");
    }

    @Test void forwardedAddressesFromUntrustedSendersAreIgnored() {
        var addr = new ClientAddress(List.of("10.0.0.1"));
        var remote = java.net.InetAddress.ofLiteral("203.0.113.9");
        assertEquals("203.0.113.9", addr.of(remote, "198.51.100.1"));
        var proxy = java.net.InetAddress.ofLiteral("10.0.0.1");
        assertEquals("198.51.100.1", addr.of(proxy, "1.2.3.4, 198.51.100.1"));
        assertEquals("10.0.0.1", addr.of(proxy, "not-an-ip"));
    }

    @Test void theSiteWideLimitCatchesManyVisitors() throws Exception {
        start(100, 2, Map.of());
        for (int i = 0; i < 2; i++) { var b = new Browser(); b.forwardedFor = "198.51.100." + i; b.redeem("ZZZZZZ", "Alex"); }
        var late = new Browser();
        late.forwardedFor = "198.51.100.99";
        assertEquals(429, late.redeem("ZZZZZZ", "Alex").statusCode());
    }

    // --- sign-in links and sessions ---

    HttpResponse<String> signInWith(Browser b, String token) throws Exception {
        return b.post("/signin", Map.of("csrf", b.csrf("/signin"), "link", ORIGIN + "/signin#" + token));
    }

    @Test void aLinkSignsTheBrowserInWithAStrictCookie() throws Exception {
        String token = links.issue(ALEX, "Alex", Instant.ofEpochMilli(now.get()));
        var b = new Browser();
        var r = signInWith(b, token);
        assertEquals(303, r.statusCode(), r.body());
        hasSecurityHeaders(r);
        String sc = r.headers().allValues("set-cookie").stream().filter(s -> s.startsWith("latchkey_session=")).findFirst().orElseThrow();
        assertTrue(sc.contains("HttpOnly") && sc.contains("Secure") && sc.contains("SameSite=Strict") && sc.contains("Path=/"), sc);
        assertTrue(sc.contains("Max-Age=86400"), sc);
        assertTrue(b.get("/").body().contains("Welcome, <strong>Alex</strong>"));
        assertEquals(1, sessions.count(ALEX));
    }

    @Test void aLinkWorksOnce() throws Exception {
        String token = links.issue(ALEX, "Alex", Instant.ofEpochMilli(now.get()));
        assertEquals(303, signInWith(new Browser(), token).statusCode());
        var again = signInWith(new Browser(), token);
        assertEquals(400, again.statusCode());
        assertTrue(again.body().contains("That link didn&#39;t work"));
        assertEquals(1, sessions.count(ALEX));
    }

    @Test void aBareTokenWorksForPeopleWithoutJavaScript() throws Exception {
        String token = links.issue(ALEX, "Alex", Instant.ofEpochMilli(now.get()));
        var b = new Browser();
        assertEquals(303, b.post("/signin", Map.of("csrf", b.csrf("/signin"), "link", token)).statusCode());
    }

    @Test void anExpiredLinkSignsNobodyIn() throws Exception {
        String token = links.issue(ALEX, "Alex", Instant.ofEpochMilli(now.get()));
        now.addAndGet(SignInLinks.LIFETIME.toMillis() + 1000);
        assertEquals(400, signInWith(new Browser(), token).statusCode());
        assertEquals(0, sessions.count(ALEX));
    }

    @Test void aPlayerWhoMayNotSignInGetsNoSession() throws Exception {
        String token = links.issue(ALEX, "Alex", Instant.ofEpochMilli(now.get()));
        barred.add(ALEX);
        assertEquals(400, signInWith(new Browser(), token).statusCode());
        assertEquals(0, sessions.count(ALEX));
    }

    @Test void signingInAgainRotatesTheSession() throws Exception {
        var b = new Browser();
        signInWith(b, links.issue(ALEX, "Alex", Instant.ofEpochMilli(now.get())));
        String first = b.jar.get("latchkey_session");
        now.addAndGet(SignInLinks.COOLDOWN.toMillis() + 1000);
        signInWith(b, links.issue(ALEX, "Alex", Instant.ofEpochMilli(now.get())));
        String second = b.jar.get("latchkey_session");
        assertNotEquals(first, second);
        assertTrue(sessions.lookup(first).isEmpty(), "the old session ended");
        assertEquals(1, sessions.count(ALEX));
    }

    @Test void signOutEndsTheSessionAndClearsTheCookie() throws Exception {
        var b = new Browser();
        signInWith(b, links.issue(ALEX, "Alex", Instant.ofEpochMilli(now.get())));
        String cookie = b.jar.get("latchkey_session");
        var r = b.post("/signout", Map.of("csrf", b.csrf("/")));
        assertEquals(303, r.statusCode());
        assertNull(b.jar.get("latchkey_session"));
        assertTrue(sessions.lookup(cookie).isEmpty());
        try (Stream<Path> s = Files.list(dir.resolve("data/sessions"))) { assertEquals(0, s.count(), "the file is gone"); }
    }

    @Test void aStolenCookieStopsWorkingAfterSignOut() throws Exception {
        var b = new Browser();
        signInWith(b, links.issue(ALEX, "Alex", Instant.ofEpochMilli(now.get())));
        var thief = new Browser();
        thief.jar.put("latchkey_session", b.jar.get("latchkey_session"));
        assertTrue(thief.get("/").body().contains("Welcome, <strong>"));
        b.post("/signout", Map.of("csrf", b.csrf("/")));
        assertFalse(thief.get("/").body().contains("Welcome, <strong>"));
    }

    @Test void sessionsAreIgnoredWhileSignInIsStopped() throws Exception {
        var b = new Browser();
        signInWith(b, links.issue(ALEX, "Alex", Instant.ofEpochMilli(now.get())));
        off.add(SafeSetup.Part.SIGNIN);
        assertFalse(b.get("/").body().contains("Welcome, <strong>"));
        assertTrue(b.get("/signin").body().contains("Sign-in isn't open"));
    }

    @Test void twoSessionCookiesAreTrustedAsNeither() throws Exception {
        var b = new Browser();
        signInWith(b, links.issue(ALEX, "Alex", Instant.ofEpochMilli(now.get())));
        String c = b.jar.get("latchkey_session");
        var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/"))
                .header("Cookie", "latchkey_session=" + c + "; latchkey_session=" + c).build(), HttpResponse.BodyHandlers.ofString());
        assertFalse(r.body().contains("Welcome, <strong>"));
    }

    // --- forwarding ---

    void startBackend() throws Exception {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", x -> {
            backendSaw.add(Map.copyOf(x.getRequestHeaders()));
            byte[] b = ("saw " + x.getRequestMethod() + " " + x.getRequestURI()).getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("X-From-Backend", "yes");
            x.sendResponseHeaders(x.getRequestURI().getPath().endsWith("/missing") ? 404 : 200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        backend.start();
        start(3, 60, Map.of("/map", URI.create("http://127.0.0.1:" + backend.getAddress().getPort())));
    }

    @Test void forwardingPassesTheRequestThroughUnchanged() throws Exception {
        startBackend();
        var b = new Browser();
        b.jar.put("latchkey_session", "abc");
        var r = b.get("/map/tiles/1.png?x=1&y=2");
        assertEquals(200, r.statusCode());
        assertEquals("saw GET /map/tiles/1.png?x=1&y=2", r.body());
        assertEquals("yes", r.headers().firstValue("x-from-backend").orElse(null));
        var saw = backendSaw.getLast();
        assertEquals(List.of("latchkey_session=abc"), saw.get("Cookie"));
        assertEquals(Set.of("host", "content-length", "cookie", "user-agent"),
                saw.keySet().stream().map(String::toLowerCase).collect(java.util.stream.Collectors.toSet()),
                "only what the browser sent, with no identity header added");
        assertEquals(404, b.get("/map/missing").statusCode());
        assertEquals("saw GET /map", b.get("/map").body());
    }

    @Test void onlyTheExactPrefixIsForwarded() throws Exception {
        startBackend();
        assertEquals(404, new Browser().get("/mapx").statusCode());
        assertEquals(0, backendSaw.size());
    }

    @Test void aDownServiceGetsAPlainError() throws Exception {
        startBackend();
        backend.stop(0);
        backend = null;
        var r = new Browser().get("/map/");
        assertEquals(502, r.statusCode());
        assertFalse(r.body().contains("Exception"));
    }

    @Test void escapingCoversEverySpecialCharacter() {
        assertEquals("&lt;a href=&quot;x&quot; onclick=&#39;y&#39;&gt;&amp;", Html.esc("<a href=\"x\" onclick='y'>&"));
    }
}

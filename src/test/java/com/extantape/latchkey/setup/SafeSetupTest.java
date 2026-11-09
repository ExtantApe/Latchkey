package com.extantape.latchkey.setup;

import com.extantape.latchkey.setup.SafeSetup.Part;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class SafeSetupTest {
    static final String SAFE = """
            landing:
              enabled: true
              bind: 127.0.0.1
              port: 25680
              public-url: https://play.example.org
              trusted-proxies: [127.0.0.1]
              rate-limits:
                per-ip-per-10-minutes: 10
                site-wide-per-minute: 30
              forward:
                /map: http://127.0.0.1:8100
            invites:
              lifetime-days: 14
            sessions:
              lifetime-hours: 24
            """;

    static SafeSetup.Result check(String yaml) { return check(yaml, true, true); }

    static SafeSetup.Result check(String yaml, boolean whitelist, boolean online) {
        return SafeSetup.check(YamlConfiguration.loadConfiguration(new StringReader(yaml)), whitelist, online);
    }

    static String with(String find, String replace) {
        assertTrue(SAFE.contains(find), "fixture has " + find);
        return SAFE.replace(find, replace);
    }

    static void webStopped(SafeSetup.Result r) {
        assertFalse(r.allowed(Part.LANDING), r.problems().toString());
        assertFalse(r.allowed(Part.REDEMPTION));
        assertFalse(r.allowed(Part.SIGNIN));
    }

    @Test void aSafeSetupRunsEverything() {
        var r = check(SAFE);
        assertEquals(java.util.List.of(), r.problems());
        for (Part p : Part.values()) assertTrue(r.allowed(p));
        assertEquals(Duration.ofDays(14), r.settings().inviteLifetime());
        assertEquals(Duration.ofHours(24), r.settings().sessionLifetime());
        assertEquals("http://127.0.0.1:8100", r.settings().forward().get("/map").toString());
    }

    @Test void theShippedConfigStartsWithTheWebPartsOff() throws Exception {
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            var r = SafeSetup.check(YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in)), true, true);
            webStopped(r);
            assertTrue(r.problems().stream().allMatch(p -> p.what().startsWith("The landing page is turned off")
                    || p.what().startsWith("landing.public-url")), r.problems().toString());
        }
    }

    @Test void whitelistOffStopsRedemptionAndSignInButNotThePage() {
        var r = check(SAFE, false, true);
        assertTrue(r.allowed(Part.LANDING));
        assertFalse(r.allowed(Part.REDEMPTION));
        assertFalse(r.allowed(Part.SIGNIN));
        assertTrue(r.problems().getFirst().line().contains("/whitelist on"));
    }

    @Test void offlineModeStopsRedemptionAndSignIn() {
        var r = check(SAFE, true, false);
        assertFalse(r.allowed(Part.REDEMPTION));
        assertFalse(r.allowed(Part.SIGNIN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://play.example.org", "play.example.org", "", "https://", "https://user@play.example.org",
            "https://play.example.org/?x=1", "https://play.example.org/#x", "https://play.example.org/sub", "ftp://play.example.org"})
    void theLandingPageNeedsHttps(String url) {
        webStopped(check(with("public-url: https://play.example.org", "public-url: \"" + url + "\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::", "203.0.113.5", "localhost", "example.org", "", "999.1.1.1"})
    void theServerNeverBindsToWildcardPublicOrNamedAddresses(String bind) {
        webStopped(check(with("bind: 127.0.0.1", "bind: \"" + bind + "\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "10.0.0.5", "192.168.1.20", "172.16.0.2", "fd00::5"})
    void loopbackAndPrivateBindsAreFine(String bind) {
        assertTrue(check(with("bind: 127.0.0.1", "bind: \"" + bind + "\"")).allowed(Part.LANDING));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 80, 443, 1023, 65536, -1})
    void portsOutsideTheUserRangeAreRefused(int port) {
        webStopped(check(with("port: 25680", "port: " + port)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "0.0.0.0/0", "0.0.0.0", "::/0", "::", "10.0.0.0/8", "proxy.example.org", "", "127.0.0.1/abc", "2001:db8::/32"})
    void trustedProxiesCantBeWildcardsOrWideRanges(String proxy) {
        webStopped(check(with("trusted-proxies: [127.0.0.1]", "trusted-proxies: [\"" + proxy + "\"]")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.7", "192.168.1.0/24", "::1", "fd00::/64"})
    void exactProxiesAndNarrowRangesAreFine(String proxy) {
        assertTrue(check(with("trusted-proxies: [127.0.0.1]", "trusted-proxies: [\"" + proxy + "\"]")).allowed(Part.LANDING));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, SafeSetup.PER_IP_CEILING + 1, 1000})
    void perIpRateLimitCantBeOffOrLoose(int n) {
        webStopped(check(with("per-ip-per-10-minutes: 10", "per-ip-per-10-minutes: " + n)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, SafeSetup.SITE_WIDE_CEILING + 1, 1000})
    void siteWideRateLimitCantBeOffOrLoose(int n) {
        webStopped(check(with("site-wide-per-minute: 30", "site-wide-per-minute: " + n)));
    }

    @Test void stricterRateLimitsAreFine() {
        assertTrue(check(with("per-ip-per-10-minutes: 10", "per-ip-per-10-minutes: 1")).allowed(Part.LANDING));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -3, 31, 365})
    void invitesLastOneToThirtyDays(int days) {
        var r = check(with("lifetime-days: 14", "lifetime-days: " + days));
        assertFalse(r.allowed(Part.REDEMPTION));
        assertTrue(r.allowed(Part.LANDING));
        assertTrue(r.allowed(Part.SIGNIN));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 721, 8760})
    void sessionsLastAnHourToThirtyDays(int hours) {
        var r = check(with("lifetime-hours: 24", "lifetime-hours: " + hours));
        assertFalse(r.allowed(Part.SIGNIN));
        assertTrue(r.allowed(Part.REDEMPTION));
    }

    @Test void thirtyDaySessionsAreTheCeilingNotPastIt() {
        assertTrue(check(with("lifetime-hours: 24", "lifetime-hours: 720")).allowed(Part.SIGNIN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://10.0.0.5:8100", "http://example.org:8100", "http://localhost:8100", "https://127.0.0.1:8100",
            "http://127.0.0.1", "http://user@127.0.0.1:8100", "file:///etc/passwd", "http://0.0.0.0:8100", "http://192.168.1.2:80"})
    void forwardingOnlyReachesLoopback(String target) {
        webStopped(check(with("/map: http://127.0.0.1:8100", "/map: \"" + target + "\"")));
    }

    @Test void ipv6LoopbackForwardingIsFine() {
        assertTrue(check(with("/map: http://127.0.0.1:8100", "/map: \"http://[::1]:8100\"")).allowed(Part.LANDING));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/invite", "/signin", "/status", "/", "/Map", "/a/b", "map", "/../x"})
    void forwardingCantTakeLatchkeysPathsOrOddOnes(String prefix) {
        webStopped(check(with("/map: http://127.0.0.1:8100", "\"" + prefix + "\": http://127.0.0.1:8100")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"  debug: true\n", "  security-headers: false\n", "  verbose-errors: true\n"})
    void unknownSettingsStopTheWebParts(String extra) {
        var r = check(SAFE.replace("  enabled: true\n", "  enabled: true\n" + extra));
        webStopped(r);
        assertTrue(r.problems().getFirst().what().startsWith("Latchkey has no setting called landing."));
    }

    @Test void aTypoAtTheTopLevelStopsTheWebPartsToo() {
        webStopped(check(SAFE + "sesions:\n  lifetime-hours: 9999\n"));
    }

    @Test void turnedOffMeansOff() {
        webStopped(check(with("enabled: true", "enabled: false")));
    }

    @Test void everyProblemSaysHowToFixIt() {
        var r = check("""
                landing:
                  enabled: true
                  bind: 0.0.0.0
                  port: 80
                  public-url: http://x
                  trusted-proxies: ["*"]
                  rate-limits: {per-ip-per-10-minutes: 0, site-wide-per-minute: 0}
                  forward: {/signin: "http://1.2.3.4:80"}
                invites: {lifetime-days: 90}
                sessions: {lifetime-hours: 0}
                """, false, false);
        assertEquals(11, r.problems().size(), r.problems().toString());
        for (var p : r.problems()) {
            assertFalse(p.fix().isBlank());
            assertFalse(p.line().contains("\n"), "one line each");
        }
    }
}

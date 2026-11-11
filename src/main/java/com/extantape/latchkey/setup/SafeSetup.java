package com.extantape.latchkey.setup;

import org.bukkit.configuration.ConfigurationSection;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Safe setup or no setup (owner, 2026-11-08). Reads Latchkey's settings and the server's own state, and says which parts
 * may run. A failing check stops only the parts it affects, with one line saying what's wrong and how to fix it.
 *
 * <p>There's deliberately no setting that turns a check off. The limits below are in code; getting round one means
 * changing this file.
 */
public final class SafeSetup {
    /** The parts a check can stop. */
    public enum Part {
        /** The web server: the landing page and forwarding. Everything web needs this. */
        LANDING,
        /** Redeeming invite codes. */
        REDEMPTION,
        /** Browser sessions and /signin links. */
        SIGNIN
    }

    public record Problem(Set<Part> stops, String what, String fix) {
        public String line() { return what + " Fix: " + fix; }
    }

    /** Settings that passed every check. Only read these when the matching part isn't stopped. */
    public record Settings(String bind, int port, URI publicUrl, List<String> trustedProxies, int perIpPer10Minutes,
                           int siteWidePerMinute, Duration inviteLifetime, Duration sessionLifetime,
                           Map<String, URI> forward) {}

    /**
     * @param externalSignIn the older {@code sign-in-page} setting is an https:// address. Then in-game sign-in links go
     *                       there while Latchkey's own page is off, as 0.2.0's upgrade notes promise; sign-in still
     *                       stops for its own reasons (whitelist off, online mode off, a bad session length).
     */
    public record Result(Settings settings, List<Problem> problems, boolean externalSignIn) {
        public Result(Settings settings, List<Problem> problems) { this(settings, problems, false); }

        public boolean allowed(Part part) {
            boolean viaOtherPage = part == Part.SIGNIN && externalSignIn && !landingRuns();
            for (Problem p : problems) {
                if (!p.stops().contains(part)) continue;
                // A problem with Latchkey's own page (it stops every web part) doesn't stop links to another page.
                if (viaOtherPage && p.stops().equals(EnumSet.allOf(Part.class))) continue;
                return false;
            }
            if (part == Part.LANDING) return true;
            return landingRuns() || viaOtherPage;
        }

        private boolean landingRuns() {
            for (Problem p : problems) if (p.stops().contains(Part.LANDING)) return false;
            return true;
        }
    }

    // Limits set in code. Config may be stricter, never looser.
    public static final int PER_IP_CEILING = 20;
    public static final int SITE_WIDE_CEILING = 60;
    public static final Duration MAX_INVITE = Duration.ofDays(30);
    public static final Duration MAX_SESSION = Duration.ofDays(30);
    /** Latchkey's own paths, which forwarding may never take over. */
    public static final Set<String> OWN_PATHS = Set.of("/invite", "/signin", "/signout", "/session", "/sessions", "/status", "/assets", "/map", "/logo");

    private static final Pattern PREFIX = Pattern.compile("/[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Set<String> TOP = Set.of("landing", "invites", "sessions", "sign-in-page", "lobby", "page");
    private static final Set<String> LANDING_KEYS = Set.of("enabled", "bind", "port", "public-url", "trusted-proxies", "rate-limits", "forward", "health-check-minutes");
    private static final Set<String> RATE_KEYS = Set.of("per-ip-per-10-minutes", "site-wide-per-minute");
    private static final Set<String> INVITE_KEYS = Set.of("lifetime-days");
    private static final Set<String> SESSION_KEYS = Set.of("lifetime-hours");

    private SafeSetup() {}

    /**
     * @param whitelistOn       whether the server's whitelist is enforced
     * @param identitiesProven  whether the server checks who players are (online mode, or the test-only launch flag)
     */
    public static Result check(ConfigurationSection config, boolean whitelistOn, boolean identitiesProven) {
        List<Problem> out = new ArrayList<>();
        Set<Part> web = EnumSet.allOf(Part.class);

        if (!whitelistOn) out.add(new Problem(EnumSet.of(Part.REDEMPTION, Part.SIGNIN),
                "The server's whitelist is off, so anyone could join and sign in.",
                "turn it on with /whitelist on."));
        if (!identitiesProven) out.add(new Problem(EnumSet.of(Part.REDEMPTION, Part.SIGNIN),
                "online-mode is off, so a username proves nothing.",
                "set online-mode=true in server.properties."));

        unknownKeys(config, TOP, "", out);
        ConfigurationSection landing = config.getConfigurationSection("landing");
        if (landing == null) landing = config.createSection("landing");
        unknownKeys(landing, LANDING_KEYS, "landing.", out);

        if (!landing.getBoolean("enabled", false)) out.add(new Problem(web,
                "The landing page is turned off.", "set landing.enabled: true once the settings below are right."));

        String bind = landing.getString("bind", "127.0.0.1");
        InetAddress bindAddr = literal(bind);
        if (bindAddr == null || !(bindAddr.isLoopbackAddress() || isPrivate(bindAddr))) out.add(new Problem(web,
                "landing.bind is \"" + bind + "\". It must be a loopback or private IP address, never a wildcard, a public address or a host name.",
                "use 127.0.0.1 and let the tunnel or proxy reach it."));

        int port = landing.getInt("port", 25680);
        if (port < 1024 || port > 65535) out.add(new Problem(web,
                "landing.port is " + port + ".", "pick a port from 1024 to 65535; 25680 is the default."));

        String url = landing.getString("public-url", "");
        URI publicUrl = null;
        try {
            URI u = new URI(url);
            if ("https".equals(u.getScheme()) && u.getHost() != null && u.getRawUserInfo() == null
                    && u.getRawQuery() == null && u.getRawFragment() == null
                    && (u.getRawPath() == null || u.getRawPath().isEmpty() || u.getRawPath().equals("/"))) {
                publicUrl = u;
            }
        } catch (URISyntaxException ignored) {}
        if (publicUrl == null) out.add(new Problem(web,
                "landing.public-url is \"" + url + "\". The page must be served over HTTPS, at the root of its own host name.",
                "set it to the tunnel's or proxy's https:// address, such as https://play.example.org."));

        List<String> proxies = landing.getStringList("trusted-proxies");
        for (String p : proxies) {
            String why = proxyProblem(p);
            if (why != null) out.add(new Problem(web, "landing.trusted-proxies has \"" + p + "\": " + why,
                    "list only the exact addresses of your tunnel agent or proxy, such as 127.0.0.1."));
        }

        ConfigurationSection rates = landing.getConfigurationSection("rate-limits");
        if (rates != null) unknownKeys(rates, RATE_KEYS, "landing.rate-limits.", out);
        int perIp = rates == null ? 10 : rates.getInt("per-ip-per-10-minutes", 10);
        int site = rates == null ? 30 : rates.getInt("site-wide-per-minute", 30);
        if (perIp < 1 || perIp > PER_IP_CEILING) out.add(new Problem(web,
                "landing.rate-limits.per-ip-per-10-minutes is " + perIp + ". Rate limits can't be turned off or loosened past " + PER_IP_CEILING + ".",
                "use a number from 1 to " + PER_IP_CEILING + "; 10 is the default."));
        if (site < 1 || site > SITE_WIDE_CEILING) out.add(new Problem(web,
                "landing.rate-limits.site-wide-per-minute is " + site + ". Rate limits can't be turned off or loosened past " + SITE_WIDE_CEILING + ".",
                "use a number from 1 to " + SITE_WIDE_CEILING + "; 30 is the default."));

        Map<String, URI> forward = new LinkedHashMap<>();
        ConfigurationSection fw = landing.getConfigurationSection("forward");
        if (fw != null) for (String prefix : fw.getKeys(false)) {
            String target = fw.getString(prefix, "");
            String why = forwardProblem(prefix, target);
            if (why != null) out.add(new Problem(web, "landing.forward \"" + prefix + "\" → \"" + target + "\": " + why,
                    "forward a single path like /wiki to a service on this machine, such as http://127.0.0.1:8200."));
            else forward.put(prefix, URI.create(target));
        }

        ConfigurationSection inv = config.getConfigurationSection("invites");
        if (inv != null) unknownKeys(inv, INVITE_KEYS, "invites.", out);
        long inviteDays = inv == null ? 14 : inv.getLong("lifetime-days", 14);
        if (inviteDays < 1 || inviteDays > MAX_INVITE.toDays()) out.add(new Problem(EnumSet.of(Part.REDEMPTION),
                "invites.lifetime-days is " + inviteDays + ".", "use 1 to 30 days; 14 is the default."));

        ConfigurationSection ses = config.getConfigurationSection("sessions");
        if (ses != null) unknownKeys(ses, SESSION_KEYS, "sessions.", out);
        long sessionHours = ses == null ? 24 : ses.getLong("lifetime-hours", 24);
        if (sessionHours < 1 || sessionHours > MAX_SESSION.toHours()) out.add(new Problem(EnumSet.of(Part.SIGNIN),
                "sessions.lifetime-hours is " + sessionHours + ".", "use 1 to 720 hours (30 days); 24 is the default."));

        Settings s = new Settings(bind, port, publicUrl, List.copyOf(proxies), perIp, site,
                Duration.ofDays(Math.max(1, Math.min(inviteDays, 30))), Duration.ofHours(Math.max(1, Math.min(sessionHours, 720))),
                Map.copyOf(forward));
        return new Result(s, List.copyOf(out), com.extantape.latchkey.SignInLinks.usablePage(config.getString("sign-in-page", "")));
    }

    /**
     * Settings Latchkey doesn't know stop the web parts. That way a typo can't silently leave something unsafe, and a
     * setting such as "debug" or "security-headers: false" can't exist.
     */
    private static void unknownKeys(ConfigurationSection s, Set<String> known, String path, List<Problem> out) {
        for (String k : s.getKeys(false)) if (!known.contains(k)) out.add(new Problem(EnumSet.allOf(Part.class),
                "Latchkey has no setting called " + path + k + ".", "remove it, or check the spelling against the default config.yml."));
    }

    static String proxyProblem(String p) {
        if (p == null || p.isBlank()) return "it's empty.";
        if (p.contains("*")) return "wildcards aren't allowed.";
        String addr = p, bits = null;
        int slash = p.indexOf('/');
        if (slash >= 0) { addr = p.substring(0, slash); bits = p.substring(slash + 1); }
        InetAddress a = literal(addr);
        if (a == null) return "it isn't an IP address (host names aren't allowed).";
        if (a.isAnyLocalAddress()) return "that means every address.";
        if (bits != null) {
            int n;
            try { n = Integer.parseInt(bits); } catch (NumberFormatException e) { return "the range isn't a number."; }
            int max = a instanceof Inet6Address ? 128 : 32;
            int min = a instanceof Inet6Address ? 64 : 24;
            if (n < min || n > max) return "the range /" + n + " is too wide; use /" + min + " to /" + max + ".";
        }
        return null;
    }

    /** For tests in other packages. */
    public static String forwardProblemForTest(String prefix, String target) { return forwardProblem(prefix, target); }

    static String forwardProblem(String prefix, String target) {
        if (!PREFIX.matcher(prefix).matches()) return "the path must be one lowercase segment, like /wiki.";
        if (OWN_PATHS.contains(prefix)) return "that path belongs to Latchkey.";
        URI u;
        try { u = new URI(target); } catch (URISyntaxException e) { return "the target isn't a valid address."; }
        if (!"http".equals(u.getScheme())) return "the target must start with http:// (it's on this machine).";
        if (u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) return "the target can't carry a user, query or fragment.";
        String host = u.getHost();
        if (host != null && host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        InetAddress a = literal(host);
        if (a == null || !a.isLoopbackAddress()) return "forwarding may only reach loopback addresses such as 127.0.0.1.";
        if (u.getPort() < 1) return "the target needs a port.";
        return null;
    }

    /** An IP address written out, never looked up. Host names give null, so no check ever touches DNS. */
    static InetAddress literal(String s) {
        if (s == null || s.isBlank()) return null;
        if (!IPV4.matcher(s).matches() && !s.contains(":")) return null;
        try { return InetAddress.ofLiteral(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static boolean isPrivate(InetAddress a) {
        if (a.isSiteLocalAddress()) return true;
        return a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc;
    }
}

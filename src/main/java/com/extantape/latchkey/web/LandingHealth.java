package com.extantape.latchkey.web;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.function.LongSupplier;

/**
 * Whether the landing page really answers at its public address: reached from outside through the tunnel and proxy,
 * over HTTPS with a certificate Java trusts, and answered by this server's Latchkey, not something else at that name.
 * It asks for a one-off path that only this check knows. The page answers it only while that check is running, so
 * nobody else can use it.
 *
 * <p>It also notes whether Latchkey can tell visitors apart. If the check arrives looking like this machine, the proxy
 * isn't passing on each visitor's own address. The per-visitor rate limit then acts like the site-wide one.
 */
public final class LandingHealth {
    public static final String PATH = "/.well-known/latchkey-health/";
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * One check's outcome. {@code visitorsTold} is null when the check never reached this server. {@code problem} is
     * plain words, or null when all is well.
     */
    public record Result(long checkedAt, boolean reachable, boolean https, boolean thisServer, Boolean visitorsTold,
                         int status, String problem) {
        public boolean healthy() { return problem == null; }
    }

    private final URI publicUrl;
    private final HttpClient client;
    private final LongSupplier clock;
    private volatile String pending;
    private volatile String seenFrom;
    private volatile Result last;

    public LandingHealth(URI publicUrl, LongSupplier clockMillis) {
        this(publicUrl, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(TIMEOUT).build(), clockMillis);
    }

    LandingHealth(URI publicUrl, HttpClient client, LongSupplier clockMillis) {
        this.publicUrl = publicUrl;
        this.client = client;
        this.clock = clockMillis;
    }

    /** The latest check, or null before the first. */
    public Result last() { return last; }

    /** Runs one check. Blocks for up to about 20 seconds, so never on the server thread. */
    public synchronized Result check() {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        seenFrom = null;
        pending = token;
        boolean https = "https".equalsIgnoreCase(publicUrl.getScheme());
        Result r;
        try {
            // Under the page's own path, when it has one, since that's all the proxy sends on.
            URI u = URI.create(publicUrl.toString().replaceAll("/+$", "") + PATH + token);
            HttpResponse<Void> resp = client.send(HttpRequest.newBuilder(u).timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.discarding());
            int code = resp.statusCode();
            String from = seenFrom;
            boolean mine = code == 204 && from != null;
            Boolean told = mine ? !isLoopback(from) : null;
            String problem;
            if (!https) problem = "The public address isn't https://, so browsers won't trust the page.";
            else if (!mine) problem = "Something answered at " + publicUrl + " (status " + code + "), but not this server's Latchkey. Check where the tunnel or proxy points.";
            else if (!told) problem = "Every visitor looks like this machine, so the per-visitor limit acts like the site-wide one. Turn on PROXY protocol in the tunnel, and let the proxy pass on X-Forwarded-For.";
            else problem = null;
            r = new Result(clock.getAsLong(), true, https, mine, told, code, problem);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r = new Result(clock.getAsLong(), false, false, false, null, 0, "The check was stopped.");
        } catch (IOException e) {
            r = new Result(clock.getAsLong(), false, false, false, null, 0, why(e));
        } finally {
            pending = null;
        }
        last = r;
        return r;
    }

    /** Plain words for a failed request. Never the exception's own text, which can carry internal addresses. */
    String why(IOException e) {
        String host = publicUrl.getHost();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SSLException) return "HTTPS failed at " + host + ": the certificate isn't trusted or doesn't match the name.";
            if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) return host + " doesn't resolve: its DNS record is missing or hasn't spread yet.";
        }
        if (e instanceof HttpTimeoutException) return "Nothing answered at " + host + " within " + TIMEOUT.toSeconds() + " seconds.";
        if (e instanceof ConnectException) return "Nothing answered at " + host + ": the tunnel or proxy may be down.";
        return "The check couldn't reach " + host + " (" + e.getClass().getSimpleName() + ").";
    }

    /**
     * For the landing page: whether this request is the running check, and if so, who it seemed to come from. Any
     * other token, or none running, is false, so the path is a plain 404 to everyone else.
     */
    public boolean answer(String token, String visitor) {
        String p = pending;
        if (p == null || token == null) return false;
        if (!MessageDigest.isEqual(p.getBytes(StandardCharsets.US_ASCII), token.getBytes(StandardCharsets.US_ASCII))) return false;
        seenFrom = visitor;
        return true;
    }

    static boolean isLoopback(String address) {
        try {
            return InetAddress.ofLiteral(address).isLoopbackAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

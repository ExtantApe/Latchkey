package com.extantape.latchkey.session;

import com.extantape.signalbox.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Browser sessions, one file each: {@code sessions/<sha256 of the cookie, lowercase hex>.json} holding
 * {@code {"uuid","name","expires"}} with expires in Unix seconds. The cookie itself is never stored, so reading the
 * folder doesn't let anyone sign in. Folder 0700, files 0600, written through a temporary file and an atomic rename.
 *
 * <p>Anything that can't be read for certain counts as no session: a missing, malformed or expired file signs nobody in,
 * and is deleted when found.
 */
public final class SessionStore {
    public static final Duration DEFAULT_LIFETIME = Duration.ofDays(1);
    public static final Duration MAX_LIFETIME = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern COOKIE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern FILE = Pattern.compile("[0-9a-f]{64}\\.json");
    private static final Pattern BODY = Pattern.compile(
            "\\{\"uuid\":\"([0-9a-f-]{36})\",\"name\":\"([A-Za-z0-9_]{1,16})\",\"expires\":(\\d{1,12})}\\s*");

    private final Path folder;
    private final LongSupplier clockMillis;

    public SessionStore(Path folder, LongSupplier clockMillis) throws IOException {
        this.folder = folder;
        this.clockMillis = clockMillis;
        Files.createDirectories(folder);
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
    }

    /** A new session and the cookie value for it. The cookie is shown to the browser once and never kept. */
    public record Created(Session session, String cookie) {}

    /**
     * Signs someone in. Any session the browser already had is ended first, so a sign-in always rotates the cookie.
     */
    public synchronized Created signIn(UUID uuid, String name, Duration lifetime, String previousCookie) throws IOException {
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(MAX_LIFETIME) > 0)
            throw new IllegalArgumentException("A session lasts between 1 second and 30 days.");
        if (!BODY.matcher(Json.write(body(uuid, name, 0))).matches())
            throw new IllegalArgumentException("Not a Minecraft name: " + name);
        if (previousCookie != null) signOut(previousCookie);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String cookie = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Session s = new Session(uuid, name, nowSeconds() + lifetime.toSeconds());
        Path tmp = Files.createTempFile(folder, "session", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(tmp, Json.write(body(uuid, name, s.expires())) + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, fileFor(cookie), StandardCopyOption.ATOMIC_MOVE);
        return new Created(s, cookie);
    }

    /** The live session for a cookie, or empty. An expired or unreadable session file is deleted on the way. */
    public synchronized Optional<Session> lookup(String cookie) throws IOException {
        if (cookie == null || !COOKIE.matcher(cookie).matches()) return Optional.empty();
        Path f = fileFor(cookie);
        Optional<Session> s = read(f);
        if (s.isEmpty() || !s.get().live(nowSeconds())) {
            Files.deleteIfExists(f);
            return Optional.empty();
        }
        return s;
    }

    /** Ends one browser's session. True if there was one. */
    public synchronized boolean signOut(String cookie) throws IOException {
        if (cookie == null || !COOKIE.matcher(cookie).matches()) return false;
        return Files.deleteIfExists(fileFor(cookie));
    }

    /**
     * Ends every session for a player, for when they're taken off the whitelist or banned. Unreadable files are deleted
     * too, since nobody can say whose they are. Returns how many sessions belonging to the player were ended.
     */
    public synchronized int signOutEverywhere(UUID uuid) throws IOException {
        int ended = 0;
        for (Path f : files()) {
            Optional<Session> s = read(f);
            if (s.isEmpty()) { Files.deleteIfExists(f); continue; }
            if (s.get().uuid().equals(uuid) && Files.deleteIfExists(f)) ended++;
        }
        return ended;
    }

    /** Deletes expired and unreadable session files. Returns how many went. */
    public synchronized int sweep() throws IOException {
        int gone = 0;
        long now = nowSeconds();
        for (Path f : files()) {
            Optional<Session> s = read(f);
            if ((s.isEmpty() || !s.get().live(now)) && Files.deleteIfExists(f)) gone++;
        }
        return gone;
    }

    /** How many live sessions a player has. */
    public synchronized int count(UUID uuid) throws IOException {
        int n = 0;
        long now = nowSeconds();
        for (Path f : files()) {
            Optional<Session> s = read(f);
            if (s.isPresent() && s.get().live(now) && s.get().uuid().equals(uuid)) n++;
        }
        return n;
    }

    private static Map<String, Object> body(UUID uuid, String name, long expires) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uuid", uuid.toString());
        m.put("name", name);
        m.put("expires", expires);
        return m;
    }

    private Optional<Session> read(Path f) throws IOException {
        String text;
        try { text = Files.readString(f, StandardCharsets.UTF_8); }
        catch (NoSuchFileException e) { return Optional.empty(); }
        Matcher m = BODY.matcher(text);
        if (!m.matches()) return Optional.empty();
        try {
            return Optional.of(new Session(UUID.fromString(m.group(1)), m.group(2), Long.parseLong(m.group(3))));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private java.util.List<Path> files() throws IOException {
        java.util.List<Path> out = new java.util.ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
            for (Path p : ds) if (FILE.matcher(p.getFileName().toString()).matches()) out.add(p);
        }
        return out;
    }

    private Path fileFor(String cookie) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(cookie.getBytes(StandardCharsets.US_ASCII));
            return folder.resolve(HexFormat.of().formatHex(h) + ".json");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private long nowSeconds() { return Math.floorDiv(clockMillis.getAsLong(), 1000L); }
}

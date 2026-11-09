package com.extantape.latchkey.invite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Invites on disk, in {@code invites.tsv} (one per line, tab-separated, readable without the plugin) next to
 * {@code invite-salt} (32 random bytes). Both owner-only (0600). Every change rewrites the file through a temporary file
 * and an atomic rename, under one lock, so two redemptions of the same code can never both succeed.
 */
public final class InviteStore {
    public static final Duration MAX_LIFETIME = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private final byte[] salt;
    private final LongSupplier clock;
    private final Map<String, Invite> byId = new LinkedHashMap<>();

    public InviteStore(Path folder, LongSupplier clock) throws IOException {
        Files.createDirectories(folder);
        this.file = folder.resolve("invites.tsv");
        this.clock = clock;
        this.salt = loadOrMakeSalt(folder.resolve("invite-salt"));
        if (Files.exists(file)) for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            Invite inv = parse(line);
            byId.put(inv.id(), inv);
        }
    }

    /** What creating an invite gives back: the record, and the code, which is never stored and shown only once. */
    public record Created(Invite invite, String code) {}

    public synchronized Created create(UUID uuid, String name, List<String> profiles, Duration lifetime) throws IOException {
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(MAX_LIFETIME) > 0)
            throw new IllegalArgumentException("An invite lasts between 1 second and 30 days.");
        String code, hash;
        do { code = InviteCodes.generate(); hash = InviteCodes.hash(salt, code); } while (findByHash(hash).isPresent());
        long now = clock.getAsLong();
        String id = HexFormat.of().formatHex(randomBytes(4));
        Invite inv = new Invite(id, uuid, name, hash, now, now + lifetime.toMillis(), List.copyOf(profiles), Invite.Status.PENDING, now);
        byId.put(id, inv);
        save();
        return new Created(inv, code);
    }

    /**
     * Redeems a code for a name, atomically. Empty for every kind of failure alike (wrong, used, revoked, expired, or
     * for someone else), so the landing page can answer them all the same way.
     */
    public synchronized Optional<Invite> redeem(String typedCode, String typedName) throws IOException {
        String code = InviteCodes.normalize(typedCode);
        // Hash even a code that can't be valid, so a bad code takes as long as a good one.
        String hash = InviteCodes.hash(salt, code == null ? "------" : code);
        Optional<Invite> found = code == null ? Optional.empty() : findByHash(hash);
        long now = clock.getAsLong();
        if (found.isEmpty() || !found.get().usable(now) || typedName == null
                || !found.get().name().toLowerCase(Locale.ROOT).equals(typedName.trim().toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        Invite done = found.get().with(Invite.Status.REDEEMED, now);
        byId.put(done.id(), done);
        save();
        return Optional.of(done);
    }

    public synchronized boolean revoke(String id) throws IOException {
        Invite inv = byId.get(id);
        if (inv == null || inv.status() != Invite.Status.PENDING) return false;
        byId.put(id, inv.with(Invite.Status.REVOKED, clock.getAsLong()));
        save();
        return true;
    }

    public synchronized List<Invite> all() { return List.copyOf(byId.values()); }

    private Optional<Invite> findByHash(String hash) {
        byte[] want = hash.getBytes(StandardCharsets.US_ASCII);
        Invite hit = null;
        for (Invite inv : byId.values()) {
            // Compare every stored hash in constant time, so timing says nothing about which one matched.
            if (MessageDigest.isEqual(want, inv.codeHash().getBytes(StandardCharsets.US_ASCII))) hit = inv;
        }
        return Optional.ofNullable(hit);
    }

    private void save() throws IOException {
        StringBuilder b = new StringBuilder("# Latchkey invites. id, uuid, name, code hash, created, expires, profiles, status, changed. Times are Unix milliseconds.\n");
        for (Invite i : byId.values()) {
            b.append(String.join("\t", i.id(), i.uuid().toString(), i.name(), i.codeHash(), Long.toString(i.created()),
                    Long.toString(i.expires()), String.join(",", i.profiles()), i.status().name(), Long.toString(i.changed()))).append('\n');
        }
        Path tmp = Files.createTempFile(file.getParent(), "invites", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(tmp, b, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Invite parse(String line) {
        String[] f = line.split("\t", -1);
        if (f.length != 9) throw new IllegalStateException("invites.tsv has a line with " + f.length + " fields, not 9");
        List<String> profiles = f[6].isEmpty() ? List.of() : List.of(f[6].split(","));
        return new Invite(f[0], UUID.fromString(f[1]), f[2], f[3], Long.parseLong(f[4]), Long.parseLong(f[5]),
                profiles, Invite.Status.valueOf(f[7]), Long.parseLong(f[8]));
    }

    private static byte[] loadOrMakeSalt(Path p) throws IOException {
        if (Files.exists(p)) {
            byte[] s = Files.readAllBytes(p);
            if (s.length != 32) throw new IOException("invite-salt isn't 32 bytes; refusing to guess");
            return s;
        }
        byte[] s = randomBytes(32);
        Path tmp = Files.createTempFile(p.getParent(), "salt", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(tmp, s);
        Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE);
        return s;
    }

    private static byte[] randomBytes(int n) { byte[] b = new byte[n]; RANDOM.nextBytes(b); return b; }
}

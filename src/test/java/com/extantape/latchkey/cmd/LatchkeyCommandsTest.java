package com.extantape.latchkey.cmd;

import com.extantape.latchkey.invite.Invite;
import com.extantape.latchkey.invite.InviteStore;
import com.extantape.latchkey.session.SessionStore;
import com.extantape.signalbox.OnceFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class LatchkeyCommandsTest {
    static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @TempDir Path dir;
    InviteStore invites;
    SessionStore sessions;
    OnceFiles once;
    boolean open = true;
    /** What the fake Usher says: a refusal, questions, or nobody installed. */
    String refuse;
    Map<String, String> questions = Map.of();
    boolean usherInstalled = true;
    int reloads;
    final List<String> logged = new ArrayList<>();
    final Logger log = Logger.getAnonymousLogger();
    LatchkeyCommands cmds;

    final Host host = new Host() {
        public CompletableFuture<Optional<Profile>> resolve(String who) {
            if (who.equalsIgnoreCase("alex") || who.equals(ALEX.toString())) return CompletableFuture.completedFuture(Optional.of(new Profile(ALEX, "Alex")));
            if (who.equals("Flaky")) return CompletableFuture.failedFuture(new RuntimeException("Mojang is down"));
            return CompletableFuture.completedFuture(Optional.empty());
        }
        public InviteStore invites() { return invites; }
        public SessionStore sessions() { return sessions; }
        public OnceFiles once() { return once; }
        public Duration defaultInviteLifetime() { return Duration.ofDays(14); }
        public boolean redemptionOpen() { return open; }
        public Status status() { return new Status(false, true, open, true, true, "https://play.example.org", open ? List.of() : List.of("The server's whitelist is off. Fix: /whitelist on.")); }
        public void reload() { reloads++; }
        public String version() { return "0.2.0"; }
        public void sync(Runnable r) { r.run(); }
        public ProfileCheck checkProfiles(UUID uuid, String name, List<String> profiles, Set<String> confirmed) {
            var e = new com.extantape.latchkey.api.InviteCheckEvent(uuid, name, profiles, confirmed);
            if (usherInstalled) {
                e.checkedBy("Usher");
                if (refuse != null) e.refuse("restricted", refuse);
                questions.forEach(e::need);
            }
            return new ProfileCheck(e.getRefusalCode(), e.getRefusalMessage(), e.getAsked(), e.getUnanswered(), e.getCheckedBy());
        }
    };

    static final class Who implements Caller {
        final Set<String> perms = new HashSet<>();
        final boolean inGame;
        final List<String> told = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        Who(boolean inGame, String... perms) { this.inGame = inGame; this.perms.addAll(List.of(perms)); }
        public boolean has(String p) { return perms.contains(p); }
        public boolean inGame() { return inGame; }
        public void tell(String text, boolean problem) { told.add(text); }
        public void line(String raw) { lines.add(raw); }
        String only() { assertEquals(1, lines.size(), "exactly one answer: " + lines); return lines.getFirst(); }
    }

    static Who admin(boolean inGame) {
        return new Who(inGame, LatchkeyCommands.P_CREATE, LatchkeyCommands.P_LIST, LatchkeyCommands.P_REVOKE,
                LatchkeyCommands.P_SESSIONS, LatchkeyCommands.P_STATUS, LatchkeyCommands.P_RELOAD);
    }

    @BeforeEach void up() throws Exception {
        invites = new InviteStore(dir, System::currentTimeMillis);
        sessions = new SessionStore(dir.resolve("sessions"), System::currentTimeMillis);
        once = new OnceFiles(dir);
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            public void publish(LogRecord r) { logged.add(r.getMessage()); }
            public void flush() {}
            public void close() {}
        });
        cmds = new LatchkeyCommands(host, log);
    }

    void run(Caller c, String line) { cmds.run(c, line.split(" ")); }

    static String code(Path onceFile) throws Exception {
        Matcher m = Pattern.compile("\"value\":\"([^\"]+)\"").matcher(Files.readString(onceFile));
        assertTrue(m.find());
        return m.group(1);
    }

    // --- invite create ---

    @Test void jsonCreateKeepsTheCodeOutOfTheAnswerAndTheLog() throws Exception {
        var c = admin(false);
        run(c, "invite create Alex builders,miners 7d --json k1");
        String line = c.only();
        assertTrue(line.startsWith("ea-result k1 {\"id\":\"k1\",\"ok\":true"), line);
        assertTrue(line.contains("\"secretFile\":\"once/k1.json\""), line);
        assertTrue(line.contains("\"profiles\":[\"builders\",\"miners\"]"), line);
        Path f = dir.resolve("once/k1.json");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
        String code = code(f);
        assertFalse(line.contains(code));
        for (String l : logged) assertFalse(l.contains(code), "the log never has the code: " + l);
        assertTrue(invites.redeem(code, "Alex").isPresent(), "the code in the file works");
    }

    @Test void inGameTheCodeGoesPrivatelyToTheAdmin() throws Exception {
        var c = admin(true);
        run(c, "invite create alex");
        String all = String.join("\n", c.told);
        Matcher m = Pattern.compile("Code: ([0-9A-Z]{6})").matcher(all);
        assertTrue(m.find(), all);
        assertTrue(all.contains("lobby only"));
        for (String l : logged) assertFalse(l.contains(m.group(1)));
        assertFalse(Files.exists(dir.resolve("once")), "no file needed in game");
        assertTrue(invites.redeem(m.group(1), "Alex").isPresent());
    }

    @Test void onTheConsoleTheCodeGoesToAFileNotTheLog() throws Exception {
        var c = admin(false);
        run(c, "invite create Alex");
        Invite inv = invites.all().getFirst();
        Path f = dir.resolve("once/invite-" + inv.id() + ".json");
        String code = code(f);
        for (String t : c.told) assertFalse(t.contains(code), "the console never shows the code: " + t);
        for (String l : logged) assertFalse(l.contains(code));
        assertTrue(String.join(" ", c.told).contains("once/invite-" + inv.id() + ".json"));
    }

    @Test void createRefusesBadInput() {
        var c = admin(false);
        run(c, "invite create Alex none 31d --json a");
        assertTrue(c.only().contains("\"code\":\"bad-argument\""));
        c.lines.clear();
        run(c, "invite create Alex Bad_Profile --json b");
        assertTrue(c.only().contains("\"code\":\"bad-argument\""));
        c.lines.clear();
        run(c, "invite create x --json c");
        assertTrue(c.only().contains("\"code\":\"bad-argument\""));
        c.lines.clear();
        run(c, "invite create Alex none 30s --json d");
        assertTrue(c.only().contains("\"code\":\"bad-argument\""));
        assertTrue(invites.all().isEmpty());
    }

    @Test void profileIdsWithUnderscoresAreAccepted() throws Exception {
        var c = admin(false);
        run(c, "invite create Alex grown_sky,builders --json a");
        assertTrue(c.only().contains("\"profiles\":[\"grown_sky\",\"builders\"]"));
    }

    @Test void createRefusesUnknownPlayersAndSaysWhenItCantLookUp() {
        var c = admin(false);
        run(c, "invite create Nobody --json a");
        assertTrue(c.only().contains("\"code\":\"not-found\""));
        c.lines.clear();
        run(c, "invite create Flaky --json b");
        assertTrue(c.only().contains("\"code\":\"busy\""));
        assertTrue(invites.all().isEmpty());
    }

    @Test void noInvitesAreMadeWhileTheyCantBeUsed() {
        open = false;
        var c = admin(false);
        run(c, "invite create Alex --json a");
        assertTrue(c.only().contains("\"code\":\"not-open\""));
        assertTrue(invites.all().isEmpty());
    }

    // --- profiles checked by Usher at creation ---

    @Test void aRefusedProfileMakesNoInvite() {
        refuse = "The Admins group can only be changed from the console.";
        var c = admin(false);
        run(c, "invite create Alex admins --json a");
        String line = c.only();
        assertTrue(line.contains("\"code\":\"profiles-refused\"") && line.contains("only be changed from the console"), line);
        assertTrue(invites.all().isEmpty());
    }

    @Test void questionsMustBeAnsweredBeforeTheInviteIsMade() throws Exception {
        questions = Map.of("confirm-builders", "Builders can fly. Is that all right?");
        var c = admin(false);
        run(c, "invite create Alex builders --json a");
        String line = c.only();
        assertTrue(line.contains("\"needs\":[{\"id\":\"confirm-builders\""), line);
        assertTrue(invites.all().isEmpty(), "nothing made yet");
        c.lines.clear();
        run(c, "invite create Alex builders --yes confirm-builders,unrelated --json b");
        assertTrue(c.only().contains("\"confirmed\":[\"confirm-builders\"]"), "only real answers are kept");
        assertEquals(Set.of("confirm-builders"), invites.all().getFirst().confirmed());
    }

    @Test void peopleAreToldHowToAnswer() {
        questions = Map.of("bridge", "This links two chat circles.");
        var c = admin(true);
        run(c, "invite create Alex builders");
        String all = String.join("\n", c.told);
        assertTrue(all.contains("bridge: This links two chat circles.") && all.contains("--yes bridge"), all);
        assertTrue(invites.all().isEmpty());
    }

    @Test void withoutUsherTheInviteIsMadeLobbyOnlyWithAWarning() {
        usherInstalled = false;
        var c = admin(false);
        run(c, "invite create Alex builders --json a");
        String line = c.only();
        assertTrue(line.contains("\"ok\":true") && line.contains("lobby-only"), line);
        assertTrue(logged.stream().anyMatch(l -> l.contains("no plugin checked them")));
    }

    @Test void anInviteWithNoProfilesAsksNobody() {
        refuse = "would refuse";
        var c = admin(false);
        run(c, "invite create Alex --json a");
        assertTrue(c.only().contains("\"ok\":true"));
    }

    // --- permissions ---

    @Test void everyCommandChecksItsOwnPermission() throws Exception {
        invites.create(ALEX, "Alex", List.of(), Duration.ofDays(1));
        String id = invites.all().getFirst().id();
        Map<String, String> needs = Map.of(
                "invite create Alex", LatchkeyCommands.P_CREATE,
                "invite list", LatchkeyCommands.P_LIST,
                "invite revoke " + id, LatchkeyCommands.P_REVOKE,
                "session list Alex", LatchkeyCommands.P_SESSIONS,
                "session revoke Alex", LatchkeyCommands.P_SESSIONS,
                "status", LatchkeyCommands.P_STATUS,
                "reload", LatchkeyCommands.P_RELOAD,
                "describe", LatchkeyCommands.P_STATUS);
        for (var e : needs.entrySet()) {
            var everythingElse = admin(false);
            everythingElse.perms.remove(e.getValue());
            run(everythingElse, e.getKey() + " --json p");
            assertTrue(everythingElse.only().contains("\"code\":\"no-permission\""), e.getKey());
        }
        assertEquals(1, invites.all().size());
        assertEquals(Invite.Status.PENDING, invites.all().getFirst().status());
        assertEquals(0, reloads);
    }

    // --- list and revoke ---

    @Test void listPagesNewestFirstWithoutHashes() throws Exception {
        for (int i = 0; i < 5; i++) { invites.create(ALEX, "Alex", List.of(), Duration.ofDays(1)); Thread.sleep(2); }
        var c = admin(false);
        run(c, "invite list 2 --json a");
        String first = c.only();
        assertTrue(first.contains("\"next\":2"), first);
        assertFalse(first.contains("codeHash") || first.contains(invites.all().getFirst().codeHash()));
        c.lines.clear();
        run(c, "invite list 2 4 --json b");
        assertTrue(c.only().contains("\"next\":null"));
        c.lines.clear();
        run(c, "invite list 101 --json c");
        assertTrue(c.only().contains("bad-argument"));
    }

    @Test void revokeWorksOnceAndSaysWhy() throws Exception {
        String code = invites.create(ALEX, "Alex", List.of(), Duration.ofDays(1)).code();
        String id = invites.all().getFirst().id();
        var c = admin(false);
        run(c, "invite revoke " + id + " --json a");
        assertTrue(c.only().contains("\"status\":\"revoked\""));
        assertTrue(invites.redeem(code, "Alex").isEmpty());
        c.lines.clear();
        run(c, "invite revoke " + id + " --json b");
        assertTrue(c.only().contains("\"code\":\"not-pending\""));
        c.lines.clear();
        run(c, "invite revoke 00000000 --json c");
        assertTrue(c.only().contains("\"code\":\"not-found\""));
        c.lines.clear();
        run(c, "invite revoke ../x --json d");
        assertTrue(c.only().contains("\"code\":\"bad-argument\""));
    }

    // --- sessions ---

    @Test void sessionRevokeSignsAPlayerOutEverywhere() throws Exception {
        sessions.signIn(ALEX, "Alex", Duration.ofHours(1), null);
        sessions.signIn(ALEX, "Alex", Duration.ofHours(1), null);
        var c = admin(false);
        run(c, "session list Alex --json a");
        assertTrue(c.only().contains("\"sessions\":2"));
        c.lines.clear();
        run(c, "session revoke " + ALEX + " --json b");
        assertTrue(c.only().contains("\"ended\":2"));
        assertEquals(0, sessions.count(ALEX));
        try (var s = Files.list(dir.resolve("sessions"))) { assertEquals(0, s.count()); }
    }

    // --- status, reload, describe, unknown ---

    @Test void statusShowsProblemsWithTheirFixes() {
        open = false;
        var c = admin(false);
        run(c, "status --json a");
        String line = c.only();
        assertTrue(line.contains("\"redemption\":false") && line.contains("Fix: /whitelist on."), line);
        var person = admin(false);
        run(person, "status");
        assertTrue(person.told.stream().anyMatch(t -> t.contains("Fix: /whitelist on.")));
    }

    @Test void reloadRerunsTheChecksAndAnswersWithStatus() {
        var c = admin(false);
        run(c, "reload --json a");
        assertEquals(1, reloads);
        assertTrue(c.only().contains("\"landing\":true"));
    }

    @Test void describeListsEveryCommandWithFormatOne() {
        var c = admin(false);
        run(c, "describe --json a");
        String line = c.only();
        assertTrue(line.contains("\"format\":1"), line);
        for (var d : LatchkeyCommands.described()) assertTrue(line.contains("\"" + d.name() + "\""), d.name());
        assertTrue(line.contains("\"secret\":true"));
    }

    @Test void describeMarksTheOptionalArguments() {
        var c = admin(false);
        run(c, "describe --json a");
        String line = c.only();
        assertTrue(line.contains("{\"name\":\"player\",\"type\":\"player\"}"), "required, no optional key: " + line);
        assertTrue(line.contains("\"name\":\"profiles\",\"type\":\"list\",\"of\":\"string\",\"values\":[\"none\"],\"optional\":true"), line);
        assertTrue(line.contains("{\"name\":\"lifetime\",\"type\":\"duration\",\"optional\":true}"), line);
    }

    @Test void unknownAndMalformedRequestsStillGetOneAnswer() {
        var c = admin(false);
        run(c, "frobnicate --json a");
        assertTrue(c.only().contains("\"code\":\"unknown-command\""));
        c.lines.clear();
        run(c, "status extra --json b");
        assertTrue(c.only().contains("\"code\":\"bad-argument\""));
        c.lines.clear();
        run(c, "invite create -Alex --json e");
        assertTrue(c.only().contains("\"code\":\"bad-argument\""));
    }

    @Test void withoutJsonPeopleGetPlainWords() {
        var c = admin(false);
        run(c, "nonsense");
        assertTrue(c.lines.isEmpty());
        assertTrue(c.told.getFirst().startsWith("Latchkey commands:"));
    }
}

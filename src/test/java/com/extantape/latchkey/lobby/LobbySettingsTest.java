package com.extantape.latchkey.lobby;

import com.extantape.latchkey.lobby.LobbySettings.Send;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LobbySettingsTest {
    static LobbySettings read(String yaml) {
        return LobbySettings.read(YamlConfiguration.loadConfiguration(new StringReader(yaml)).getConfigurationSection("lobby"));
    }

    @Test void offUntilAWorldIsSet() {
        assertFalse(read("lobby:\n  world: \"\"\n").enabled());
        assertFalse(LobbySettings.read(null).enabled());
        assertTrue(read("lobby:\n  world: lobby\n").enabled());
    }

    @Test void theShippedConfigLeavesTheLobbyOffWithNoProblems() throws Exception {
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            var s = LobbySettings.read(YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in)).getConfigurationSection("lobby"));
            assertFalse(s.enabled());
            assertEquals(List.of(), s.problems());
            assertEquals(Send.FIRST_JOIN, s.send());
        }
    }

    @Test void whoIsSent() {
        assertTrue(Send.FIRST_JOIN.sends(true));
        assertFalse(Send.FIRST_JOIN.sends(false));
        assertTrue(Send.ALWAYS.sends(false));
        assertFalse(Send.NEVER.sends(true));
        assertEquals(Send.ALWAYS, read("lobby:\n  world: lobby\n  send: always\n").send());
    }

    @Test void aSpawnPointNeedsAllThreeCoordinates() {
        var s = read("lobby:\n  world: lobby\n  spawn: {x: 0.5, y: 65, z: 0.5, yaw: 180}\n");
        assertTrue(s.enabled());
        assertEquals(180f, s.spawn().yaw());
        var bad = read("lobby:\n  world: lobby\n  spawn: {x: 1, z: 2}\n");
        assertFalse(bad.enabled());
        assertTrue(bad.problems().getFirst().contains("x, y and z"));
        assertNull(read("lobby:\n  world: lobby\n").spawn(), "the world's own spawn");
    }

    @Test void problemsTurnTheLobbyOffAndSayHowToFixThem() {
        for (String yaml : List.of(
                "lobby:\n  world: lobby\n  send: sometimes\n",
                "lobby:\n  world: lobby\n  spawn: {x: 0, y: 9999, z: 0}\n",
                "lobby:\n  world: lobby\n  give-items: true\n",
                "lobby:\n  world: lobby\n  welcome: {title: \"" + "x".repeat(61) + "\"}\n",
                "lobby:\n  world: lobby\n  welcome: {chat: [" + "a,".repeat(9) + "a]}\n")) {
            var s = read(yaml);
            assertFalse(s.enabled(), yaml);
            assertTrue(s.problems().stream().allMatch(p -> p.contains("Fix: ")), s.problems().toString());
        }
    }

    @Test void theWelcomeIsReadAsWritten() {
        var s = read("lobby:\n  world: lobby\n  welcome:\n    title: Welcome\n    subtitle: Walk through the gate\n    chat: [\"Hi!\", \"Rules are at the board.\"]\n");
        assertEquals("Welcome", s.title());
        assertEquals(List.of("Hi!", "Rules are at the board."), s.chat());
    }
}

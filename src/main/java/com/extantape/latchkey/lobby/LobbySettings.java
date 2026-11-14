package com.extantape.latchkey.lobby;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The lobby: where arriving players are sent, and what a first-timer is told. Off until a world is set. A problem here
 * only turns the lobby off; invites, sign-in and the page carry on.
 *
 * <p>Nothing about players is stored: whether someone is new comes from the server's own record of who has played.
 */
public record LobbySettings(boolean enabled, String world, Spawn spawn, Send send, String title, String subtitle,
                            List<String> chat, List<String> problems) {

    /** Who is sent to the lobby when they join. */
    public enum Send {
        FIRST_JOIN, ALWAYS, NEVER;

        public boolean sends(boolean firstJoin) {
            return this == ALWAYS || (this == FIRST_JOIN && firstJoin);
        }
    }

    /** A spot in the lobby world. Null means the world's own spawn. */
    public record Spawn(double x, double y, double z, float yaw, float pitch) {}

    static final Set<String> KEYS = Set.of("world", "spawn", "send", "welcome", "rewild-keep", "rewild-keep-radius");
    static final Set<String> SPAWN_KEYS = Set.of("x", "y", "z", "yaw", "pitch");
    static final Set<String> WELCOME_KEYS = Set.of("title", "subtitle", "chat");
    static final int MAX_LINES = 8, MAX_LINE = 200;

    public static LobbySettings read(ConfigurationSection lobby) {
        List<String> problems = new ArrayList<>();
        if (lobby == null) return new LobbySettings(false, "", null, Send.FIRST_JOIN, "", "", List.of(), List.of());
        for (String k : lobby.getKeys(false)) if (!KEYS.contains(k))
            problems.add("Latchkey has no setting called lobby." + k + ". Fix: remove it, or check the spelling against the default config.yml.");

        String world = lobby.getString("world", "").trim();

        Spawn spawn = null;
        ConfigurationSection s = lobby.getConfigurationSection("spawn");
        if (s != null) {
            for (String k : s.getKeys(false)) if (!SPAWN_KEYS.contains(k))
                problems.add("Latchkey has no setting called lobby.spawn." + k + ". Fix: use x, y, z, yaw and pitch.");
            if (!s.isSet("x") || !s.isSet("y") || !s.isSet("z")) {
                problems.add("lobby.spawn needs x, y and z. Fix: give all three, or remove spawn to use the world's own spawn.");
            } else {
                double y = s.getDouble("y");
                if (y < -64 || y > 320) problems.add("lobby.spawn y is " + y + ". Fix: use a height from -64 to 320.");
                else spawn = new Spawn(s.getDouble("x"), y, s.getDouble("z"), (float) s.getDouble("yaw", 0), (float) s.getDouble("pitch", 0));
            }
        }

        Send send = Send.FIRST_JOIN;
        String sendText = lobby.getString("send", "first-join");
        try {
            send = Send.valueOf(sendText.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            problems.add("lobby.send is \"" + sendText + "\". Fix: use first-join, always or never.");
        }

        String title = "", subtitle = "";
        List<String> chat = List.of();
        ConfigurationSection w = lobby.getConfigurationSection("welcome");
        if (w != null) {
            for (String k : w.getKeys(false)) if (!WELCOME_KEYS.contains(k))
                problems.add("Latchkey has no setting called lobby.welcome." + k + ". Fix: use title, subtitle and chat.");
            title = w.getString("title", "");
            subtitle = w.getString("subtitle", "");
            chat = w.getStringList("chat");
            if (title.length() > 60 || subtitle.length() > 80)
                problems.add("lobby.welcome title or subtitle is too long to read on screen. Fix: keep the title under 60 characters and the subtitle under 80.");
            if (chat.size() > MAX_LINES || chat.stream().anyMatch(l -> l.length() > MAX_LINE))
                problems.add("lobby.welcome.chat is too long. Fix: use at most " + MAX_LINES + " lines of up to " + MAX_LINE + " characters.");
        }

        boolean enabled = !world.isEmpty() && problems.isEmpty();
        return new LobbySettings(enabled, world, spawn, send, title, subtitle, List.copyOf(chat), List.copyOf(problems));
    }
}

package com.extantape.latchkey.lobby;

import com.extantape.latchkey.api.FirstJoinEvent;
import com.extantape.latchkey.api.PlayerArrivedEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/** Sends arriving players to the lobby, welcomes first-timers, and tells other plugins. */
public final class Lobby implements Listener {
    private final Plugin plugin;
    private final Logger log;
    private volatile LobbySettings settings = LobbySettings.read(null);
    private boolean warnedMissing;

    public Lobby(Plugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public void apply(LobbySettings s) {
        settings = s;
        warnedMissing = false;
        for (String p : s.problems()) log.warning(p);
        // Checked a tick later, once every plugin has loaded its worlds.
        Bukkit.getScheduler().runTask(plugin, () -> { if (settings == s && s.enabled() && Bukkit.getWorld(s.world()) == null) missing(s); });
    }

    public LobbySettings settings() { return settings; }

    /** Where the lobby is right now, or null if it's off or its world isn't loaded. */
    public Location location() {
        LobbySettings s = settings;
        if (!s.enabled()) return null;
        World w = Bukkit.getWorld(s.world());
        if (w == null) { missing(s); return null; }
        if (s.spawn() == null) return w.getSpawnLocation();
        return new Location(w, s.spawn().x(), s.spawn().y(), s.spawn().z(), s.spawn().yaw(), s.spawn().pitch());
    }

    /**
     * The chunks around the lobby's spawn, for Rewild to leave whole, within {@code radius} chunks. Empty unless
     * {@code w} is the lobby's world.
     */
    public java.util.List<int[]> rewildChunks(World w, int radius) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        Location at = location();
        if (at == null || !at.getWorld().getUID().equals(w.getUID())) return out;
        int cx = at.getBlockX() >> 4, cz = at.getBlockZ() >> 4;
        for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++) out.add(new int[]{x, z});
        return out;
    }

    /** The lobby's problem for /latchkey status, or null. */
    public String problem() {
        LobbySettings s = settings;
        if (!s.problems().isEmpty()) return s.problems().getFirst();
        if (s.enabled() && Bukkit.getWorld(s.world()) == null) return missingText(s);
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        boolean first = !p.hasPlayedBefore();
        if (first) Bukkit.getPluginManager().callEvent(new FirstJoinEvent(p));
        LobbySettings s = settings;
        Location lobby = location();
        if (lobby != null && s.send().sends(first)) {
            p.teleportAsync(lobby).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                boolean sent = err == null && Boolean.TRUE.equals(ok);
                if (!sent) log.warning("Couldn't send " + p.getName() + " to the lobby; they start where the server put them.");
                arrived(p, first, lobby, sent, s);
            }));
        } else {
            arrived(p, first, lobby, false, s);
        }
    }

    private void arrived(Player p, boolean first, Location lobby, boolean sent, LobbySettings s) {
        if (first && s.enabled()) welcome(p, s);
        Bukkit.getPluginManager().callEvent(new PlayerArrivedEvent(p, first, lobby, sent));
    }

    private static void welcome(Player p, LobbySettings s) {
        if (!s.title().isEmpty() || !s.subtitle().isEmpty())
            p.showTitle(Title.title(Component.text(s.title()), Component.text(s.subtitle())));
        for (String line : s.chat()) p.sendMessage(Component.text(line));
    }

    private void missing(LobbySettings s) {
        if (warnedMissing) return;
        warnedMissing = true;
        log.warning(missingText(s));
    }

    private static String missingText(LobbySettings s) {
        return "lobby.world is \"" + s.world() + "\", but no world by that name is loaded, so nobody is sent to the lobby. "
                + "Fix: load or create that world (with Multiverse, /mv create " + s.world() + " normal), then /latchkey reload.";
    }
}

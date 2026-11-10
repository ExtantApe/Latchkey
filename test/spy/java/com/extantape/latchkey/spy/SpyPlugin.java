package com.extantape.latchkey.spy;

import com.extantape.latchkey.api.FirstJoinEvent;
import com.extantape.latchkey.api.PlayerArrivedEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/** Test only: prints Latchkey's lobby events as SPY lines, with where the player actually is. */
public final class SpyPlugin extends JavaPlugin implements Listener {
    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        // The method other plugins read by name, checked the same way they'd call it.
        getServer().getScheduler().runTask(this, () -> {
            try {
                var lk = getServer().getPluginManager().getPlugin("Latchkey");
                Object at = lk.getClass().getMethod("lobbyLocation").invoke(lk);
                getLogger().info("SPY lobbyLocation=" + (at == null ? "none" : ((Location) at).getWorld().getName() + "," + ((Location) at).getBlockX() + "," + ((Location) at).getBlockZ()));
            } catch (ReflectiveOperationException e) {
                getLogger().info("SPY lobbyLocation=missing " + e);
            }
        });
    }

    @EventHandler public void on(FirstJoinEvent e) {
        getLogger().info("SPY first name=" + e.getPlayer().getName() + " main=" + Bukkit.isPrimaryThread());
    }

    @EventHandler public void on(PlayerArrivedEvent e) {
        Location at = e.getPlayer().getLocation(), lobby = e.getLobby();
        getLogger().info("SPY arrived name=" + e.getPlayer().getName() + " first=" + e.isFirstJoin() + " sent=" + e.isSentToLobby()
                + " lobby=" + (lobby == null ? "none" : lobby.getWorld().getName() + "," + lobby.getBlockX() + "," + lobby.getBlockZ())
                + " at=" + at.getWorld().getName() + "," + at.getBlockX() + "," + at.getBlockZ() + " main=" + Bukkit.isPrimaryThread());
    }
}

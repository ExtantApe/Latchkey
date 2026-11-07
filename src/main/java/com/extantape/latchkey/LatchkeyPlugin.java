package com.extantape.latchkey;

import java.io.IOException;
import java.time.Instant;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class LatchkeyPlugin extends JavaPlugin {
    /** Bots can't log in to an online-mode server. Only something that controls the server's launch can set this. */
    private static final String TESTING_FLAG = "latchkey.allowOfflineForTesting";

    private SignInLinks links;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        links = new SignInLinks(getDataFolder().toPath().resolve("pending"));
        clearLinks();
        if (!identitiesProven()) {
            getLogger().warning("online-mode is off, so a name proves nothing. /signin will refuse to make links.");
        }
    }

    @Override
    public void onDisable() {
        clearLinks();
    }

    private void clearLinks() {
        try {
            links.removeAll();
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Couldn't clear old sign-in links", e);
        }
    }

    private static boolean identitiesProven() {
        return Bukkit.getOnlineMode() || Boolean.getBoolean(TESTING_FLAG);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player in game can ask for a sign-in link.", NamedTextColor.RED));
            return true;
        }
        if (!identitiesProven()) {
            refuse(player, "Sign-in is off because this server doesn't check who players are.");
            return true;
        }
        if (!player.isWhitelisted()) {
            refuse(player, "You aren't on this server's allowlist.");
            return true;
        }
        reloadConfig();
        String page = getConfig().getString("sign-in-page", "");
        if (!SignInLinks.usablePage(page)) {
            refuse(player, "Sign-in hasn't been set up on this server yet.");
            return true;
        }
        String token;
        try {
            token = links.issue(player.getUniqueId(), player.getName(), Instant.now());
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Couldn't save a sign-in link", e);
            refuse(player, "That didn't work. Tell whoever runs this server.");
            return true;
        }
        if (token == null) {
            refuse(player, "You just asked for one. Try again in a few seconds.");
            return true;
        }
        String link = SignInLinks.link(page.trim(), token);
        player.sendMessage(Component.text("Click here to sign in", NamedTextColor.GREEN, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(link))
                .hoverEvent(HoverEvent.showText(Component.text("Works once, for two minutes. Don't share it."))));
        player.sendMessage(Component.text("It works once, for two minutes. Don't share it.", NamedTextColor.GRAY));
        return true;
    }

    private static void refuse(Player player, String why) {
        player.sendMessage(Component.text(why, NamedTextColor.RED));
    }
}

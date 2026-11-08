package com.extantape.latchkey;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.time.Instant;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class LatchkeyPlugin extends JavaPlugin {
    /** Bots can't log in to an online-mode server. Only something that controls the server's launch can set this. */
    private static final String TESTING_FLAG = "latchkey.allowOfflineForTesting";
    private static final String PERMISSION = "latchkey.signin";

    private SignInLinks links;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        links = new SignInLinks(getDataFolder().toPath().resolve("pending"));
        clearLinks();
        if (!identitiesProven()) {
            getLogger().warning("online-mode is off, so a name proves nothing. /signin will refuse to make links.");
        }
        // Brigadier: players without the permission aren't sent the command at all.
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(
                Commands.literal("signin")
                        .requires(source -> source.getSender().hasPermission(PERMISSION))
                        .executes(context -> {
                            signIn(context.getSource());
                            return Command.SINGLE_SUCCESS;
                        })
                        .build(),
                "Get a one-time link that signs your browser in."));
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

    /**
     * The link is for whoever typed the command. The sender, not the executor: {@code /execute as <player>} changes the
     * executor, and must never make a link for someone else.
     */
    private void signIn(CommandSourceStack source) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(Component.text("Only a player in game can ask for a sign-in link.", NamedTextColor.RED));
            return;
        }
        if (!identitiesProven()) {
            refuse(player, "Sign-in is off because this server doesn't check who players are.");
            return;
        }
        if (!player.isWhitelisted()) {
            refuse(player, "You aren't on this server's allowlist.");
            return;
        }
        reloadConfig();
        String page = getConfig().getString("sign-in-page", "");
        if (!SignInLinks.usablePage(page)) {
            refuse(player, "Sign-in hasn't been set up on this server yet.");
            return;
        }
        String token;
        try {
            token = links.issue(player.getUniqueId(), player.getName(), Instant.now());
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Couldn't save a sign-in link", e);
            refuse(player, "That didn't work. Tell whoever runs this server.");
            return;
        }
        if (token == null) {
            refuse(player, "You just asked for one. Try again in a few seconds.");
            return;
        }
        String link = SignInLinks.link(page.trim(), token);
        player.sendMessage(Component.text("Click here to sign in", NamedTextColor.GREEN, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(link))
                .hoverEvent(HoverEvent.showText(Component.text("Works once, for two minutes. Don't share it."))));
        player.sendMessage(Component.text("It works once, for two minutes. Don't share it.", NamedTextColor.GRAY));
    }

    private static void refuse(Player player, String why) {
        player.sendMessage(Component.text(why, NamedTextColor.RED));
    }
}

package com.extantape.latchkey.api;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A player's first time on this server, by the server's own record. Fired on the server thread when they join, before
 * {@link PlayerArrivedEvent}, whether or not the lobby is on. Latchkey hands out nothing; that's other plugins' job.
 *
 * <p>Contract by method name (crossing latchkey-waystation): {@code getPlayer()}.
 */
public final class FirstJoinEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    public FirstJoinEvent(Player who) { super(who); }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

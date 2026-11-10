package com.extantape.latchkey.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A player has joined and is where they'll start: in the lobby if Latchkey sent them there, otherwise wherever the
 * server put them. Fired on the server thread on every join, after any teleport has finished.
 *
 * <p>Contract by method name (crossing latchkey-waystation): {@code getPlayer()}, {@code isFirstJoin()},
 * {@code getLobby()} (null when no lobby is set up) and {@code isSentToLobby()}.
 */
public final class PlayerArrivedEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final boolean firstJoin, sentToLobby;
    private final Location lobby;

    public PlayerArrivedEvent(Player who, boolean firstJoin, @Nullable Location lobby, boolean sentToLobby) {
        super(who);
        this.firstJoin = firstJoin;
        this.lobby = lobby == null ? null : lobby.clone();
        this.sentToLobby = sentToLobby;
    }

    public boolean isFirstJoin() { return firstJoin; }
    public @Nullable Location getLobby() { return lobby == null ? null : lobby.clone(); }
    public boolean isSentToLobby() { return sentToLobby; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

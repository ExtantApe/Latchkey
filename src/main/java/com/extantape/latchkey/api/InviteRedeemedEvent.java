package com.extantape.latchkey.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Someone used an invite on the landing page, and the player is now whitelisted. Fired on the server thread. Usher
 * listens for this to apply the invite's profiles; any plugin may. An empty list of profiles means lobby-only.
 *
 * <p>Contract by method name (crossing latchkey-usher): {@code getUuid()}, {@code getName()}, {@code getProfiles()},
 * {@code getConfirmed()}.
 */
public final class InviteRedeemedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID uuid;
    private final String name;
    private final List<String> profiles;
    private final Set<String> confirmed;

    public InviteRedeemedEvent(UUID uuid, String name, List<String> profiles, Set<String> confirmed) {
        this.uuid = uuid;
        this.name = name;
        this.profiles = List.copyOf(profiles);
        this.confirmed = Set.copyOf(confirmed);
    }

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }
    public List<String> getProfiles() { return profiles; }
    /** The questions the admin answered yes to when making the invite (see {@link InviteCheckEvent}). */
    public Set<String> getConfirmed() { return confirmed; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

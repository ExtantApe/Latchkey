package com.extantape.latchkey.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An admin is making an invite that names profiles. Fired on the server thread before anything is saved, so a plugin
 * that owns the profiles (Usher) can refuse a bad combination now rather than when the player arrives, or ask questions
 * that need a yes. The first refusal wins. An invite whose questions aren't all answered isn't made.
 *
 * <p>The contract other plugins rely on, by method name (crossing latchkey-usher): {@code getUuid()}, {@code getName()},
 * {@code getProfiles()}, {@code getConfirmed()}, {@code refuse(String code, String message)},
 * {@code need(String id, String question)} and {@code checkedBy(String plugin)}. Question ids are {@code a-z 0-9 -}.
 */
public final class InviteCheckEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID uuid;
    private final String name;
    private final List<String> profiles;
    private final Set<String> confirmed;
    private String refusalCode, refusalMessage;
    private final Map<String, String> needs = new LinkedHashMap<>();
    private final List<String> checkedBy = new ArrayList<>();

    public InviteCheckEvent(UUID uuid, String name, List<String> profiles, Set<String> confirmed) {
        this.uuid = uuid;
        this.name = name;
        this.profiles = List.copyOf(profiles);
        this.confirmed = Set.copyOf(confirmed);
    }

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }
    public List<String> getProfiles() { return profiles; }
    /** Question ids the admin has already said yes to. */
    public Set<String> getConfirmed() { return confirmed; }

    public void refuse(String code, String message) {
        if (refusalCode == null) { refusalCode = code; refusalMessage = message; }
    }

    public void need(String id, String question) {
        if (!id.matches("[a-z0-9-]{1,64}")) throw new IllegalArgumentException("Question ids are 1 to 64 of a-z, 0-9 and -: " + id);
        needs.putIfAbsent(id, question);
    }

    public void checkedBy(String plugin) { checkedBy.add(plugin); }

    public String getRefusalCode() { return refusalCode; }
    public String getRefusalMessage() { return refusalMessage; }
    /** Every question raised, answered or not. */
    public Map<String, String> getAsked() { return new LinkedHashMap<>(needs); }

    /** Questions not yet answered yes. */
    public Map<String, String> getUnanswered() {
        Map<String, String> out = new LinkedHashMap<>(needs);
        out.keySet().removeAll(confirmed);
        return out;
    }
    public List<String> getCheckedBy() { return List.copyOf(checkedBy); }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

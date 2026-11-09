package com.extantape.latchkey.invite;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One invite: for one player, by uuid and name, single use. Holds only the code's hash, never the code. Profiles are
 * Usher profile ids; an invite with none gives lobby-only access until an admin adds more (owner, 2026-11-09).
 * Confirmed holds the ids of questions the admin answered yes to when making it, passed on at redemption.
 */
public record Invite(String id, UUID uuid, String name, String codeHash, long created, long expires,
                     List<String> profiles, Set<String> confirmed, Status status, long changed) {
    public enum Status { PENDING, REDEEMED, REVOKED }

    public boolean usable(long now) { return status == Status.PENDING && now < expires; }

    Invite with(Status s, long at) { return new Invite(id, uuid, name, codeHash, created, expires, profiles, confirmed, s, at); }
}

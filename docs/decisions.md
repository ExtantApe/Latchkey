# Decisions

The owner's decisions for Latchkey, with the date each was made.

## Join page, switches and tickets (2026-11-14, owner, D187)

The owner, in Helm's chat, relayed by Helm: "Let's give it an extra switch. Also, URL page it servers should be customizable -- i'd rather /login... actually... I think we should change from using the plain url to using join.[the server's domain] -- cause then someone can type the code it, or they can scan the QR code that LeafJuice helps Latchkey generate with the invite ticket. It should have the server name on the ticket, the URL, etc. map should still work the same with the mapkeeper stuff, etc."

This replaces the "Address" decision below: the page moves from the bare domain to a join subdomain, and the bare domain forwards there (D188). Built in 0.5.0:

- **Three switches,** each off unless set: `landing.enabled`, `invites.enabled` and `sessions.enabled`.
- **Where the page lives:** `landing.public-url` takes a host, or a path of up to three lowercase parts.
- **Invite links** put the code after `#`, and the page fills it in.
- **Tickets:** `invite create` gives tools a ticket with the server name, the join address, the link format and the expiry. The code stays in the once file.
- **The map** is unchanged and still goes through Mapkeeper.

## Arrival flow (2026-11-09, owner)

The plan is in the ops repo (`briefs/minecraft-arrival-flow-plan.md`). The owner's answers:

- **HTTPS, revised (owner, 2026-11-10, in Plugins Lead's chat):** playit's HTTPS tunnel doesn't decrypt the connection ("Playit does not terminate SSL", its own guide), so something on this machine must hold the certificate. The owner chose "Add Caddy in front": Caddy gets and renews the Let's Encrypt certificate and passes requests to Latchkey, which stays plain HTTP on 127.0.0.1. This replaces "not a local Caddy" below. The safe-setup rule is unchanged: no HTTPS, no page.
- **Invite page choices (owner, 2026-11-10 ~12:35 PM MST, relayed verbatim by Helm):** (1) "No (Recommended)" to Microsoft sign-in: the code is tied to the looked-up username and online mode proves the account on first join; we never touch anyone's Microsoft login. (2) "Code only (Recommended)": one box; after a valid code the page shows "This invite is for <name>" before activating. (3) "That browser + /signin (Recommended)": the activating browser sees the play address while its session lasts (1 day by default); other devices use a one-time /signin link. (4) "The resource pack's logo (Recommended)": the server's own logo, already in its resource pack. Tests must prove the play address never appears in any unauthenticated answer.
- **The map's sign-in (owner, 2026-11-10, D43):** the web map lives at its own address, `map.<domain>`, not a path here, and gets its own host-only cookie through a one-time hand-off from this page. There is never a cookie for the whole domain. Built as `page.map-address`, the "Open the map" button and single-use 60-second tokens (#9). A hand-off also needs the player still whitelisted and not banned, both when it's redeemed and at every recheck (#12).
- **Address (owner, 2026-11-10):** the page lives at the server's bare domain, not a subdomain. playit's guides only show subdomains handed to playit, so the N49 test also checks that the bare domain works through playit. If it doesn't, the owner is asked again.
- **HTTPS for the landing page:** through playit's HTTPS/web tunnel type, not a local Caddy. It needs playit Premium and hasn't been tested. Before anything is built on it, a throwaway test checks that the account offers that tunnel type and what client IP and headers arrive through it. If it isn't available, work stops and the owner is asked; there's no switch to Caddy without him. Whatever the route, the safe-setup rule holds: no HTTPS, no landing page, and no override.
- **Invites:** redeeming an invite whitelists its username at once. Usher, if installed, applies the invite's profile.
- **Sessions (changed by the owner later on 2026-11-09):** Latchkey owns player browser sessions, not Leaf Juice, so servers without Leaf Juice get them too and less lands in the server wrapper. Latchkey issues them, stores only their hashes, ends them when their length runs out (see below), and revokes them; removing someone from the whitelist ends their sessions at once. Other plugins check a session through a Bukkit service, and Leaf Juice through the localhost admin API. Leaf Juice keeps only the owner's own admin login for the app. Cookies are HttpOnly, Secure and SameSite=Strict, get a new id on every sign-in, and never appear in a URL. No HTTPS, no sessions. (The first answer that day, sessions staying in Leaf Juice, is replaced.)
- **Session length (changed by the owner later on 2026-11-09):** a setting with a 1-day default. Admins may lengthen it up to 30 days, the ceiling; a config above 30 days is refused at startup and reload, with no override. This replaces "re-sign every 30 days".

## Commands are the API, and invites carry profiles (2026-11-09, owner)

From the invite-hookup plan (ops repo, `briefs/minecraft-invite-hookup.md`). This replaces the localhost admin API planned earlier the same day.

- **No admin API.** Latchkey opens no admin port and keeps no admin token. Leaf Juice, scripts and people use the same commands. Each command has a machine-readable form, a one-line JSON answer with a request id, and `/latchkey describe` lists what Latchkey offers. The format comes from a small shared library shaded into each plugin. The public landing page stays.
- **No secret on the console.** Paper logs console output, so a command that makes a secret writes it to `plugins/Latchkey/once/<request-id>.json` (0600). The answer only points to that file, and the file is deleted after 5 minutes if nobody reads it. In game, the code goes privately to the admin who asked, in chat, and is never logged. A test checks that the server log never contains a code.
- **Invites carry profiles.** An invite is the UUID, name, code hash, expiry and a list of Usher profiles. Redeeming it whitelists the player and fires `InviteRedeemedEvent(uuid, name, profiles)`. Usher checks the profiles when the invite is created and applies them on redemption. Without Usher the profiles are ignored and a log line says so. An invite with no profiles gives lobby-only access until an admin adds more.
- **Without Leaf Juice:** everything works through commands, alone or with any other plugins.
- **Session checks for other programs (decided later on 2026-11-09):** a file per session. Latchkey writes `plugins/Latchkey/sessions/<sha256 of the cookie>.json` with the UUID, name and expiry, readable only by the server's user (folder 0700, files 0600). It deletes the file on revoke, expiry or whitelist removal before the response that caused it returns. Leaf Juice hashes the cookie and reads the file; commands do everything else. Tests cover both the deletion timing and the file permissions.
- **Leaf Juice's Players page** is hand-built and filled from `describe`. Automatic panels are for plugin settings.

## Sessions stay open while you play (2026-11-09, owner)

Sent through Helm. If a player is actively playing when their browser session is about to end, the session is extended by one day. A session can be extended at most 7 days in a row; after that the player has to sign in again.

- **Active** means the player is online and, in the last 5 minutes, moved to a different block or interacted with the world (broke or placed a block, used an item, hit or used an entity, opened a container). Turning on the spot or standing still doesn't count. Being pushed by water or riding something moves the player too, so an AFK pool can look active; nothing stops that, and the 7-day limit is the backstop. Activity is kept in memory only, never written to disk or shown anywhere.
- **When:** in the last 10 minutes before a session ends, the regular 30-second check extends it by one day if the player is active then.
- **Limits:** at most 7 one-day extensions after sign-in, and never more than 30 days from sign-in, the existing ceiling. Signing in again starts the count afresh.
- **Unchanged:** taking a player off the whitelist, a ban, or the whitelist going off still ends their sessions at once.
- **Files:** extensions rewrite the same session file. The file gains one field, `since` (sign-in time, Unix seconds), so the limits can be worked out from the file alone. Leaf Juice reads `uuid`, `name` and `expires` as before and must ignore the new field; it's told through the `latchkey-leafjuice` crossing.
- **Leaf Juice agreed (2026-11-09, ledger c67348cc5812):** `since` is required from 0.2.0 on. Leaf Juice also refuses a session whose file lacks `since`, has `expires <= since`, or claims more than 30 days, and leaves deleting to Latchkey.

## Sign-in stays admin-granted (2026-11-09, owner)

`latchkey.signin` stays op-only by default, as in 0.1.0. A server that wants players to sign in grants it, for example with `lp group default permission set latchkey.signin true`. Nothing is offered to players that the server hasn't chosen to offer.

## Lobby defaults (decided by the owner 2026-11-10)

Proposed 2026-11-09 and built this way on the 0.3.0 branch. The owner approved them on 2026-11-10 (N56, "N56 lobby defaults OK", relayed verbatim by Helm), and 0.3.0 is released with them.

- Players are sent to the lobby on their first join only. `always` and `never` are also settings.
- The lobby is a world named in config, plus an optional spot in it; otherwise the world's own spawn.
- First-timers get a title and chat lines, written in config. Latchkey hands nothing out and keeps no new player data: who is new comes from the server's own record.
- Events for other plugins: `FirstJoinEvent`, then `PlayerArrivedEvent` on every join (crossing latchkey-waystation, ledger 558c1f5383ae).

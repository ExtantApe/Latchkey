# Decisions

The owner's decisions for Latchkey, with the date each was made.

## Arrival flow (2026-11-09, owner)

The plan is in the ops repo (`notes/minecraft-arrival-flow-plan.md`). The owner's answers:

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

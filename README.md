# Latchkey

Invites, a landing page and browser sign-in for whitelisted Paper servers. A player gets a six-character code, types it on your page with their username, and they're on the whitelist.

**Status:** 0.3.0. 193 unit and HTTP tests pass, and a bot test with Usher passes 44/44 on throwaway Paper 26.1.2 and 26.3 servers. Not yet tried: HTTPS through a real tunnel, a real browser, a real game client, or a real server. On 26.3 the bots can't walk, so "playing" was shown there by interaction only, and the lobby title can't be seen by a bot.

## What it does

**Invites.** An admin makes an invite for one username. The code is shown once, to that admin only, and works once.

- Codes are 6 characters from 25 that can't be mixed up (no 0/O, 1/I/L, 5/S, 2/Z, 8/B). Typed in any case, with dashes or spaces.
- An invite lasts 14 days by default, 30 at most. It can be revoked until it's used.
- Only a salted hash of each code is stored, in `plugins/Latchkey/invites.tsv`, which you can read in any text editor.
- A wrong, used, expired, revoked or someone else's code all get the same answer, after the same 400 ms.
- An invite can name [Usher](https://github.com/ExtantApe/Usher) profiles. Usher checks them when the invite is made, asks anything that needs a yes, and applies them when it's used. Without Usher, or with no profiles, the player gets lobby-only access.

**The landing page.** A small web page built on Java's own HTTP server, so nothing extra ships.

- One form, which works with a keyboard and a screen reader, without JavaScript, and in light or dark mode.
- It listens on 127.0.0.1 by default. Your tunnel or proxy provides the HTTPS.
- Form tokens, refusal of posts from other sites, rate limits (per visitor and site-wide), a fixed Content-Security-Policy and security headers on every answer. No stack traces, and nothing personal in the log.
- It can pass paths such as `/map` through to services on the same machine, so a signed-in browser reaches them on the same address. Only loopback targets are allowed.

**Browser sign-in.** A player types `/signin` in game and clicks the link. The link works once, for two minutes. Its secret comes after a `#`, so it never reaches a web log.

- A session lasts 1 day by default, 30 at most. Signing in again gives a new cookie (HttpOnly, Secure, SameSite=Strict).
- While the player is actually playing, the session is kept open a day at a time, at most 7 days in a row. Playing means moving to a different block or interacting with the world in the last 5 minutes; turning on the spot doesn't count.
- Taking a player off the whitelist ends their sessions before the command finishes. Turning the whitelist off ends everyone's. Bans are caught within 30 seconds.
- Each session is a file, `plugins/Latchkey/sessions/<sha256 of the cookie>.json`, holding the uuid, name, expiry and sign-in time. Other programs on the machine, like Leaf Juice, check a session by reading that file. The cookie itself is never stored.

**The lobby.** Where arriving players start, with or without Multiverse or Waystation. It's off until you name a world.

- `lobby.world` is any loaded world, and `lobby.spawn` an optional place in it (x, y, z, yaw, pitch); without it, it's the world's spawn.
- `lobby.send` says who goes there on joining: `first-join` (the default), `always` or `never`. A player coming back with `first-join` starts where they left.
- First-timers get a title and chat lines, written in `lobby.welcome`. Who is new comes from the server's own record of who has played, so Latchkey stores nothing more about players.
- If the world isn't loaded, nobody is sent anywhere and the console says once how to fix it. `/latchkey status` shows it too. A lobby problem never stops the landing page.

For other plugins (no dependency needed; read them by name): `FirstJoinEvent` on a first join, `PlayerArrivedEvent` on every join once the player is where they'll start (`isFirstJoin()`, `getLobby()`, `isSentToLobby()`), and `lobbyLocation()` on the plugin. Waystation uses them for its lobby gate.

**Safe setup or no setup.** Latchkey checks its settings at startup and on reload. If something isn't safe, the part it affects stays off and the console says what to change. The checks:

- the page must be served over HTTPS;
- it must listen on a loopback or private address;
- no wildcard or wide trusted proxies;
- rate limits can't be turned off or loosened past 20 tries per visitor per 10 minutes, or 60 site-wide per minute;
- invites last at most 30 days, and sessions at most 30 days;
- forwarding only goes to loopback, and never over Latchkey's own paths;
- the whitelist must be on, and online mode must be on;
- a setting Latchkey doesn't know stops the page, so a typo can't leave something open.

There's no switch that skips a check.

## Install

Download the jar from [Releases](https://github.com/ExtantApe/Latchkey/releases), or build it. It needs Paper 26.1 or later and Java 25. Put it in `plugins/` and start the server once. Then:

1. Point your tunnel or proxy's HTTPS address at `127.0.0.1:25680`.
2. In `plugins/Latchkey/config.yml`, set `landing.public-url` to that address and `landing.enabled` to `true`.
3. Run `/latchkey reload`, then `/latchkey status` to check every part is on.
4. To let players use `/signin`, grant `latchkey.signin`. It's op-only by default: `lp group default permission set latchkey.signin true`.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/signin` | `latchkey.signin` | Sends you a one-time sign-in link. |
| `/latchkey invite create <player> [profiles\|none] [lifetime]` | `latchkey.invite.create` | Makes an invite, such as `invite create Alex builders 7d`. |
| `/latchkey invite list [limit] [cursor]` | `latchkey.invite.list` | Lists invites, newest first. Never shows codes. |
| `/latchkey invite revoke <id>` | `latchkey.invite.revoke` | Cancels an unused invite. |
| `/latchkey session list <player>` | `latchkey.session.manage` | How many browsers a player is signed in on. |
| `/latchkey session revoke <player>` | `latchkey.session.manage` | Signs a player out everywhere. |
| `/latchkey status` | `latchkey.status` | What's on, and why anything is off. |
| `/latchkey reload` | `latchkey.reload` | Rereads the config and reruns the checks. |
| `/latchkey describe` | `latchkey.status` | Lists the commands. |

All permissions are op-only by default. `/lk` works too.

In game, a new code goes privately to the admin who made it. On the console, which is logged, the code goes to `plugins/Latchkey/once/` for 5 minutes and the console only says where.

Every `/latchkey` command also answers tools such as Leaf Juice. Add `--json <id>` at the end and the answer is one line, `ea-result <id> {…}`, in the [Signalbox](https://github.com/ExtantApe/Signalbox) format. There's no admin port or token: the commands are the API.

## Build

```bash
./gradlew build
```

The jar lands in `build/libs/`. [docs/paper-26.md](docs/paper-26.md) says what changed for Paper 26, and [docs/decisions.md](docs/decisions.md) holds the owner's decisions with their dates.

## Testing

`./gradlew test` runs the unit and HTTP tests. `test/acceptance.mjs` runs the bot test on a throwaway server; the top of that file says what it needs. Bots can't join an online-mode server, so the launch flag `-Dlatchkey.allowOfflineForTesting=true` lets Latchkey work on an offline-mode one. Use it only on a throwaway copy.

## License

MIT. See `LICENSE`.

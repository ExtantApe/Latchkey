# Moving to Paper 26.1

Latchkey now targets Paper 26.1 and later, built with Java 25 against `paper-api` 26.1.2. Paper 1.21.11 isn't supported any more.

## What changed

- **`paper-plugin.yml` instead of `plugin.yml`.** Plugins here are Paper only. The permission moved across unchanged. Latchkey has no dependencies or load order to declare, and doesn't use a bootstrapper, which Paper still marks experimental.
- **`/signin` is a Brigadier command**, registered through `LifecycleEvents.COMMANDS` in `onEnable`. Paper doesn't mark that API experimental. Its `requires` check means a player without `latchkey.signin` isn't sent the command at all.
- **The link goes to whoever typed the command.** It's the command's sender, not its executor, so `/execute as <player> run signin` can't make a link for someone else. The console and command blocks are refused, as before.
- **Java 25**, which Paper says 26.1 needs. CI builds with it too.

## What was checked, and what wasn't

Leaf Juice's `--real-signin` test ran this jar on throwaway Paper 26.1.2, 26.2 and 26.3 servers, all on this Mac, with bots:

- the plugin loads and Leaf Juice sees it;
- no permission, no link; off the allowlist, no link;
- a link arrives, only its hash is saved, it isn't in the console or log, it signs in once, and a second use fails;
- `execute as` from the console or from an op makes no link for another player, and that player can't ask for one;
- taking someone off the allowlist ends their session, and no links are left after a stop.

Not checked: a real game client clicking the link, a real server, LuckPerms granting the permission (the tests use op), and whether a client sees `/signin` appear without rejoining after the permission is given.

One thing seen along the way: with no `white-list` line in `server.properties`, Paper 26.3 refused a player who wasn't on the allowlist. Leaf Juice's tests now set it explicitly.

## Not used

Adventure was already in use for the clickable link. MiniMessage and dialogs weren't needed for one line of chat.

## Owner decisions

Settled by the owner. Follow them without asking again.

- **2026-11-07:** Latchkey is public at `ExtantApe/Latchkey`, MIT, "Copyright (c) 2026 EXTANT APE".
- **2026-11-07:** It targets Paper 26.1 and later only, with `paper-api` and `paper-plugin.yml`. No Spigot, and no 1.21.11.
- **2026-11-07:** Release as soon as it's good, as 0.x minor versions on GitHub Releases only. Modrinth, Hangar and Spigot wait for the owner's own code review. A `v*` tag builds the jar and attaches it, with notes from `docs/releases/<tag>.md`.
- **Standing:** children play on these servers, so their safety and privacy come first. Nothing Latchkey writes or shows exposes player data beyond the one player's own link.

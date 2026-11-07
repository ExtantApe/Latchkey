# Latchkey

Signs a player's browser in to a web page with a one-time link, sent to them privately in game. For Paper servers.

**Status:** early, and private. Its tests cover how links are made and stored, and it has run on a throwaway Paper 1.21.11 server with a bot. No real game client has clicked a link, and it hasn't run on a real server.

## What it does

A player types `/signin` and gets a link they can click. Being on the server proves who they are, so there's no password.

- A link works once and lasts two minutes. Asking again cancels the last one.
- Only a hash of the secret is written to disk, in `plugins/Latchkey/pending/`. The secret comes after a `#` in the link, so a browser never sends it in a web request and it can't land in a web log.
- `/signin` needs the `latchkey.signin` permission, which ops have by default, and the player must be on the allowlist.
- It refuses to make links when `online-mode` is off, because then a name proves nothing. A server behind a proxy has `online-mode` off, so that setup isn't supported yet.
- Links left waiting are cleared when the server starts and stops.

It doesn't serve any web page itself. [Leaf Juice](https://github.com/mgilardi/leaf-juice) reads the files in `pending/`, deletes each one when it's used, and runs the page and sessions.

Bedrock players coming through Geyser haven't been tried, and may not be able to click a chat link.

## Setup

Put the jar in `plugins/`, start the server once, then set the page's address in `plugins/Latchkey/config.yml`. It has to start with `https://`.

```yaml
sign-in-page: "https://example.com"
```

## Build

```bash
./gradlew build
```

The jar lands in `build/libs/`. It needs Java 21 and targets Paper 1.21.11.

## Testing

Bots can't join an online-mode server, so the launch flag `-Dlatchkey.allowOfflineForTesting=true` lets the plugin make links on an offline-mode server. Use it only on a throwaway copy.

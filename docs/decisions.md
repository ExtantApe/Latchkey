# Decisions

The owner's decisions for Latchkey, with the date each was made.

## Arrival flow (2026-11-09, owner)

The plan is in the ops repo (`notes/minecraft-arrival-flow-plan.md`). The owner's answers:

- **HTTPS for the landing page:** through playit's HTTPS/web tunnel type, not a local Caddy. It needs playit Premium and hasn't been tested. Before anything is built on it, a throwaway test checks that the account offers that tunnel type and what client IP and headers arrive through it. If it isn't available, work stops and the owner is asked; there's no switch to Caddy without him. Whatever the route, the safe-setup rule holds: no HTTPS, no landing page, and no override.
- **Invites:** redeeming an invite whitelists its username at once. Usher, if installed, applies the invite's profile.
- **Sessions (changed by the owner later on 2026-11-09):** Latchkey owns player browser sessions, not Leaf Juice, so servers without Leaf Juice get them too and less lands in the server wrapper. Latchkey issues them, stores only their hashes, ends them after 30 days, and revokes them; removing someone from the whitelist ends their sessions at once. Other plugins check a session through a Bukkit service, and Leaf Juice through the localhost admin API. Leaf Juice keeps only the owner's own admin login for the app. Cookies are HttpOnly, Secure and SameSite=Strict, get a new id on every sign-in, and never appear in a URL. No HTTPS, no sessions. (The first answer that day, sessions staying in Leaf Juice, is replaced.)
- **Re-sign:** players sign in again in the browser every 30 days.

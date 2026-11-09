# Decisions

The owner's decisions for Latchkey, with the date each was made.

## Arrival flow (2026-11-09, owner)

The plan is in the ops repo (`notes/minecraft-arrival-flow-plan.md`). The owner's answers:

- **HTTPS for the landing page:** through playit's HTTPS/web tunnel type, not a local Caddy. It needs playit Premium and hasn't been tested. Before anything is built on it, a throwaway test checks that the account offers that tunnel type and what client IP and headers arrive through it. If it isn't available, work stops and the owner is asked; there's no switch to Caddy without him. Whatever the route, the safe-setup rule holds: no HTTPS, no landing page, and no override.
- **Invites:** redeeming an invite whitelists its username at once. Usher, if installed, applies the invite's profile.
- **Sessions:** browser sessions stay in Leaf Juice. Latchkey only proves who someone is and reports whitelist removals.
- **Re-sign:** players sign in again in the browser every 30 days.

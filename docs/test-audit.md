# Test audit

## 2026-11-14, for 0.5.0 (switches, a path, links and tickets)

| Promise | Covered by |
| --- | --- |
| Invites and sign-in each have a switch, off unless set, apart from the page's | `invitesHaveTheirOwnSwitchOffUnlessSet`, `signInHasItsOwnSwitchOffUnlessSet`, `theShippedConfigStartsWithTheWebPartsOff` |
| A switch can't open a part another check stops (fails closed) | `aSwitchTurnedOnCantOpenAPartAnotherCheckStops`, `aSwitchMustBeTrueNotJustPresent`, `theSignInSwitchAlsoStopsLinksToAnotherPage` |
| The page offers only what's on | `withInvitesOffThePageHasNoCodeBoxAndNoScript`, `withSignInOffThePageDoesntOfferIt`, `withSignInOffAUsedInviteStillAdmitsButSignsNoBrowserIn`, `invitesStayClosedWhenRedemptionIsStopped` |
| No invite is made while codes can't be used | `noInvitesAreMadeWhileTheyCantBeUsed` |
| A page under a path answers only there, and its cookies stay there | `underAPathOnlyThatPathIsServed`, `underAPathAnInviteWorksAndTheCookieStaysOnThatPath`, `thePageCanBeAtAHostOrUnderAPath`, bad paths in `theLandingPageNeedsHttps` |
| The code in a link is after #, filled in but not used | `theHomePageFillsTheCodeFromTheLinkWithAnAllowedScript`, `theTicketLinkPutsTheCodeAfterTheHash` |
| A ticket never carries the code, and there's none while codes can't be used | `jsonCreateCarriesTheTicketButNeverTheCode`, `noTicketWhileTheJoinPageCantTakeCodes`, `inGameTheAdminAlsoGetsTheLinkThatFillsTheCodeIn` |

**Gaps skipped:** the health check's address under a path is a one-line change with no test of its own; it reaches the network, and the path itself is covered above. The bot test wasn't rerun, since the disk was too full for a throwaway server. Its config now turns on all three switches.

## 2026-11-10, for 0.4.0 (the invite page)

| Promise | Covered by |
| --- | --- |
| The play address is never in an answer to a browser that isn't signed in | HTTP `thePlayAddressIsNeverInAnyAnswerToABrowserThatIsntSignedIn`: the code page, the confirm page, a wrong code, a wrong confirm, 404, 405s, `/signin`, sign-out and the logo, bodies and headers |
| Signed in by using the invite, the address is there, not cached, and gone after sign-out | same test |
| Looking at an invite uses nothing up; the yes needs a form token | `theConfirmStepUsesNothingUntilYes`; bots "first asks This invite is for Alex, using nothing up" (Alex still can't join) |
| One admission per code, even racing at the confirm step | `twoBrowsersRacingOneCodeGetOneAdmission` (8 browsers) |
| Every failed code looks and takes the same | `everyFailedInviteLooksAndTakesTheSame` (wrong, used, revoked, expired, too long) |
| The logo is a real PNG or WebP from the plugin folder only, small, served with nosniff | `PageSettingsTest` (7 tests), `theLogoIsServedAsAnImageWithTheSecurityHeaders` |
| The welcome's one script is allowed by hash, and the copy button only shows where it works | `theWelcomeShowsTheStepsAndItsScriptIsAllowedByHash` |

Bot test 45/45 on Paper 26.1.2. Its rate limit is raised to the ceiling (20), since every test browser shares 127.0.0.1 and each invite now takes two posts.

**Not covered yet:** the real client address behind playit and Caddy (PROXY protocol), which needs the N49 tunnel; Latchkey's side, trusting X-Forwarded-For only from listed proxies, is tested.

## 2026-11-10, for 0.3.0 (branch `latchkey-0.3`)

What 0.3.0 adds: the lobby module (`FirstJoinEvent`, `PlayerArrivedEvent`, `lobbyLocation()`), invite lists in pages, and optional arguments marked in `describe`. The 0.2.0 audit below still holds.

### Covered

- **The lobby is off until a world is set, and a problem there never stops the web parts:** unit `LobbySettingsTest` (6 tests), `lobbyProblemsNeverStopTheWebParts`.
- **New in this audit: a first-timer is sent to the lobby spot and welcomed; someone coming back starts where they left, with no welcome.** The lobby had no bot checks of its own; only Waystation's arrival suite used it, through `lobbyLocation()` and the arrival event. Four checks added to the bot test (`send: first-join`, a spot away from the world spawn, one chat line).
- **New in this audit: a profile that turns unsafe between the invite and its use isn't applied, and Usher says so in the log.** Fail-closed, so it needed a test.
- **Lists in pages:** `listPagesNewestFirstWithoutHashes`; the bot test now reads the page shape (it still read the 0.2.0 shape and failed on the branch).
- **`describe` marks optional arguments:** `describeMarksTheOptionalArguments`.
- **Other plugins can use the lobby and the events:** Waystation's `test/arrival.mjs`, with Latchkey 0.3.0, 29/29 on 26.3.

Bot test: 44/44 on Paper 26.1.2 and 44/44 on 26.3. On 26.3, two harness fixes were needed: the bot now answers the server's teleport (as a real client does), and before the arm-swing check Alex is moved back near spawn. At the lobby spot, Paper fired no interact event for these bots' swings, while the same swing near spawn counts; Latchkey's tracker doesn't look at position.

### Skipped, with reasons

- **`send: always` and `never`.** `whoIsSent` covers the rule itself. The join handler only asks it, so a bot check per mode would retest the same line.
- **A lobby world that isn't loaded, live.** `problem()` and the warning text are simple. The unit tests cover the setting side, and the status line shows it.
- **Arm swings at the lobby spot on 26.3.** See above: the bots' swings there never reach Latchkey as events, so the check runs near spawn. A real player walking counts through movement. This belongs on the real-client list.
- **The lobby title on screen.** A bot can't see titles; it's on the real-client list (T12 step 6).

## 2026-11-09, for 0.2.0

What Latchkey promises (its README and `docs/decisions.md`), and what proves each promise. Unit and HTTP tests are in `src/test`, 185 of them. The bot test is `test/acceptance.mjs`: 38 checks, passed 38/38 on throwaway Paper 26.1.2, 26.2 and 26.3 servers with LuckPerms and Usher.

### Covered

**Who gets in**
- **A code works once, for one username, until it expires or is revoked.**
  - Unit tests: `InviteStoreTest`.
  - A 16-thread race in which exactly one redemption wins.
  - An HTTP race of 8 browsers on one code.
  - On a real server: a used code fails, and a stranger can't join before redeeming.
- **Every kind of failed code looks and takes the same:** `everyFailedInviteLooksAndTakesTheSame` covers seven kinds and checks the 400 ms minimum. The bot test compares a wrong code with someone else's.
- **Codes are hashed with a salt, never stored, never logged.**
  - The store's own tests.
  - `jsonCreateKeepsTheCodeOutOfTheAnswerAndTheLog`, `onTheConsoleTheCodeGoesToAFileNotTheLog`.
  - The bot test searches the whole console output and `latest.log` for both codes.
- **Usher checks profiles at creation and applies them on use.**
  - Latchkey: `LatchkeyCommandsTest` covers a refusal, questions and `--yes`, and no Usher installed.
  - Usher: `LatchkeyHookTest`.
  - On a real server: a refusal, a question, then both profiles applied and logged as "Latchkey invite".
- **No invite is made while invites can't be used:** a unit test, and on a real server.

**Sessions**
- **A session ends when it should:**
  - on expiry, sign-out or a new sign-in (`SessionStoreTest`, `LandingServerTest`);
  - on whitelist removal, before the command finishes (unit test, and bot test on each version);
  - when the whitelist is turned off (bot test);
  - when the player is banned, within 30 seconds (bot test, added in this audit).
- **A stolen cookie stops working on sign-out:** `aStolenCookieStopsWorkingAfterSignOut`. A browser that sends two session cookies is trusted as neither.
- **The cookie is never on disk.** Files are owner-only, named by the cookie's hash, in plain JSON. Covered by unit tests, and the bot test checks a real file's name, fields and mode.
- **Unreadable files sign nobody in and are deleted.** This includes a file with no `since`, or one claiming more than 30 days, which Leaf Juice relies on (ledger c67348cc5812).
- **Playing keeps a session open:** `ExtensionTest` covers active, idle, the 8th day refused, the 30-day ceiling, and signing in again resetting the count. On a real server, an active bot is extended and an idle bot isn't.

**The page**
- **Every Latchkey answer carries the security headers**, including 404, 405, 429 and refusals. The CSP allows exactly the inline style and script, by hash.
- **Posts need a form token** and are refused from other sites, whether by `Origin` or `Sec-Fetch-Site`. Odd bodies are refused: wrong type, too big, a field twice, control characters.
- **Rate limits:** per visitor, site-wide, and forwarded addresses believed only from listed proxies (HTTP tests).
- **Forwarding** passes the request unchanged with no identity header, only for the exact prefix, and a down service gets a plain error.
- **Forms are labelled for screen readers**, and the page has a language set.

**Safe setup or no setup**
- `SafeSetupTest`, 89 cases: every check in the README refuses its unsafe values and accepts the safe ones, every problem has one line with a fix, and unknown settings stop the page.
- On a real server: an `http://` address keeps the page off with the fix logged, and fixing it plus `/latchkey reload` turns it on.

**Commands**
- **Every command checks its own permission** and changes nothing when refused (`everyCommandChecksItsOwnPermission`).
- **Every `--json` request gets exactly one answer,** including unknown commands and bad arguments. On a real server the answer comes straight after the log header, where chat can't reach.

### Gaps

- **Added in this audit:** bans ending sessions had no test. Added to the bot test, which passed on all three versions.
- **Skipped:**
  - **HTTPS through a real tunnel.** It can't be tested without the owner's playit set-up (owner step N49). Until then, the safe-setup check is what guarantees HTTPS.
  - **A real browser and a real client.** The HTTP tests stand in for the browser, and the one inline script is only checked by its hash. Clicking the in-game link, and the script filling in the form, belong on the real-client list.
  - **Walking on 26.2 and 26.3.** Bots can't walk through ViaBackwards, so "playing" there was shown by interaction only. Walking was shown on 26.1.2.
  - **`/signin` refusals for players without the permission.** Brigadier never sends them the command; that's Paper's code, and 0.1.0's tests covered the rest of `/signin`.
  - **The in-game code message from `invite create`.** It's unit-tested; the bot test makes invites from the console.

### Overkill, not tested

Getters on the event classes, HTML escaping beyond one test of every special character, and the JDK HTTP server and Brigadier themselves.

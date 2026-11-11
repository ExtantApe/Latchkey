// Latchkey acceptance test (0.3.0) on a throwaway Paper server, with LuckPerms and Usher, driven from the console,
// over HTTP and with mineflayer bots. It builds the server in a temp folder on 127.0.0.1, never copies a real one,
// and deletes it at the end.
//
//   LK_TEST_JARS    folder with paper-<version>.jar, LuckPerms-Bukkit-*.jar, and on 26.2+ ViaVersion-*.jar, ViaBackwards-*.jar
//   LK_TEST_USHER   the Usher jar to test with (built from its latchkey-hook branch)
//   LK_TEST_EULA    an eula.txt the owner already accepted. It's copied, never written.
//   LK_TEST_RUN     where the server is built. Must be under a temp folder.
//   LK_TEST_PAPER   26.1.2 (default), 26.2 or 26.3
//   LK_TEST_JAVA    a Java 25 to run Paper with
import { spawn } from 'node:child_process'
import { createHash, randomBytes } from 'node:crypto'
import { chmodSync, copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import mineflayer from 'mineflayer'

const must = n => { const v = process.env[n]; if (!v) throw new Error('Set ' + n + ' (see the top of test/acceptance.mjs)'); return v }
const PAPER = process.env.LK_TEST_PAPER ?? '26.1.2'
const VIA = !PAPER.startsWith('26.1')
const JAVA = process.env.LK_TEST_JAVA ?? 'java'
const JARS = must('LK_TEST_JARS'), USHER = must('LK_TEST_USHER'), EULA = must('LK_TEST_EULA')
const RUN = path.resolve(must('LK_TEST_RUN'))
if (![os.tmpdir(), '/tmp', '/private/tmp', '/var/folders'].map(p => path.resolve(p)).some(t => RUN.startsWith(t + path.sep)))
  throw new Error('LK_TEST_RUN must be inside a temp folder, not ' + RUN)
if (!/^eula=true$/m.test(readFileSync(EULA, 'utf8'))) throw new Error('That eula.txt isn\'t accepted. Accepting it is the owner\'s call.')
const ROOT = new URL('../', import.meta.url).pathname
const LIBS = path.join(ROOT, 'build/libs')
const LK = readdirSync(LIBS).filter(f => /^Latchkey-[\d.]+\.jar$/.test(f))
if (LK.length !== 1) throw new Error('Run ./gradlew clean build first: expected one Latchkey jar in build/libs')

const PORT = 25617, WEB = 25691, PUBLIC = 'https://play.example.test'
const sleep = ms => new Promise(r => setTimeout(r, ms))
const jar = re => { const f = readdirSync(JARS).find(n => re.test(n)); if (!f) throw new Error('No jar matching ' + re); return path.join(JARS, f) }
const strip = l => l.replace(/^\[[\d:]+ \w+\]: /, '').replace(/^\[[\d:]+\] \[[^\]]+\/\w+\]: /, '')

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`)
  return ok
}

// --- the server ---

const dir = path.join(RUN, 'latchkey-' + PAPER)
rmSync(dir, { recursive: true, force: true })
mkdirSync(path.join(dir, 'plugins/Latchkey'), { recursive: true })
mkdirSync(path.join(dir, 'plugins/LuckPerms'), { recursive: true })
copyFileSync(jar(new RegExp('^paper-' + PAPER.replace(/\./g, '\\.') + '\\.jar$')), path.join(dir, 'paper.jar'))
copyFileSync(EULA, path.join(dir, 'eula.txt'))
for (const re of [/^LuckPerms-Bukkit-.*\.jar$/, ...(VIA ? [/^ViaVersion-.*\.jar$/, /^ViaBackwards-.*\.jar$/] : [])]) {
  const j = jar(re); copyFileSync(j, path.join(dir, 'plugins', path.basename(j)))
}
copyFileSync(USHER, path.join(dir, 'plugins/Usher.jar'))
copyFileSync(path.join(LIBS, LK[0]), path.join(dir, 'plugins/Latchkey.jar'))
// Usher's shipped config, set for a test server the way Usher's own tests do it. Read from the jar it ships in.
{
  const { execFileSync } = await import('node:child_process')
  let u = execFileSync('unzip', ['-p', USHER, 'config.yml'], { encoding: 'utf8' })
  for (const [a, b] of [['name-lookup: auto', 'name-lookup: offline'], ['lobby-world: ""', 'lobby-world: world']]) {
    if (!u.includes(a)) throw new Error('Usher\'s default config no longer has: ' + a)
    u = u.replace(a, b)
  }
  mkdirSync(path.join(dir, 'plugins/Usher'), { recursive: true })
  writeFileSync(path.join(dir, 'plugins/Usher/config.yml'), u)
}
writeFileSync(path.join(dir, 'plugins/LuckPerms/config.yml'), 'server: global\nstorage-method: yaml\nwatch-files: false\n')
writeFileSync(path.join(dir, 'server.properties'), [
  'server-ip=127.0.0.1', `server-port=${PORT}`, 'online-mode=false', 'white-list=true', 'enforce-whitelist=true',
  'level-type=minecraft\\:flat', 'generate-structures=false', 'spawn-protection=0', 'enable-query=false', 'enable-rcon=false',
  'view-distance=4', 'simulation-distance=4', 'difficulty=peaceful', 'spawn-monsters=false', 'motd=Latchkey test'].join('\n') + '\n')

/** Latchkey's shipped config with the test's settings. An unsafe public address, when asked for. */
function latchkeyConfig({ publicUrl = PUBLIC } = {}) {
  let t = readFileSync(path.join(ROOT, 'src/main/resources/config.yml'), 'utf8')
  for (const [a, b] of [['enabled: false', 'enabled: true'], ['port: 25680', 'port: ' + WEB], ['public-url: ""', 'public-url: "' + publicUrl + '"'],
    // Every test browser comes from 127.0.0.1, and each invite now takes two posts, so allow the most the ceiling lets.
    ['per-ip-per-10-minutes: 10', 'per-ip-per-10-minutes: 20'],
    // The lobby (0.3.0): the main world at a spot away from its spawn, with one chat line for first-timers.
    ['  world: ""', '  world: world\n  spawn: {x: 40.5, y: -60, z: 40.5, yaw: 0, pitch: 0}'], ['  chat: []', '  chat: ["First time here: welcome."]']]) {
    if (!t.includes(a)) throw new Error('The default config no longer has: ' + a)
    t = t.replace(a, b)
  }
  writeFileSync(path.join(dir, 'plugins/Latchkey/config.yml'), t)
}

const log = []
let proc, exited
async function start() {
  proc = spawn(JAVA, ['-Xms1536M', '-Xmx1536M', '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Dlatchkey.allowOfflineForTesting=true',
    '-jar', 'paper.jar', 'nogui'], { cwd: dir, stdio: ['pipe', 'pipe', 'pipe'] })
  exited = new Promise(r => proc.on('exit', r))
  process.on('exit', () => { if (proc.exitCode === null) proc.kill('SIGKILL') })
  const add = d => { for (const l of d.toString().split('\n')) if (l.trim()) log.push(l) }
  proc.stdout.on('data', add); proc.stderr.on('data', add)
  await waitFor(/Done \(\d/, 240000)
  await sleep(1500)
}
async function waitFor(re, ms = 15000, from = 0) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    const hit = log.slice(from).find(l => re.test(l))
    if (hit) return hit
    if (proc.exitCode !== null) throw new Error('Server exited while waiting for ' + re + ':\n  ' + log.slice(-5).join('\n  '))
    await sleep(50)
  }
  throw new Error('Timed out waiting for ' + re)
}
const send = c => proc.stdin.write(c + '\n')
async function ask(cmd, ms = 600) { const from = log.length; send(cmd); await sleep(ms); return log.slice(from).map(strip) }
let req = 0
/** A Latchkey command with --json, returning the parsed answer and the raw line. */
async function lk(args, yes) {
  const id = 'r' + (++req) + '-' + randomBytes(3).toString('hex')
  const from = log.length
  send('latchkey ' + args + (yes ? ' --yes ' + yes : '') + ' --json ' + id)
  const line = await waitFor(new RegExp('\\]: ea-result ' + id + ' '), 30000, from)
  return { id, line, ...JSON.parse(line.slice(line.indexOf('{'))) }
}
async function stop() {
  send('stop')
  const done = await Promise.race([exited.then(() => true), sleep(90000).then(() => false)])
  if (!done) { proc.kill('SIGKILL'); await exited }
}

// --- bots and the web ---

async function bot(username) {
  const b = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username, version: '26.1', auth: 'offline', viewDistance: 'tiny',
    hideErrors: true, physicsEnabled: false })
  if (VIA) {
    // Through ViaBackwards a 26.1 bot's movement reaches a 26.2+ server as invalid and gets it kicked, so it never moves.
    // The one exception: the reply to a teleport from the server, at exactly the spot it sent, which a real client also
    // sends. Without it the server keeps waiting for the client after the lobby teleport and ignores what the bot does.
    const write = b._client.write.bind(b._client)
    let sent = null
    b._client.prependListener('position', d => { sent = d }) // before mineflayer's own handler, which replies at once
    b._client.write = (name, data) => {
      if (!/^(position|position_look|look|flying)$/.test(name)) return write(name, data)
      if (sent && name === 'position_look' && data.x === sent.x && data.y === sent.y && data.z === sent.z) { sent = null; return write(name, data) }
    }
  }
  b.raw = []
  b.on('message', m => b.raw.push(JSON.stringify(m.json)))
  b.on('error', () => {})
  await new Promise((resolve, reject) => {
    b.once('spawn', resolve)
    b.once('kicked', r => reject(new Error('kicked: ' + (typeof r === 'string' ? r : JSON.stringify(r)))))
    b.once('end', r => reject(new Error(username + ' disconnected before spawning: ' + r)))
    setTimeout(() => reject(new Error(username + ' spawn timeout')), 60000)
  })
  await sleep(1200)
  return b
}
async function cannotJoin(username) {
  try { const b = await bot(username); b.quit(); return false } catch (e) { return /kicked|disconnected/.test(e.message) }
}

class Browser {
  jar = new Map()
  async req(p, { method = 'GET', form, headers = {} } = {}) {
    const h = { ...headers }
    if (this.jar.size) h.cookie = [...this.jar].map(([k, v]) => k + '=' + v).join('; ')
    let body
    if (form) { body = new URLSearchParams(form).toString(); h['content-type'] = 'application/x-www-form-urlencoded' }
    const r = await fetch(`http://127.0.0.1:${WEB}${p}`, { method, headers: h, body, redirect: 'manual' })
    for (const sc of r.headers.getSetCookie()) {
      const [kv] = sc.split(';'); const i = kv.indexOf('=')
      const k = kv.slice(0, i), v = kv.slice(i + 1)
      if (!v || /Max-Age=0/.test(sc)) this.jar.delete(k); else this.jar.set(k, v)
    }
    return { status: r.status, headers: r.headers, body: await r.text() }
  }
  async csrf(p) { const m = /name="csrf" value="([^"]+)"/.exec((await this.req(p)).body); if (!m) throw new Error('no form on ' + p); return m[1] }
  /** Step one: the code. 200 is the "This invite is for…" page; nothing is used up. */
  async check(code) { return this.req('/invite', { method: 'POST', form: { csrf: await this.csrf('/'), code } }) }
  /** Both steps, as a person would: the code, then "Yes, that's me". 0.4.0 asks for the code only. */
  async redeem(code) {
    const first = await this.check(code)
    if (first.status !== 200) return first
    const m = /name="csrf" value="([^"]+)"/.exec(first.body)
    return this.req('/invite/confirm', { method: 'POST', form: { csrf: m[1], code } })
  }
  async signOut() { return this.req('/signout', { method: 'POST', form: { csrf: await this.csrf('/') } }) }
  async signIn(link) { return this.req('/signin', { method: 'POST', form: { csrf: await this.csrf('/signin'), link } }) }
}

const codeIn = file => JSON.parse(readFileSync(path.join(dir, 'plugins/Latchkey', file), 'utf8')).value
const sessionDir = path.join(dir, 'plugins/Latchkey/sessions')
const sessionFiles = () => existsSync(sessionDir) ? readdirSync(sessionDir).filter(f => f.endsWith('.json')) : []
const sha = s => createHash('sha256').update(s).digest('hex')
/** A session file as Latchkey writes it, for a cookie the test made, ending in `inSeconds`. */
function plantSession(uuid, name, inSeconds) {
  const cookie = randomBytes(32).toString('base64url')
  const now = Math.floor(Date.now() / 1000), expires = now + inSeconds
  const f = path.join(sessionDir, sha(cookie) + '.json')
  writeFileSync(f, JSON.stringify({ uuid, name, expires, since: expires - 86400 }) + '\n', { mode: 0o600 })
  return { cookie, file: f, expires }
}
const readSession = f => JSON.parse(readFileSync(f, 'utf8'))
/** The offline-mode uuid Paper gives a name. */
function offlineUuid(name) {
  const h = createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30; h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}

// --- the run ---

console.log('== Paper ' + PAPER)
latchkeyConfig({ publicUrl: 'http://play.example.test' })
await start()
const bots = []
try {
  // Safe setup or no setup: a plain-http address keeps the page off, with one line saying how to fix it.
  check('an http public address keeps the landing page off, with the fix logged',
    log.some(l => /landing\.public-url is "http:\/\/play\.example\.test".*Fix: /.test(l)) && !log.some(l => /Landing page listening/.test(l)))
  const s1 = await lk('status')
  check('status says why the page is off', s1.ok && s1.landing === false && s1.problems.some(p => p.includes('HTTPS')), JSON.stringify(s1.problems))
  check('no invite is made while invites can\'t be used', (await lk('invite create Alex')).error?.code === 'not-open')
  latchkeyConfig()
  const s2 = await lk('reload')
  check('after fixing the config, reload starts the page', s2.ok && s2.landing === true && s2.redemption === true && s2.signIn === true, JSON.stringify(s2))
  check('the page answers on its port with security headers',
    (await new Browser().req('/')).headers.get('content-security-policy')?.startsWith("default-src 'none'"))
  check('Usher found Latchkey\'s events', log.some(l => /Checking and applying the profiles on Latchkey invites/.test(l)))
  check('answers come straight after the log header, so chat can\'t fake them', s2.line.includes(']: ea-result '))

  const d = await lk('describe')
  check('describe lists the commands with format 1', d.ok && d.format === 1 && d.commands?.some(c => c.name === 'invite create'), Object.keys(d).join(','))

  // Usher's groups, as an admin would set them up.
  for (const g of ['young', 'grown', 'builders', 'young_sky', 'grown_sky']) await ask('lp creategroup ' + g, 400)
  await ask('lp creategroup everything', 400); await ask('lp group everything permission set * true', 400)
  await ask('usher reload', 1500)
  // latchkey.signin is op-only by default, so an admin grants it to players.
  await ask('lp group default permission set latchkey.signin true', 600)

  // Invites and Usher's checks at creation.
  const bad = await lk('invite create Alex wizards')
  check('Usher refuses an unknown profile when the invite is made', bad.error?.code === 'profiles-refused' && /wizards/.test(bad.error.message), bad.line)
  const q = await lk('invite create Alex grown_sky')
  check('a profile that needs a yes comes back as a question, and nothing is made',
    q.ok === false && q.needs?.[0]?.id === 'confirm-grown-sky', q.line)
  const inv = await lk('invite create Alex grown_sky,builders', 'confirm-grown-sky')
  check('with the yes, the invite is made and the code goes to a once-file', inv.ok && inv.secretFile === 'once/' + inv.id + '.json', inv.line)
  const code = codeIn(inv.secretFile)
  const mode = (statSync(path.join(dir, 'plugins/Latchkey', inv.secretFile)).mode & 0o777).toString(8)
  check('the once-file is owner-only', mode === '600', mode)
  const plain = await lk('invite create Sam')
  const samCode = codeIn(plain.secretFile)

  // Redeeming on the page.
  check('a stranger can\'t join before being invited', await cannotJoin('Alex'))
  const wrong = await new Browser().check('ZZZZZZ')
  check('a wrong code is refused', wrong.status === 400)
  const asked = await new Browser().check(code.toLowerCase().replace(/(...)/, '$1-'))
  check('the right code, typed loosely, first asks "This invite is for Alex", using nothing up',
    asked.status === 200 && /This invite is for <strong>Alex<\/strong>/.test(asked.body) && await cannotJoin('Alex'), String(asked.status))
  const alexBrowser = new Browser()
  const ok = await alexBrowser.redeem(code.toLowerCase().replace(/(...)/, '$1-'))
  check('after "Yes, that\'s me", Alex is in and the browser shows the welcome', ok.status === 303 && /Welcome, <strong>Alex<\/strong>/.test((await alexBrowser.req('/')).body), String(ok.status))
  await alexBrowser.signOut() // the session checks below count only the sessions they make
  await sleep(2500)
  const groups = (await ask('lp user Alex parent info', 1500)).join(' ')
  check('Usher applied both profiles, including the one answered yes at creation', /grown_sky/.test(groups) && /builders/.test(groups), groups.slice(0, 200))
  check('Usher logged it as a Latchkey invite', readFileSync(path.join(dir, 'plugins/Usher/changes.log'), 'utf8').includes('Latchkey invite'))
  check('a used code doesn\'t work twice', (await new Browser().redeem(code)).status === 400)

  // A profile that turns unsafe between the invite and its use isn't applied: Usher checks again at redemption.
  const robinInv = await lk('invite create Robin builders')
  const robinCode = codeIn(robinInv.secretFile)
  await ask('lp group builders parent add everything', 800)
  const robinBrowser = new Browser()
  const robinOk = await robinBrowser.redeem(robinCode)
  await robinBrowser.signOut()
  await sleep(2500)
  const robinGroups = (await ask('lp user Robin parent info', 1500)).join(' ')
  check('a profile made unsafe after the invite is not applied when it\'s used', robinOk.status === 303 && !/builders/.test(robinGroups), robinGroups.slice(0, 200))
  check('and Usher says so in the log', log.some(l => /profiles on Robin's invite weren't applied/.test(l)))
  await ask('lp group builders parent remove everything', 800)
  const alex = await bot('Alex'); bots.push(alex)
  check('Alex can now join', !!alex.entity)
  const near = (b, x, z) => Math.abs(b.entity.position.x - x) < 1.5 && Math.abs(b.entity.position.z - z) < 1.5
  check('on their first join, Alex is sent to the lobby spot', near(alex, 40.5, 40.5), JSON.stringify(alex.entity.position))
  check('and gets the first-timer welcome', alex.raw.some(r => r.includes('First time here: welcome.')))

  const all = log.join('\n') + readFileSync(path.join(dir, 'logs/latest.log'), 'utf8')
  check('no invite code ever reaches the console or the log', !all.includes(code) && !all.includes(samCode))

  // Sign-in links and sessions.
  alex.chat('/signin')
  await sleep(1500)
  const link = alex.raw.map(r => /https:\/\/play\.example\.test\/signin#([A-Za-z0-9_-]{43})/.exec(r)).find(Boolean)
  check('/signin sends Alex a one-time link to the landing page', !!link)
  const br = new Browser()
  const si = await br.signIn(link[0])
  const cookie = si.headers.getSetCookie().find(c => c.startsWith('latchkey_session='))
  check('the link signs the browser in with a strict cookie', si.status === 303 && /HttpOnly/.test(cookie) && /Secure/.test(cookie) && /SameSite=Strict/.test(cookie), cookie)
  check('the browser shows who it is', /Welcome, <strong>Alex<\/strong>/.test((await br.req('/')).body))
  const files = sessionFiles()
  const sf = readSession(path.join(sessionDir, files[0]))
  const fmode = (statSync(path.join(sessionDir, files[0])).mode & 0o777).toString(8)
  check('one session file, named by the cookie\'s hash, with since, owner-only',
    files.length === 1 && files[0] === sha(br.jar.get('latchkey_session')) + '.json' && sf.since > 0 && fmode === '600', JSON.stringify(sf) + ' ' + fmode)
  check('the link works once', (await new Browser().signIn(link[0])).status === 400)

  // Playing keeps a session open; standing idle doesn't.
  const sam = new Browser()
  await sam.redeem(samCode)
  await sam.signOut()
  const samBot = await bot('Sam'); bots.push(samBot)
  const active = plantSession(offlineUuid('Alex'), 'Alex', 300)
  const idle = plantSession(offlineUuid('Sam'), 'Sam', 300)
  // A whitelisted player who gets banned: no event fires, so the 30-second check has to catch it.
  await ask('whitelist add Cass', 600)
  const banned = plantSession(offlineUuid('Cass'), 'Cass', 3600)
  await ask('ban Cass Testing', 600)
  // Alex swings their arm (counted as interaction) and, where bots can move, walks a few blocks.
  // On 26.2 and 26.3 the bots can't move, so playing is shown by swinging an arm. At the lobby spot, Paper fires no
  // interact event for a swing from these bots (checked 2026-11-10: the same swing counts near spawn, and Latchkey's
  // tracker doesn't look at position at all), so Alex is moved back near spawn first. Real players walk, which counts.
  if (VIA) await ask('tp Alex -5.5 -60 -8.5', 1500)
  const swing = setInterval(() => alex.swingArm(), 1000)
  if (!VIA) { alex.physicsEnabled = true; alex.setControlState('forward', true); await sleep(1500); alex.setControlState('forward', false) }
  await sleep(36000)
  clearInterval(swing)
  check('an active player\'s session is extended by a day', readSession(active.file).expires === active.expires + 86400, String(readSession(active.file).expires - active.expires))
  check('an idle player\'s session is not', readSession(idle.file).expires === idle.expires)
  check('a banned player\'s session ends within 30 seconds', !existsSync(banned.file))
  check('the extension is logged', log.some(l => /Kept 1 browser session\(s\) open for a day/.test(l)))

  // The lobby with send: first-join: someone coming back starts where they left, with no welcome.
  await ask('tp Sam 0.5 -60 0.5', 800)
  samBot.quit(); await sleep(2000)
  const samAgain = await bot('Sam'); bots.push(samAgain)
  check('a returning player isn\'t sent to the lobby', near(samAgain, 0.5, 0.5), JSON.stringify(samAgain.entity.position))
  check('and isn\'t welcomed again', !samAgain.raw.some(r => r.includes('First time here: welcome.')))

  // Whitelist removal ends sessions at once, extended ones included.
  const before = sessionFiles().length
  const from = log.length
  send('whitelist remove Alex')
  await waitFor(/Removed Alex from the whitelist|Ended \d+ browser session/, 5000, from)
  const after = sessionFiles().map(f => readSession(path.join(sessionDir, f))).filter(s => s.name === 'Alex').length
  check('removing Alex from the whitelist ends their sessions before the command finishes', before >= 2 && after === 0, `${before} -> ${after} for Alex`)
  check('their browser is signed out', !/Welcome, <strong>/.test((await br.req('/')).body))
  check('Sam\'s session is untouched', existsSync(idle.file))

  // The commands from the other side.
  const sl = await lk('session list Sam')
  check('session list counts Sam\'s sessions', sl.ok && sl.sessions === 1, sl.line)
  const sr = await lk('session revoke Sam')
  check('session revoke signs Sam out everywhere', sr.ok && sr.ended === 1 && !existsSync(idle.file), sr.line)
  const list = await lk('invite list')
  // 0.3.0 answers lists in pages, the spec's way: {"items": [...], "next": ...}.
  check('invite list shows them all, used, without hashes', list.ok && list.items.length >= 3 && list.items.every(i => i.status === 'redeemed') && !/codeHash|[0-9a-f]{64}/.test(list.line), list.line.slice(0, 160))

  // Turning the whitelist off stops sign-in and redemption and ends every session.
  plantSession(offlineUuid('Sam'), 'Sam', 3600)
  send('whitelist off')
  await sleep(1500)
  const st = await lk('status')
  check('with the whitelist off, invites and sign-in stop', st.redemption === false && st.signIn === false && st.problems.some(p => /whitelist is off/.test(p)), JSON.stringify(st))
  check('and every session ends', sessionFiles().length === 0, String(sessionFiles().length))
  send('whitelist on')

  check('no Latchkey or Usher errors in the log', !log.some(l => /(Latchkey|Usher)/.test(l) && /(ERROR|Exception|SEVERE)/.test(l)),
    log.filter(l => /(Latchkey|Usher)/.test(l) && /(ERROR|Exception|SEVERE)/.test(l)).slice(0, 2).join(' | '))
} catch (e) {
  check('the run finished', false, e.message)
} finally {
  if (process.env.LK_TEST_DUMP) for (const l of log) if (new RegExp(process.env.LK_TEST_DUMP).test(l)) console.log('  | ' + strip(l))
  for (const b of bots) try { b.quit() } catch {}
  await stop()
  rmSync(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 })
}
const failed = results.filter(r => !r.ok)
console.log(`${results.length - failed.length}/${results.length} passed`)
if (failed.length) process.exitCode = 1

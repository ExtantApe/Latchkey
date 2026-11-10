// Latchkey's lobby on a throwaway Paper server, with and without Multiverse. A test-only spy plugin logs the lobby
// events, so the test can see what other plugins would see. Built in a temp folder on 127.0.0.1 and deleted after.
//
//   LK_TEST_JARS    folder with paper-<version>.jar, multiverse-core-*.jar, and on 26.2+ ViaVersion-*.jar, ViaBackwards-*.jar
//   LK_TEST_EULA    an eula.txt the owner already accepted. It's copied, never written.
//   LK_TEST_RUN     where the server is built. Must be under a temp folder.
//   LK_TEST_PAPER   26.1.2 (default), 26.2 or 26.3
//   LK_TEST_JAVA    a Java 25 to run Paper with
// Build first: ./gradlew clean build spyJar
import { spawn } from 'node:child_process'
import { copyFileSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import mineflayer from 'mineflayer'

const must = n => { const v = process.env[n]; if (!v) throw new Error('Set ' + n + ' (see the top of test/lobby.mjs)'); return v }
const PAPER = process.env.LK_TEST_PAPER ?? '26.1.2'
const VIA = !PAPER.startsWith('26.1')
const JAVA = process.env.LK_TEST_JAVA ?? 'java'
const JARS = must('LK_TEST_JARS'), EULA = must('LK_TEST_EULA'), RUN = path.resolve(must('LK_TEST_RUN'))
if (![os.tmpdir(), '/tmp', '/private/tmp', '/var/folders'].map(p => path.resolve(p)).some(t => RUN.startsWith(t + path.sep)))
  throw new Error('LK_TEST_RUN must be inside a temp folder, not ' + RUN)
if (!/^eula=true$/m.test(readFileSync(EULA, 'utf8'))) throw new Error('That eula.txt isn\'t accepted. Accepting it is the owner\'s call.')
const ROOT = new URL('../', import.meta.url).pathname
const LK = readdirSync(path.join(ROOT, 'build/libs')).filter(f => /^Latchkey-[\d.]+\.jar$/.test(f))
if (LK.length !== 1) throw new Error('Run ./gradlew clean build spyJar first')
const SPY = path.join(ROOT, 'build/test-spy/LatchkeySpy.jar')

const PORT = 25618
const sleep = ms => new Promise(r => setTimeout(r, ms))
const jar = re => { const f = readdirSync(JARS).find(n => re.test(n)); if (!f) throw new Error('No jar matching ' + re); return path.join(JARS, f) }
const strip = l => l.replace(/^\[[\d:]+ \w+\]: /, '').replace(/^\[[\d:]+\] \[[^\]]+\/\w+\]: /, '')
const results = []
const check = (name, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`); return ok }

class Server {
  log = []
  constructor({ multiverse }) {
    this.dir = path.join(RUN, 'lobby-' + PAPER + (multiverse ? '-mv' : ''))
    rmSync(this.dir, { recursive: true, force: true })
    mkdirSync(path.join(this.dir, 'plugins/Latchkey'), { recursive: true })
    copyFileSync(jar(new RegExp('^paper-' + PAPER.replace(/\./g, '\\.') + '\\.jar$')), path.join(this.dir, 'paper.jar'))
    copyFileSync(EULA, path.join(this.dir, 'eula.txt'))
    const extra = [...(multiverse ? [/^multiverse-core-.*\.jar$/] : []), ...(VIA ? [/^ViaVersion-.*\.jar$/, /^ViaBackwards-.*\.jar$/] : [])]
    for (const re of extra) { const j = jar(re); copyFileSync(j, path.join(this.dir, 'plugins', path.basename(j))) }
    copyFileSync(path.join(ROOT, 'build/libs', LK[0]), path.join(this.dir, 'plugins/Latchkey.jar'))
    copyFileSync(SPY, path.join(this.dir, 'plugins/LatchkeySpy.jar'))
    writeFileSync(path.join(this.dir, 'server.properties'), [
      'server-ip=127.0.0.1', `server-port=${PORT}`, 'online-mode=false', 'white-list=true', 'enforce-whitelist=true',
      'level-type=minecraft\\:flat', 'generate-structures=false', 'spawn-protection=0', 'enable-query=false', 'enable-rcon=false',
      'view-distance=4', 'simulation-distance=4', 'difficulty=peaceful', 'spawn-monsters=false', 'motd=Latchkey lobby test'].join('\n') + '\n')
  }
  /** The shipped config with the lobby section replaced. */
  config(lobby) {
    let t = readFileSync(path.join(ROOT, 'src/main/resources/config.yml'), 'utf8')
    const i = t.indexOf('\nlobby:')
    if (i < 0) throw new Error('The default config has no lobby section')
    writeFileSync(path.join(this.dir, 'plugins/Latchkey/config.yml'), t.slice(0, i) + '\nlobby:\n' + lobby)
  }
  async start() {
    const from = this.log.length
    this.proc = spawn(JAVA, ['-Xms1G', '-Xmx1G', '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Dlatchkey.allowOfflineForTesting=true',
      '-jar', 'paper.jar', 'nogui'], { cwd: this.dir, stdio: ['pipe', 'pipe', 'pipe'] })
    this.exited = new Promise(r => this.proc.on('exit', r))
    const proc = this.proc
    process.on('exit', () => { if (proc.exitCode === null) proc.kill('SIGKILL') })
    const add = d => { for (const l of d.toString().split('\n')) if (l.trim()) this.log.push(strip(l)) }
    this.proc.stdout.on('data', add); this.proc.stderr.on('data', add)
    await this.waitFor(/Done \(\d/, 240000, from)
    await sleep(1500)
  }
  async waitFor(re, ms = 15000, from = 0) {
    const end = Date.now() + ms
    while (Date.now() < end) {
      const hit = this.log.slice(from).find(l => re.test(l))
      if (hit) return hit
      if (this.proc.exitCode !== null) throw new Error('Server exited while waiting for ' + re)
      await sleep(50)
    }
    throw new Error('Timed out waiting for ' + re)
  }
  async ask(cmd, ms = 600) { const from = this.log.length; this.proc.stdin.write(cmd + '\n'); await sleep(ms); return this.log.slice(from) }
  async status() {
    const id = 's' + Math.random().toString(36).slice(2, 8), from = this.log.length
    this.proc.stdin.write('latchkey status --json ' + id + '\n')
    const l = await this.waitFor(new RegExp('^ea-result ' + id + ' '), 15000, from)
    return JSON.parse(l.slice(l.indexOf('{')))
  }
  /** Joins a bot and returns it with the SPY lines its join produced. */
  async join(name) {
    const from = this.log.length
    const b = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '26.1', auth: 'offline', viewDistance: 'tiny', hideErrors: true, physicsEnabled: false })
    if (VIA) {
      const write = b._client.write.bind(b._client)
      b._client.write = (n, d) => /^(position|position_look|look|flying)$/.test(n) ? undefined : write(n, d)
    }
    b.chat_log = []
    b.on('messagestr', m => b.chat_log.push(m))
    b.titles = []
    b.on('title', t => b.titles.push(String(t)))
    b.on('error', () => {})
    await new Promise((res, rej) => { b.once('spawn', res); b.once('kicked', r => rej(new Error('kicked ' + r))); setTimeout(() => rej(new Error('spawn timeout')), 60000) })
    const arrived = await this.waitFor(new RegExp('SPY arrived name=' + name + ' '), 15000, from)
    await sleep(800)
    b.spy = this.log.slice(from).filter(l => l.includes('SPY ') && l.includes('name=' + name + ' '))
    b.arrived = Object.fromEntries(arrived.slice(arrived.indexOf('SPY arrived') + 12).split(' ').map(kv => kv.split('=')))
    return b
  }
  async leave(b) { b.quit(); await sleep(1500) }
  async stop({ keep = false } = {}) {
    if (this.proc && this.proc.exitCode === null) {
      this.proc.stdin.write('stop\n')
      if (!await Promise.race([this.exited.then(() => true), sleep(90000).then(() => false)])) { this.proc.kill('SIGKILL'); await this.exited }
    }
    if (!keep) rmSync(this.dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 })
  }
}

const WELCOME = '  welcome:\n    title: "Welcome"\n    subtitle: "Walk through the gate"\n    chat: ["Hello from the lobby."]\n'

const ONLY = process.env.LK_TEST_ONLY
console.log('== Paper ' + PAPER + ', without Multiverse')
if (ONLY !== 'mv') {
  const s = new Server({ multiverse: false })
  s.config('  world: world\n  spawn: {x: 100.5, y: -60, z: 100.5}\n  send: first-join\n' + WELCOME)
  try {
    await s.start()
    for (const n of ['Newbie', 'Other']) await s.ask('whitelist add ' + n, 400)
    check('the lobby is on, with no problems', (await s.status()).lobby === true)
    check('lobbyLocation() answers by name, as other plugins read it', s.log.some(l => /SPY lobbyLocation=world,100,100/.test(l)), s.log.filter(l => /lobbyLocation/.test(l)).join(' | '))
    const a = await s.join('Newbie')
    check('a first-timer fires FirstJoinEvent on the server thread', a.spy.some(l => /SPY first name=Newbie main=true/.test(l)), a.spy.join(' | '))
    check('and arrives in the lobby', a.arrived.first === 'true' && a.arrived.sent === 'true' && a.arrived.at === 'world,100,100' && a.arrived.lobby === 'world,100,100', JSON.stringify(a.arrived))
    check('PlayerArrivedEvent comes after the teleport, on the server thread', a.arrived.main === 'true')
    check('the welcome is shown in chat', a.chat_log.some(m => m.includes('Hello from the lobby.')), a.chat_log.join(' | '))
    check('and as a title', a.titles.some(t => t.includes('Welcome')), a.titles.join(' | '))
    await s.ask('tp Newbie 0 -60 0', 800)
    await s.leave(a)
    const back = await s.join('Newbie')
    check('a returning player isn\'t first-join and isn\'t moved (first-join mode)', !back.spy.some(l => /SPY first/.test(l)) && back.arrived.first === 'false' && back.arrived.sent === 'false' && back.arrived.at === 'world,0,0', JSON.stringify(back.arrived))
    check('and isn\'t welcomed again', !back.chat_log.some(m => m.includes('Hello from the lobby.')))
    await s.leave(back)

    s.config('  world: world\n  spawn: {x: 100.5, y: -60, z: 100.5}\n  send: always\n' + WELCOME)
    await s.ask('latchkey reload', 1500)
    const again = await s.join('Newbie')
    check('in always mode, every join goes to the lobby', again.arrived.sent === 'true' && again.arrived.at === 'world,100,100', JSON.stringify(again.arrived))
    await s.leave(again)

    s.config('  world: nowhere\n  send: always\n')
    await s.ask('latchkey reload', 1500)
    const st = await s.status()
    check('a missing world turns the lobby off, with the fix in status', st.lobby === false && st.problems.some(p => /lobby\.world is "nowhere".*Fix: /.test(p)), JSON.stringify(st.problems))
    const o = await s.join('Other')
    check('then nobody is moved, and the events still fire', o.arrived.first === 'true' && o.arrived.sent === 'false' && o.arrived.lobby === 'none', JSON.stringify(o.arrived))
    check('and nobody is welcomed', !o.chat_log.some(m => m.includes('Hello from the lobby.')))
    await s.leave(o)

    s.config('  world: world\n  send: sometimes\n  give-items: true\n')
    await s.ask('latchkey reload', 1500)
    const bad = await s.status()
    check('bad lobby settings turn the lobby off, with the fix', bad.lobby === false && bad.problems.some(p => /lobby\.give-items.*Fix: /.test(p)), JSON.stringify(bad.problems))
    check('no Latchkey errors', !s.log.some(l => /Latchkey/.test(l) && /(ERROR|Exception|SEVERE)/.test(l)))
  } catch (e) { check('the run finished', false, e.message); for (const l of s.log.filter(l => /Exception|Caused by|at com.extantape|Error|issued server command/.test(l)).slice(0, 25)) console.log('  | ' + l) } finally { await s.stop() }
}

console.log('== Paper ' + PAPER + ', with Multiverse')
{
  const s = new Server({ multiverse: true })
  s.config('  world: lobby\n  send: first-join\n' + WELCOME)
  try {
    await s.start()
    await s.ask('whitelist add Arrival', 400)
    const before = await s.status()
    check('before the lobby world exists, status says how to make it', before.lobby === false && before.problems.some(p => /mv create lobby/.test(p)), JSON.stringify(before.problems))
    await s.ask('mv create lobby normal --world-type flat --no-structures', 8000)
    await s.ask('latchkey reload', 1500)
    check('after creating it with Multiverse, the lobby is on', (await s.status()).lobby === true)
    const a = await s.join('Arrival')
    check('a first-timer arrives in the Multiverse lobby world', a.arrived.sent === 'true' && a.arrived.at.startsWith('lobby,') && a.arrived.lobby.startsWith('lobby,'), JSON.stringify(a.arrived))
    await s.leave(a)
    await s.stop({ keep: true })
    const mark = s.log.length
    await s.start()
    await sleep(1000)
    check('after a restart, the lobby is found without a false warning',
      !s.log.slice(mark).some(l => /no world by that name is loaded/.test(l)) && (await s.status()).lobby === true)
    check('no Latchkey errors', !s.log.some(l => /Latchkey/.test(l) && /(ERROR|Exception|SEVERE)/.test(l)))
  } catch (e) { check('the run finished', false, e.message); for (const l of s.log.filter(l => /Exception|Caused by|at com.extantape|Error|issued server command/.test(l)).slice(0, 25)) console.log('  | ' + l) } finally { await s.stop() }
}

const failed = results.filter(r => !r).length
console.log(`${results.length - failed}/${results.length} passed`)
if (failed) process.exitCode = 1

// Integration tests for Item Milestone Log against a real dedicated server.
//
// The server is started by .github/scripts/run-server-tests.sh. This script talks
// to it over RCON and, where mineflayer supports the Minecraft version, joins as a
// vanilla-protocol client (a client without the mod installed) to check what a
// player actually sees.
//
// Phases:
//   fresh    first start: command registration over RCON, then (with --bot) the
//            full player flow: commands, recording by inventory scan and by pickup
//   restart  after a server restart (with --bot): the data saved before is back
//   stop     stop the server over RCON
import net from 'node:net';
import { once } from 'node:events';
import { setTimeout as sleep } from 'node:timers/promises';
import { parseArgs } from 'node:util';

const { values: opt } = parseArgs({
  options: {
    phase: { type: 'string', default: 'fresh' },
    mc: { type: 'string' },
    bot: { type: 'boolean', default: false },
    host: { type: 'string', default: '127.0.0.1' },
    port: { type: 'string', default: '25565' },
    'rcon-port': { type: 'string', default: '25575' },
    'rcon-password': { type: 'string', default: 'iml-ci' },
    'scan-interval-ticks': { type: 'string', default: '600' },
  },
});

const BOT_NAME = 'ImlBot';
const SCAN_SECONDS = Number(opt['scan-interval-ticks']) / 20;
let failures = 0;

function pass(name) {
  console.log(`  PASS  ${name}`);
}

function fail(name, detail) {
  failures++;
  console.log(`  FAIL  ${name}`);
  if (detail) console.log(String(detail).split('\n').map((l) => `        ${l}`).join('\n'));
}

async function check(name, fn) {
  try {
    await fn();
    pass(name);
  } catch (e) {
    fail(name, e?.message ?? e);
  }
}

// ------------------------------------------------------------------ RCON

class Rcon {
  constructor(host, port, password) {
    Object.assign(this, { host, port: Number(port), password });
    this.buffer = Buffer.alloc(0);
    this.waiting = new Map();
    this.nextId = 1;
  }

  async connect() {
    this.socket = net.connect({ host: this.host, port: this.port });
    // The server closes the connection on "stop"; never let that crash the script.
    this.socket.on('error', (err) => console.log(`        [rcon] ${err.message}`));
    await once(this.socket, 'connect');
    this.socket.on('data', (chunk) => {
      this.buffer = Buffer.concat([this.buffer, chunk]);
      this.drain();
    });
    const auth = await this.request(3, this.password);
    if (auth.id === -1) throw new Error('RCON authentication failed');
  }

  drain() {
    while (this.buffer.length >= 4) {
      const length = this.buffer.readInt32LE(0);
      if (this.buffer.length < 4 + length) return;
      const id = this.buffer.readInt32LE(4);
      const body = this.buffer.toString('utf8', 12, 4 + length - 2);
      this.buffer = this.buffer.subarray(4 + length);
      // A failed login answers with id -1, so hand it to whoever is waiting.
      const key = this.waiting.has(id) ? id : [...this.waiting.keys()][0];
      const resolve = this.waiting.get(key);
      if (resolve) {
        this.waiting.delete(key);
        resolve({ id, body });
      }
    }
  }

  request(type, body) {
    const id = this.nextId++;
    const payload = Buffer.from(body, 'utf8');
    const packet = Buffer.alloc(14 + payload.length);
    packet.writeInt32LE(10 + payload.length, 0);
    packet.writeInt32LE(id, 4);
    packet.writeInt32LE(type, 8);
    payload.copy(packet, 12);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiting.delete(id);
        reject(new Error(`RCON timeout for: ${body}`));
      }, 15000);
      this.waiting.set(id, (res) => {
        clearTimeout(timer);
        resolve(res);
      });
      this.socket.write(packet);
    });
  }

  async command(cmd) {
    const res = await this.request(2, cmd);
    return res.body;
  }

  close() {
    this.socket?.end();
  }
}

async function connectRcon() {
  const rcon = new Rcon(opt.host, opt['rcon-port'], opt['rcon-password']);
  await rcon.connect();
  return rcon;
}

// ------------------------------------------------------------------- bot

async function joinBot() {
  // On 1.21.1, mineflayer 4.39 prints a PartialReadError (item components in an
  // armor trim entry) for one of the join packets it cannot fully parse. It is not
  // fatal, and not caused by this mod, which adds no items, recipes or registries.
  const { default: mineflayer } = await import('mineflayer');
  const bot = mineflayer.createBot({
    host: opt.host,
    port: Number(opt.port),
    username: BOT_NAME,
    version: opt.mc,
    auth: 'offline',
  });
  const messages = [];
  bot.on('message', (msg, position) => {
    const text = msg.toString();
    messages.push({ msg, text, position });
    console.log(`        [chat:${position}] ${text}`);
  });
  bot.on('kicked', (reason) => console.log(`        [kicked] ${JSON.stringify(reason)}`));
  bot.on('error', (err) => console.log(`        [error] ${err?.message ?? err}`));

  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('bot did not spawn within 60s')), 60000);
    bot.once('spawn', () => {
      clearTimeout(timer);
      resolve();
    });
    bot.once('end', (reason) => {
      clearTimeout(timer);
      reject(new Error(`bot disconnected before spawning: ${reason}`));
    });
  });
  await sleep(1500);
  return { bot, messages };
}

/** Waits until a message received after index `from` matches `pattern`. */
async function waitFor(session, from, pattern, timeoutMs = 8000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const hit = session.messages.slice(from).find((m) => pattern.test(m.text));
    if (hit) return hit;
    await sleep(100);
  }
  const seen = session.messages.slice(from).map((m) => `    ${m.text}`).join('\n') || '    (no messages)';
  throw new Error(`expected a message matching ${pattern} within ${timeoutMs} ms; got:\n${seen}`);
}

/** Runs a chat command as the bot and waits for a matching answer. */
async function command(session, cmd, pattern, timeoutMs) {
  const from = session.messages.length;
  session.bot.chat(`/${cmd}`);
  return waitFor(session, from, pattern, timeoutMs);
}

/** The message must be a translatable component of `key` that carries an English fallback. */
function assertFallback(hit, key) {
  const { msg } = hit;
  if (msg.translate !== key) throw new Error(`expected translate=${key}, got ${msg.translate} (${hit.text})`);
  if (typeof msg.fallback !== 'string' || msg.fallback.length === 0) {
    throw new Error(`message ${key} has no English fallback, so vanilla clients would see the raw key`);
  }
}

// ---------------------------------------------------------------- phases

async function rconChecks(rcon) {
  // "help <command>" prints one usage line per subcommand. The root node is itself
  // executable, so Brigadier shows the subcommands as optional: "/milestonelog [add] <item>".
  await check('RCON: /milestonelog and all its subcommands are registered', async () => {
    const out = await rcon.command('help milestonelog');
    const expected = ['playtime', 'add] <item>', 'addhand', 'remove] <item>', 'targets', 'list',
      'preset] <name>', 'reset] <item>', 'resetall'];
    const missing = expected.filter((s) => !out.includes(`/milestonelog [${s}`));
    if (missing.length > 0) throw new Error(`missing: ${missing.join(', ')}\n${out}`);
  });
  // The console is not a player, so every command answers with the "player required"
  // error. That still shows "/iml" executes on its own (a bare redirect would be
  // "Unknown or incomplete command") and forwards its subcommands to /milestonelog.
  // ("help iml" prints nothing for a redirect, and the full "help" listing is longer
  // than one RCON packet, so neither is used here.)
  for (const cmd of ['milestonelog', 'iml', 'iml playtime']) {
    await check(`RCON: /${cmd} runs and refuses the console cleanly`, async () => {
      const out = await rcon.command(cmd);
      if (!/player/i.test(out) || /unknown|incomplete|incorrect/i.test(out)) throw new Error(out || '(empty answer)');
    });
  }
}

async function freshBotFlow(rcon) {
  const session = await joinBot();
  pass('vanilla-protocol client can join the server');

  const summary = /^Playtime: \d+h \d{2}m \d{2}s \| Milestones: 0 \/ 0 targets recorded/;
  await check('/milestonelog shows the summary in English (fallback) on a vanilla client', async () => {
    const hit = await command(session, 'milestonelog', summary);
    assertFallback(hit, 'message.itemmilestonelog.summary');
  });
  await check('/iml alone runs the summary', async () => {
    await command(session, 'iml', summary);
  });
  await check('/iml subcommands are forwarded', async () => {
    await command(session, 'iml playtime', /^Your cumulative playtime: \d+h \d{2}m \d{2}s$/);
  });
  await check('/milestonelog add', async () => {
    const hit = await command(session, 'milestonelog add minecraft:cobblestone', /^Now tracking: Cobblestone$/);
    assertFallback(hit, 'message.itemmilestonelog.added');
  });
  await check('/milestonelog add rejects duplicates', async () => {
    await command(session, 'milestonelog add minecraft:cobblestone', /^Already tracking: Cobblestone$/);
  });

  // Pickup: the scan interval is long (see --scan-interval-ticks), and the item is
  // cleared again right after it was picked up, so only the pickup event can record it.
  await check('picking up a target item records it immediately', async () => {
    const from = session.messages.length;
    await rcon.command(
      `execute at ${BOT_NAME} run summon minecraft:item ~ ~ ~ {Item:{id:"minecraft:cobblestone",count:1},PickupDelay:0}`);
    await sleep(1500);
    const cleared = await rcon.command(`clear ${BOT_NAME} minecraft:cobblestone`);
    if (!/Removed 1/.test(cleared)) throw new Error(`the bot did not pick the item up: ${cleared}`);
    const hit = await waitFor(session, from, /^★ Milestone recorded: Cobblestone at playtime \d+h \d{2}m \d{2}s$/, 4000);
    assertFallback(hit, 'message.itemmilestonelog.recorded');
  });

  await check('an item given straight into the inventory is recorded by the scan', async () => {
    await command(session, 'milestonelog add minecraft:dirt', /^Now tracking: Dirt$/);
    const from = session.messages.length;
    await rcon.command(`give ${BOT_NAME} minecraft:dirt 1`);
    await waitFor(session, from, /^★ Milestone recorded: Dirt at playtime /, (SCAN_SECONDS + 5) * 1000);
  });

  await check('/milestonelog targets lists recorded and pending targets', async () => {
    await command(session, 'milestonelog add minecraft:diamond', /^Now tracking: Diamond$/);
    const from = session.messages.length;
    session.bot.chat('/milestonelog targets');
    await waitFor(session, from, /^Target items \(3\):$/);
    await waitFor(session, from, /^✔ Cobblestone — \d+h \d{2}m \d{2}s$/);
    await waitFor(session, from, /^… Diamond — not yet obtained$/);
  });
  await check('/milestonelog list shows records, earliest first', async () => {
    await command(session, 'milestonelog list', /^Recorded milestones \(2\), earliest first:$/);
  });
  await check('/milestonelog preset vanilla_endgame', async () => {
    // diamond is already a target, so 7 of the 8 items are new
    await command(session, 'milestonelog preset vanilla_endgame',
      /^Preset 'vanilla_endgame' applied: 7 targets added, 0 skipped/);
  });
  await check('/milestonelog preset atm_star skips items that are not installed', async () => {
    await command(session, 'milestonelog preset atm_star', /^Preset 'atm_star' applied: 0 targets added, 10 skipped/);
  });
  await check('unknown presets list the available ones in a stable order', async () => {
    await command(session, 'milestonelog preset nope', /^Unknown preset 'nope'\. Available: atm_star, vanilla_endgame$/);
  });
  await check('/milestonelog remove', async () => {
    await command(session, 'milestonelog remove minecraft:diamond', /^Stopped tracking: Diamond$/);
  });
  await check('/milestonelog reset', async () => {
    await command(session, 'milestonelog reset minecraft:dirt', /^Record cleared: Dirt/);
  });
  await check('/milestonelog reset of something not recorded', async () => {
    await command(session, 'milestonelog reset minecraft:dirt', /^No record for: Dirt$/);
  });

  session.bot.quit();
  await sleep(1000);
}

async function restartBotFlow() {
  const session = await joinBot();
  // Saved before the restart: cobblestone (recorded), dirt (record reset),
  // 7 vanilla_endgame items (diamond removed again) = 9 targets, 1 record.
  await check('targets and records survive a server restart', async () => {
    const from = session.messages.length;
    session.bot.chat('/milestonelog targets');
    await waitFor(session, from, /^Target items \(9\):$/);
    await waitFor(session, from, /^✔ Cobblestone — \d+h \d{2}m \d{2}s$/);
    await waitFor(session, from, /^… Dirt — not yet obtained$/);
  });
  await check('/milestonelog resetall after the restart', async () => {
    await command(session, 'milestonelog resetall', /^Cleared 1 records\. Targets were kept\.$/);
  });
  session.bot.quit();
  await sleep(1000);
}

// ------------------------------------------------------------------ main

const rcon = await connectRcon();
try {
  if (opt.phase === 'stop') {
    await rcon.command('stop').catch(() => {});
    process.exit(0);
  } else if (opt.phase === 'fresh') {
    console.log(`Integration tests (MC ${opt.mc}, phase fresh${opt.bot ? ', with vanilla client' : ', RCON only'})`);
    await rconChecks(rcon);
    if (opt.bot) await freshBotFlow(rcon);
  } else if (opt.phase === 'restart') {
    console.log(`Integration tests (MC ${opt.mc}, phase restart)`);
    await restartBotFlow();
  } else {
    throw new Error(`unknown phase ${opt.phase}`);
  }
} finally {
  rcon.close();
}

if (failures > 0) {
  console.log(`${failures} check(s) failed`);
  process.exit(1);
}
console.log('all checks passed');
process.exit(0);

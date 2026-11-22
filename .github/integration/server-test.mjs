// Integration tests for Item Milestone Log against a real dedicated server.
//
// The server is started by .github/scripts/run-server-tests.sh. This script talks
// to it over RCON and, where mineflayer supports the Minecraft version, joins as a
// vanilla-protocol client (a client without the mod installed) to check what a
// player actually sees.
//
// Phases:
//   fresh           first start: command registration over RCON, then (with --bot)
//                   the full player flow: commands, recording by inventory scan and
//                   by pickup, nothing recorded in creative mode
//   restart         after a server restart (with --bot): the data saved before is back
//   migrate-before  on 1.21.1 (always with the client): make a record to carry over
//   migrate-after   on 26.1.2, same world: the 1.21.1 record is there
//   stop            stop the server over RCON
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
  return { bot, messages, lastChat: 0 };
}

// The server kicks a non-op player for spam (disconnect.spam) when chat messages and
// commands come faster than about one per second for a while: each one adds 20 to a
// counter that drops by 1 per tick, with a limit of 200. The bot stays a normal player
// (no op), so commands are tested with the permissions players have, and is paced.
const CHAT_SPACING_MS = 1000;

async function say(session, text) {
  const wait = session.lastChat + CHAT_SPACING_MS - Date.now();
  if (wait > 0) await sleep(wait);
  session.lastChat = Date.now();
  session.bot.chat(text);
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
  await say(session, `/${cmd}`);
  return waitFor(session, from, pattern, timeoutMs);
}

/** Fails if a "Milestone recorded" message for `name` arrived after index `from`. */
function assertNotRecorded(session, from, name) {
  const hit = session.messages.slice(from).find((m) => m.text.startsWith(`★ Milestone recorded: ${name} at`));
  if (hit) throw new Error(`${name} was recorded: ${hit.text}`);
}

/** How many of `item` the bot has ("clear <player> <item> 0" only counts). */
async function countItem(rcon, item) {
  const out = await rcon.command(`clear ${BOT_NAME} ${item} 0`);
  const m = /Found (\d+)/.exec(out);
  return m ? Number(m[1]) : 0;
}

/** Drops one `item` at the bot's feet and waits until the bot has picked it up. */
async function summonAndPickUp(rcon, item) {
  const before = await countItem(rcon, item);
  await rcon.command(
    `execute at ${BOT_NAME} run summon minecraft:item ~ ~ ~ {Item:{id:"${item}",count:1},PickupDelay:0}`);
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    await sleep(250);
    if (await countItem(rcon, item) > before) return;
  }
  throw new Error(`the bot did not pick up ${item}`);
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
    await say(session, '/milestonelog targets');
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
    // Without the dirt in the inventory, so the scan does not record it again.
    await rcon.command(`clear ${BOT_NAME} minecraft:dirt`);
    await command(session, 'milestonelog reset minecraft:dirt', /^Record cleared: Dirt/);
  });
  await check('/milestonelog reset of something not recorded', async () => {
    await command(session, 'milestonelog reset minecraft:dirt', /^No record for: Dirt$/);
  });

  await creativeModeFlow(session, rcon);

  session.bot.quit();
  await sleep(1000);
}

// ignoreCreativeAndSpectator is on by default. The scan interval is long, so a scan
// is only known to have run when it records something: a second target given at the
// same time (iron, copper) shows that the scan has also looked at the gold ingot.
async function creativeModeFlow(session, rcon) {
  const scanMs = (SCAN_SECONDS + 5) * 1000;
  await check('creative mode: targets for the checks below', async () => {
    await command(session, 'milestonelog add minecraft:gold_ingot', /^Now tracking: Gold Ingot$/);
    await command(session, 'milestonelog add minecraft:iron_ingot', /^Now tracking: Iron Ingot$/);
    await command(session, 'milestonelog add minecraft:copper_ingot', /^Now tracking: Copper Ingot$/);
  });
  await check('creative mode: nothing is recorded, by pickup or by the scan', async () => {
    await rcon.command(`gamemode creative ${BOT_NAME}`);
    const from = session.messages.length;
    await summonAndPickUp(rcon, 'minecraft:gold_ingot');
    // Long enough for at least one scan (which does not run in creative mode).
    await sleep(scanMs - 3000);
    assertNotRecorded(session, from, 'Gold Ingot');
  });
  await check('back in survival, an item carried out of creative mode is not recorded', async () => {
    await rcon.command(`gamemode survival ${BOT_NAME}`);
    const from = session.messages.length;
    await rcon.command(`give ${BOT_NAME} minecraft:iron_ingot 1`);
    await waitFor(session, from, /^★ Milestone recorded: Iron Ingot at playtime /, scanMs);
    await sleep(500);
    assertNotRecorded(session, from, 'Gold Ingot');
  });
  await check('picking up more of it while still carrying it is not recorded either', async () => {
    const from = session.messages.length;
    await summonAndPickUp(rcon, 'minecraft:gold_ingot');
    await sleep(2000);
    assertNotRecorded(session, from, 'Gold Ingot');
  });
  await check('once it has left the inventory, the next one is recorded', async () => {
    await rcon.command(`clear ${BOT_NAME} minecraft:gold_ingot`);
    let from = session.messages.length;
    // This scan finds no gold ingot any more, so it forgets it.
    await rcon.command(`give ${BOT_NAME} minecraft:copper_ingot 1`);
    await waitFor(session, from, /^★ Milestone recorded: Copper Ingot at playtime /, scanMs);
    from = session.messages.length;
    await summonAndPickUp(rcon, 'minecraft:gold_ingot');
    await waitFor(session, from, /^★ Milestone recorded: Gold Ingot at playtime /, 4000);
  });
}

async function restartBotFlow() {
  const session = await joinBot();
  // Saved before the restart: cobblestone (recorded), dirt (record reset),
  // 7 vanilla_endgame items (diamond removed again), gold, iron and copper ingots
  // (recorded) = 12 targets, 4 records.
  await check('targets and records survive a server restart', async () => {
    const from = session.messages.length;
    await say(session, '/milestonelog targets');
    await waitFor(session, from, /^Target items \(12\):$/);
    await waitFor(session, from, /^✔ Cobblestone — \d+h \d{2}m \d{2}s$/);
    await waitFor(session, from, /^✔ Gold Ingot — \d+h \d{2}m \d{2}s$/);
    await waitFor(session, from, /^… Dirt — not yet obtained$/);
  });
  await check('/milestonelog resetall after the restart', async () => {
    await command(session, 'milestonelog resetall', /^Cleared 4 records\. Targets were kept\.$/);
  });
  session.bot.quit();
  await sleep(1000);
}

// A 1.21.1 world with one record and one pending target, to be opened by 26.1.2.
async function migrateBeforeFlow(rcon) {
  const session = await joinBot();
  await check('1.21.1: a record to carry over', async () => {
    await command(session, 'milestonelog add minecraft:cobblestone', /^Now tracking: Cobblestone$/);
    await command(session, 'milestonelog add minecraft:dirt', /^Now tracking: Dirt$/);
    const from = session.messages.length;
    await summonAndPickUp(rcon, 'minecraft:cobblestone');
    await waitFor(session, from, /^★ Milestone recorded: Cobblestone at playtime /, 4000);
  });
  session.bot.quit();
  await sleep(1000);
}

async function migrateAfterFlow() {
  const session = await joinBot();
  await check('26.1.2: the targets and the record made by 1.21.1 are there', async () => {
    const from = session.messages.length;
    await say(session, '/milestonelog targets');
    await waitFor(session, from, /^Target items \(2\):$/);
    await waitFor(session, from, /^✔ Cobblestone — \d+h \d{2}m \d{2}s$/);
    await waitFor(session, from, /^… Dirt — not yet obtained$/);
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
  } else if (opt.phase === 'migrate-before') {
    console.log(`Migration test (MC ${opt.mc}, before)`);
    await migrateBeforeFlow(rcon);
  } else if (opt.phase === 'migrate-after') {
    console.log(`Migration test (MC ${opt.mc}, after)`);
    await migrateAfterFlow();
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

// Integration tests for Item Milestone Log against a real dedicated server.
//
// The server is started by .github/scripts/run-server-tests.sh. This script talks
// to it over RCON and, where mineflayer supports the Minecraft version, joins as a
// vanilla-protocol client (a client without the mod installed) to check what a
// player actually sees.
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
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
    // Must match scanIntervalTicks in the server's config (SCAN_INTERVAL_TICKS in server-lib.sh).
    'scan-interval-ticks': { type: 'string', default: '240' },
    'server-dir': { type: 'string', default: '' },
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

async function joinBot(username = BOT_NAME) {
  const { default: mineflayer } = await import('mineflayer');
  const bot = mineflayer.createBot({
    host: opt.host,
    port: Number(opt.port),
    username,
    version: opt.mc,
    auth: 'offline',
  });
  const messages = [];
  bot.on('message', (msg, position) => {
    const text = msg.toString();
    messages.push({ msg, text, position });
    console.log(`        [chat:${username}:${position}] ${text}`);
  });
  bot.on('kicked', (reason) => console.log(`        [kicked:${username}] ${JSON.stringify(reason)}`));
  bot.on('error', (err) => console.log(`        [error:${username}] ${err?.message ?? err}`));

  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${username} did not spawn within 60s`)), 60000);
    bot.once('spawn', () => {
      clearTimeout(timer);
      resolve();
    });
    bot.once('end', (reason) => {
      clearTimeout(timer);
      reject(new Error(`${username} disconnected before spawning: ${reason}`));
    });
  });
  await sleep(1500);
  return { bot, messages, lastChat: 0, username };
}

const CHAT_SPACING_MS = 1000;

async function say(session, text) {
  const wait = session.lastChat + CHAT_SPACING_MS - Date.now();
  if (wait > 0) await sleep(wait);
  session.lastChat = Date.now();
  session.bot.chat(text);
}

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

async function command(session, cmd, pattern, timeoutMs) {
  const from = session.messages.length;
  await say(session, `/${cmd}`);
  return waitFor(session, from, pattern, timeoutMs);
}

function assertNotRecorded(session, from, name) {
  const hit = session.messages.slice(from).find((m) => m.text.startsWith(`★ Milestone recorded: ${name} at`));
  if (hit) throw new Error(`${name} was recorded: ${hit.text}`);
}

async function countItem(rcon, item, botName = BOT_NAME) {
  const out = await rcon.command(`clear ${botName} ${item} 0`);
  const m = /Found (\d+)/.exec(out);
  return m ? Number(m[1]) : 0;
}

async function summonAndPickUp(rcon, item, botName = BOT_NAME) {
  const before = await countItem(rcon, item, botName);
  await rcon.command(
    `execute at ${botName} run summon minecraft:item ~ ~ ~ {Item:{id:"${item}",count:1},PickupDelay:0}`);
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    await sleep(250);
    if (await countItem(rcon, item, botName) > before) return;
  }
  throw new Error(`${botName} did not pick up ${item}`);
}

function assertFallback(hit, key) {
  const { msg } = hit;
  if (msg.translate !== key) throw new Error(`expected translate=${key}, got ${msg.translate} (${hit.text})`);
  if (typeof msg.fallback !== 'string' || msg.fallback.length === 0) {
    throw new Error(`message ${key} has no English fallback, so vanilla clients would see the raw key`);
  }
}

// ---------------------------------------------------------------- phases

async function rconChecks(rcon) {
  await check('RCON: /milestonelog and all its subcommands are registered', async () => {
    const out = await rcon.command('help milestonelog');
    const expected = ['playtime', 'add] <item>', 'addhand', 'remove] <item>', 'targets', 'list',
      'preset]', 'reset] <item>', 'resetall', 'firsts]', 'export]', 'import] <fileName>',
      'exportall]', 'mutebroadcast]', 'admin]'];
    const missing = expected.filter((s) => !out.includes(`/milestonelog [${s}`));
    if (missing.length > 0) throw new Error(`missing: ${missing.join(', ')}\n${out}`);
  });
  for (const cmd of ['milestonelog', 'iml', 'iml playtime']) {
    await check(`RCON: /${cmd} runs and refuses the console cleanly`, async () => {
      const out = await rcon.command(cmd);
      if (!/player/i.test(out) || /unknown|incomplete|incorrect/i.test(out)) throw new Error(out || '(empty answer)');
    });
  }
  await check('RCON: preset list includes pre-seeded ci_custom and ignores broken/builtin collision', async () => {
    const out = await rcon.command('milestonelog preset list');
    if (!out.includes('ci_custom') || !out.includes('CI Custom Pack') || !out.includes('atm_star')) {
      throw new Error(out);
    }
    if (out.includes('broken') || out.includes('Fake ATM Star')) {
      throw new Error(`unexpected preset in output:\n${out}`);
    }
  });
  await check('RCON: firsts, admin players, and exportall execute cleanly from console', async () => {
    const fOut = await rcon.command('milestonelog firsts');
    if (!/No world first milestones/i.test(fOut)) throw new Error(fOut);
    const aOut = await rcon.command('milestonelog admin players');
    if (!/No player milestone data/i.test(aOut)) throw new Error(aOut);
    const eOut = await rcon.command('milestonelog exportall');
    if (!/Exported milestones for 0 player\(s\)/i.test(eOut)) throw new Error(eOut);
  });
}

async function freshBotFlow(rcon) {
  const session = await joinBot(BOT_NAME);
  pass('vanilla-protocol client can join the server');

  await check('F10: vanilla client receives the first-join command tip once', async () => {
    const hit = session.messages.find((m) => /^Tip: Use \/milestonelog/.test(m.text));
    if (!hit) {
      throw new Error(`join hint not found in:\n${session.messages.map((m) => m.text).join('\n')}`);
    }
    assertFallback(hit, 'message.itemmilestonelog.join_hint_vanilla');
  });

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

  await check('picking up a target item records it immediately', async () => {
    const from = session.messages.length;
    // Taken out of the inventory right after the pickup, so the record has to come from
    // the pickup hook: the inventory scan (every SCAN_SECONDS) is unlikely to run in between.
    await summonAndPickUp(rcon, 'minecraft:cobblestone');
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
  await check('F1: /milestonelog list shows records with Day and date', async () => {
    const from = session.messages.length;
    await say(session, '/milestonelog list');
    await waitFor(session, from, /^Recorded milestones \(2\), earliest first:$/);
    await waitFor(session, from, /^✔ Cobblestone — \d+h \d{2}m \d{2}s \(Day \d+, \d{4}-\d{2}-\d{2}\)$/);
  });
  await check('/milestonelog preset vanilla_endgame', async () => {
    await command(session, 'milestonelog preset vanilla_endgame',
      /^Preset 'vanilla_endgame' applied: 7 targets added, 0 skipped/);
  });
  await check('/milestonelog preset atm_star skips items that are not installed', async () => {
    await command(session, 'milestonelog preset atm_star', /^Preset 'atm_star' applied: 0 targets added, 10 skipped/);
  });
  await check('Custom preset: /milestonelog preset ci_custom adds valid items and skips uninstalled ones', async () => {
    await command(session, 'milestonelog preset ci_custom', /^Preset 'ci_custom' applied: 1 targets added, 1 skipped/);
    await command(session, 'milestonelog remove minecraft:emerald', /^Stopped tracking: Emerald$/);
  });
  await check('unknown presets list the available ones in a stable order', async () => {
    await command(session, 'milestonelog preset nope',
      /^Unknown preset 'nope'\. Available: atm_star, ci_custom, vanilla_endgame$/);
  });
  await check('/milestonelog remove', async () => {
    await command(session, 'milestonelog remove minecraft:diamond', /^Stopped tracking: Diamond$/);
  });
  await check('/milestonelog reset', async () => {
    await rcon.command(`clear ${BOT_NAME} minecraft:dirt`);
    await command(session, 'milestonelog reset minecraft:dirt', /^Record cleared: Dirt/);
  });
  await check('/milestonelog reset of something not recorded', async () => {
    await command(session, 'milestonelog reset minecraft:dirt', /^No record for: Dirt$/);
  });

  await quantityTargetsFlow(session, rcon);
  await creativeModeFlow(session, rcon);
  await multiPlayerAndPresetManagementFlow(session, rcon);

  session.bot.quit();
  await sleep(1000);
}

async function quantityTargetsFlow(session, rcon) {
  const scanMs = (SCAN_SECONDS + 5) * 1000;
  const oneScanMs = (SCAN_SECONDS + 2) * 1000;

  await check('Quantity: x5 target is not recorded at 4 items and is recorded at 5 items', async () => {
    await command(session, 'milestonelog add minecraft:diamond 5', /^Now tracking: Diamond ×5$/);
    await command(session, 'milestonelog add minecraft:diamond 5', /^Already tracking: Diamond ×5$/);

    const from4 = session.messages.length;
    await rcon.command(`give ${BOT_NAME} minecraft:diamond 4`);
    await sleep(oneScanMs);
    assertNotRecorded(session, from4, 'Diamond ×5');

    const t4From = session.messages.length;
    await say(session, '/milestonelog targets');
    await waitFor(session, t4From, /^… Diamond ×5 \(current: 4\) — not yet obtained$/);

    const from5 = session.messages.length;
    await rcon.command(`give ${BOT_NAME} minecraft:diamond 1`);
    const hit = await waitFor(session, from5, /^★ Milestone recorded: Diamond ×5 at playtime \d+h \d{2}m \d{2}s$/, scanMs);
    assertFallback(hit, 'message.itemmilestonelog.recorded');

    const t5From = session.messages.length;
    await say(session, '/milestonelog targets');
    await waitFor(session, t5From, /^✔ Diamond ×5 — \d+h \d{2}m \d{2}s$/);

    await rcon.command(`clear ${BOT_NAME} minecraft:diamond`);
    await command(session, 'milestonelog reset minecraft:diamond 5', /^Record cleared: Diamond ×5/);
    await command(session, 'milestonelog remove minecraft:diamond 5', /^Stopped tracking: Diamond ×5$/);
  });

  await check('Quantity: coexisting x1 and x100 targets are both recorded when 100 are obtained at once', async () => {
    await command(session, 'milestonelog add minecraft:emerald', /^Now tracking: Emerald$/);
    await command(session, 'milestonelog add minecraft:emerald 100', /^Now tracking: Emerald ×100$/);

    const from = session.messages.length;
    await rcon.command(`give ${BOT_NAME} minecraft:emerald 100`);
    await waitFor(session, from, /^★ Milestone recorded: Emerald at playtime /, scanMs);
    await waitFor(session, from, /^★ Milestone recorded: Emerald ×100 at playtime /, scanMs);

    const lFrom = session.messages.length;
    await say(session, '/milestonelog list');
    await waitFor(session, lFrom, /^✔ Emerald — \d+h \d{2}m \d{2}s \(Day \d+, \d{4}-\d{2}-\d{2}\)$/);
    await waitFor(session, lFrom, /^✔ Emerald ×100 — \d+h \d{2}m \d{2}s \(Day \d+, \d{4}-\d{2}-\d{2}\)$/);

    const fFrom = session.messages.length;
    await say(session, '/milestonelog firsts');
    await waitFor(session, fFrom, /^★ Emerald ×100 — ImlBot — \d+h \d{2}m \d{2}s \(Day \d+, \d{4}-\d{2}-\d{2}\)$/);

    await rcon.command(`clear ${BOT_NAME} minecraft:emerald`);
    await command(session, 'milestonelog reset minecraft:emerald', /^Record cleared: Emerald/);
    await command(session, 'milestonelog reset minecraft:emerald 100', /^Record cleared: Emerald ×100/);
    await command(session, 'milestonelog remove minecraft:emerald', /^Stopped tracking: Emerald$/);
    await command(session, 'milestonelog remove minecraft:emerald 100', /^Stopped tracking: Emerald ×100$/);
  });
}

async function creativeModeFlow(session, rcon) {
  // Waiting for a record made by the scan: one scan interval, plus time for the message.
  const scanMs = (SCAN_SECONDS + 5) * 1000;
  // Waiting for nothing to be recorded: one full scan interval plus 2 s, so at least one
  // scan runs in the meantime (the scan runs every SCAN_SECONDS for each player).
  const oneScanMs = (SCAN_SECONDS + 2) * 1000;
  await check('creative mode: targets for the checks below', async () => {
    await command(session, 'milestonelog add minecraft:gold_ingot', /^Now tracking: Gold Ingot$/);
    await command(session, 'milestonelog add minecraft:iron_ingot', /^Now tracking: Iron Ingot$/);
    await command(session, 'milestonelog add minecraft:copper_ingot', /^Now tracking: Copper Ingot$/);
  });
  await check('creative mode: nothing is recorded, by pickup or by the scan', async () => {
    await rcon.command(`gamemode creative ${BOT_NAME}`);
    const from = session.messages.length;
    await summonAndPickUp(rcon, 'minecraft:gold_ingot');
    await sleep(oneScanMs);
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
    await rcon.command(`give ${BOT_NAME} minecraft:copper_ingot 1`);
    await waitFor(session, from, /^★ Milestone recorded: Copper Ingot at playtime /, scanMs);
    from = session.messages.length;
    await summonAndPickUp(rcon, 'minecraft:gold_ingot');
    await waitFor(session, from, /^★ Milestone recorded: Gold Ingot at playtime /, 4000);
  });
}

async function multiPlayerAndPresetManagementFlow(session, rcon) {
  const bot2 = await joinBot('ImlBot2');
  await rcon.command(`execute at ${BOT_NAME} run tp ImlBot2 ~6 ~ ~`);
  await sleep(500);

  await check('F9: non-OP player is denied /milestonelog admin', async () => {
    const from = bot2.messages.length;
    await say(bot2, '/milestonelog admin players');
    await waitFor(bot2, from, /Unknown or incomplete command|incorrect|permission/i);
  });

  await check('Custom preset: non-OP player is denied preset reload/save/delete without falling back to apply', async () => {
    await command(bot2, 'milestonelog preset reload',
      /^You do not have permission to manage presets on this server\.$/);
    await command(bot2, 'milestonelog preset save test_denied',
      /^You do not have permission to manage presets on this server\.$/);
    await command(bot2, 'milestonelog preset delete ci_custom',
      /^You do not have permission to manage presets on this server\.$/);
  });

  await check('F4: second player obtaining cobblestone does not overwrite world first', async () => {
    await command(bot2, 'milestonelog add minecraft:cobblestone', /^Now tracking: Cobblestone$/);
    const from = bot2.messages.length;
    await summonAndPickUp(rcon, 'minecraft:cobblestone', 'ImlBot2');
    await waitFor(bot2, from, /^★ Milestone recorded: Cobblestone at playtime /, 4000);

    const fFrom = bot2.messages.length;
    await say(bot2, '/milestonelog firsts');
    await waitFor(bot2, fFrom, /^World first milestones \(\d+\):$/);
    await waitFor(bot2, fFrom, /^★ Cobblestone — ImlBot — \d+h \d{2}m \d{2}s \(Day \d+, \d{4}-\d{2}-\d{2}\)$/);
  });

  await check('F11: mutebroadcast stops broadcasts from other players', async () => {
    await command(session, 'milestonelog add minecraft:coal', /^Now tracking: Coal$/);
    await command(session, 'milestonelog add minecraft:charcoal', /^Now tracking: Charcoal$/);

    let bFrom = bot2.messages.length;
    await summonAndPickUp(rcon, 'minecraft:coal', BOT_NAME);
    await waitFor(bot2, bFrom, /^★ ImlBot recorded Coal at playtime /, 5000);

    await command(bot2, 'milestonelog mutebroadcast', /^Milestone broadcasts from other players are now muted\.$/);

    bFrom = bot2.messages.length;
    const sFrom = session.messages.length;
    await summonAndPickUp(rcon, 'minecraft:charcoal', BOT_NAME);
    // A broadcast would go out together with ImlBot's own notice.
    await waitFor(session, sFrom, /^★ Milestone recorded: Charcoal at playtime /, 4000);
    await sleep(1000);
    const leaked = bot2.messages.slice(bFrom).find((m) => /recorded Charcoal/.test(m.text));
    if (leaked) throw new Error(`muted player still received broadcast: ${leaked.text}`);

    await rcon.command(`clear ${BOT_NAME} minecraft:coal`);
    await rcon.command(`clear ${BOT_NAME} minecraft:charcoal`);
    await command(session, 'milestonelog reset minecraft:coal', /^Record cleared: Coal/);
    await command(session, 'milestonelog reset minecraft:charcoal', /^Record cleared: Charcoal/);
    await command(session, 'milestonelog remove minecraft:coal', /^Stopped tracking: Coal$/);
    await command(session, 'milestonelog remove minecraft:charcoal', /^Stopped tracking: Charcoal$/);
  });

  await check('F6: non-OP player cannot import another player\'s export file', async () => {
    await command(session, 'milestonelog export',
      /^Exported your milestones to itemmilestonelog\/exports\/ImlBot\.json$/);
    await command(bot2, 'milestonelog import ImlBot.json',
      /^Cannot import ImlBot\.json: this export belongs to another player\.$/);
  });

  await check('Custom preset: OP can save current targets to JSON and delete it', async () => {
    await rcon.command('op ImlBot2');
    await sleep(1000);
    await command(bot2, 'milestonelog preset save bot_saved', /^Saved preset 'bot_saved' with 1 target\(s\)\.$/);
    if (opt['server-dir']) {
      const savedPath = path.join(opt['server-dir'], 'config', 'itemmilestonelog', 'presets', 'bot_saved.json');
      if (!fs.existsSync(savedPath)) {
        throw new Error(`expected saved preset file at ${savedPath}`);
      }
      const parsed = JSON.parse(fs.readFileSync(savedPath, 'utf8'));
      if (JSON.stringify(parsed.items) !== JSON.stringify(['minecraft:cobblestone'])) {
        throw new Error(`unexpected preset items: ${JSON.stringify(parsed)}`);
      }
    }
    await command(bot2, 'milestonelog preset delete bot_saved', /^Deleted preset 'bot_saved'\.$/);
    if (opt['server-dir']) {
      const savedPath = path.join(opt['server-dir'], 'config', 'itemmilestonelog', 'presets', 'bot_saved.json');
      if (fs.existsSync(savedPath)) {
        throw new Error(`expected deleted preset file to be gone: ${savedPath}`);
      }
    }
  });

  bot2.bot.quit();
  await sleep(1000);
}

async function restartBotFlow(rcon) {
  const session = await joinBot(BOT_NAME);

  await check('F10: first-join tip is NOT shown again on rejoin', async () => {
    await sleep(1000);
    const hit = session.messages.find((m) => /^Tip: Use \/milestonelog/.test(m.text));
    if (hit) {
      throw new Error(`join hint appeared again on rejoin: ${hit.text}`);
    }
  });

  await check('targets and records survive a server restart', async () => {
    const from = session.messages.length;
    await say(session, '/milestonelog targets');
    await waitFor(session, from, /^Target items \(12\):$/);
    await waitFor(session, from, /^✔ Cobblestone — \d+h \d{2}m \d{2}s$/);
    await waitFor(session, from, /^✔ Gold Ingot — \d+h \d{2}m \d{2}s$/);
    await waitFor(session, from, /^… Dirt — not yet obtained$/);
  });

  await check('F6: export -> resetall -> import restores all records', async () => {
    await command(session, 'milestonelog export',
      /^Exported your milestones to itemmilestonelog\/exports\/ImlBot\.json$/);
    await command(session, 'milestonelog resetall', /^Cleared 4 records\. Targets were kept\.$/);
    await command(session, 'milestonelog import ImlBot.json',
      /^Imported from ImlBot\.json: 0 target\(s\) added, 4 record\(s\) updated\.$/);
    await command(session, 'milestonelog list', /^Recorded milestones \(4\), earliest first:$/);
  });

  await check('/milestonelog resetall after import', async () => {
    await command(session, 'milestonelog resetall', /^Cleared 4 records\. Targets were kept\.$/);
  });

  await check('F9: OP can list, reset, and purge another offline player', async () => {
    await rcon.command(`op ${BOT_NAME}`);
    await sleep(1000);
    const lFrom = session.messages.length;
    await say(session, '/milestonelog admin ImlBot2 list');
    await waitFor(session, lFrom, /^Recorded milestones for ImlBot2 \(1\), earliest first:$/);
    await command(session, 'milestonelog admin ImlBot2 reset minecraft:cobblestone',
      /^Cleared ImlBot2's record for Cobblestone\.$/);
    await command(session, 'milestonelog admin ImlBot2 purge',
      /^Purged all milestone data for ImlBot2 /);
    await command(session, 'milestonelog admin ImlBot2 list',
      /^No milestone data found for player: ImlBot2$/);
  });

  session.bot.quit();
  await sleep(1000);
}

async function migrateBeforeFlow(rcon) {
  const scanMs = (SCAN_SECONDS + 5) * 1000;
  const session = await joinBot(BOT_NAME);
  await check('1.21.1: a record to carry over', async () => {
    await command(session, 'milestonelog add minecraft:cobblestone', /^Now tracking: Cobblestone$/);
    await command(session, 'milestonelog add minecraft:dirt', /^Now tracking: Dirt$/);
    await command(session, 'milestonelog add minecraft:iron_nugget 5', /^Now tracking: Iron Nugget ×5$/);
    const from = session.messages.length;
    await summonAndPickUp(rcon, 'minecraft:cobblestone');
    await waitFor(session, from, /^★ Milestone recorded: Cobblestone at playtime /, 4000);
    const qFrom = session.messages.length;
    await rcon.command(`give ${BOT_NAME} minecraft:iron_nugget 5`);
    await waitFor(session, qFrom, /^★ Milestone recorded: Iron Nugget ×5 at playtime /, scanMs);
  });
  session.bot.quit();
  await sleep(1000);
}

async function migrateAfterFlow() {
  const session = await joinBot(BOT_NAME);
  await check('26.1.2: the targets, record, and worldFirst made by 1.21.1 are there', async () => {
    const from = session.messages.length;
    await say(session, '/milestonelog targets');
    await waitFor(session, from, /^Target items \(3\):$/);
    await waitFor(session, from, /^✔ Cobblestone — \d+h \d{2}m \d{2}s$/);
    await waitFor(session, from, /^✔ Iron Nugget ×5 — \d+h \d{2}m \d{2}s$/);
    await waitFor(session, from, /^… Dirt — not yet obtained$/);

    const lFrom = session.messages.length;
    await say(session, '/milestonelog list');
    await waitFor(session, lFrom, /^✔ Cobblestone — \d+h \d{2}m \d{2}s \(Day \d+, \d{4}-\d{2}-\d{2}\)$/);
    await waitFor(session, lFrom, /^✔ Iron Nugget ×5 — \d+h \d{2}m \d{2}s \(Day \d+, \d{4}-\d{2}-\d{2}\)$/);

    const fFrom = session.messages.length;
    await say(session, '/milestonelog firsts');
    await waitFor(session, fFrom, /^★ Cobblestone — ImlBot — \d+h \d{2}m \d{2}s \(Day \d+, \d{4}-\d{2}-\d{2}\)$/);
    await waitFor(session, fFrom, /^★ Iron Nugget ×5 — ImlBot — \d+h \d{2}m \d{2}s \(Day \d+, \d{4}-\d{2}-\d{2}\)$/);
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
    await restartBotFlow(rcon);
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

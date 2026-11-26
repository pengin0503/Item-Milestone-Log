#!/usr/bin/env node
// Tests-only helper for run-migration-test.sh (F-140-1 / #122 item 5).
// Prints the root `data.schemaVersion` of a Minecraft SavedData .dat file and, when an expected
// value is supplied as the second argument, fails if it differs. Handles both gzip-compressed and
// uncompressed NBT; no npm dependencies (Node 22's built-in zlib is enough).
//
//   node check-schema-version.mjs <file.dat> [expected]

import { readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';

const [, , file, expectedArg] = process.argv;
if (!file) {
  console.error('usage: check-schema-version.mjs <file.dat> [expected]');
  process.exit(2);
}

const raw = readFileSync(file);
const bytes = raw.length >= 2 && raw[0] === 0x1f && raw[1] === 0x8b ? gunzipSync(raw) : raw;
let pos = 0;

function u1() {
  if (pos >= bytes.length) throw new Error('unexpected end of NBT stream');
  return bytes.readUInt8(pos++);
}

function i4() {
  const value = bytes.readInt32BE(pos);
  pos += 4;
  return value;
}

function str() {
  const len = bytes.readUInt16BE(pos);
  pos += 2;
  const value = bytes.toString('utf8', pos, pos + len);
  pos += len;
  return value;
}

function skip(type) {
  switch (type) {
    case 0: return;
    case 1: pos += 1; return;
    case 2: pos += 2; return;
    case 3: pos += 4; return;
    case 4: pos += 8; return;
    case 5: pos += 4; return;
    case 6: pos += 8; return;
    case 7: pos += 4 + i4(); return;
    case 8: str(); return;
    case 9: {
      const itemType = u1();
      const count = i4();
      for (let i = 0; i < count; i++) skip(itemType);
      return;
    }
    case 10:
      for (;;) {
        const childType = u1();
        if (childType === 0) return;
        str();
        skip(childType);
      }
    case 11: pos += 4 + 4 * i4(); return;
    case 12: pos += 4 + 8 * i4(); return;
    default: throw new Error('unknown NBT tag type ' + type);
  }
}

// Read a compound into a shallow map; only scalar ints and nested compounds carry values we use.
function compound() {
  const out = {};
  for (;;) {
    const type = u1();
    if (type === 0) return out;
    const name = str();
    out[name] = value(type);
  }
}

function value(type) {
  switch (type) {
    case 3: return i4();
    case 10: return compound();
    case 8: return str();
    default: skip(type); return undefined;
  }
}

if (u1() !== 10) throw new Error('NBT root is not a compound tag');
str(); // root name
const root = compound();
const version = root?.data?.schemaVersion;
if (typeof version !== 'number') {
  console.error(`FAIL  ${file}: no root data.schemaVersion int (found ${JSON.stringify(Object.keys(root?.data ?? {}))})`);
  process.exit(1);
}
console.log(`schemaVersion=${version}`);
if (expectedArg !== undefined && version !== Number(expectedArg)) {
  console.error(`FAIL  ${file}: expected schemaVersion=${expectedArg}, found ${version}`);
  process.exit(1);
}

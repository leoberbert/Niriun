#!/usr/bin/env node
// Minimal stand-in for the PC client, to exercise the phone side without the official desktop app.
//
//   adb forward tcp:4699 tcp:4699
//   node tools/pc-sim/pc-sim.js [--host 127.0.0.1] [--port 4699] [--seconds 10] [--out out.h264]
//                               [--size 1280x720] [--fps 30] [--codec h264|hevc] [--audio]
//                               [--passcode secret] [--no-ack] [--control zoom=50,torch,mirror,next-camera]
//
// It speaks the protocol documented in docs/PROTOCOL.md: reads hello/sizes/cameras, sends peer info and START,
// acknowledges every video packet and writes the raw elementary stream to --out.

const net = require('net');
const fs = require('fs');

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, all) => {
    if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : true]);
    return acc;
  }, []),
);
const host = args.host || '127.0.0.1';
const port = Number(args.port || 4699);
const seconds = Number(args.seconds || 10);
const outPath = args.out || 'out.h264';
const [reqW, reqH] = (args.size || '1280x720').split('x').map(Number);
const fps = Number(args.fps || 30);
const codec = args.codec === 'hevc' ? 2 : 1;
const wantAudio = Boolean(args.audio);
const noAck = Boolean(args['no-ack']);

const T = { HELLO: 1, SYNC: 4, AUDIO: 7, VIDEO: 8, ACK: 9, SIZES: 0x0a, START: 0x0b, CONTROL: 0x10, PEER: 0x11, AUTH: 0x17, CAMERAS: 0x18 };

function header(type, total) {
  const b = Buffer.alloc(4);
  b.writeUInt32LE(((type << 24) | (total & 0xffffff)) >>> 0);
  return b;
}
function packet(type, body) {
  return Buffer.concat([header(type, 4 + body.length), body]);
}
function fnv1a64(text) {
  if (!text) return 0n;
  let h = 0xcbf29ce484222325n;
  for (const byte of Buffer.from(text, 'utf8')) {
    h ^= BigInt(byte);
    h = (h * 0x100000001b3n) & 0xffffffffffffffffn;
  }
  return h;
}

const stats = { video: 0, videoBytes: 0, audio: 0, audioBytes: 0, config: 0, keyframes: 0, firstTs: null, lastTs: null };
const gaps = [];
let lastArrival = null;
const out = fs.createWriteStream(outPath);
let pending = Buffer.alloc(0);
let started = false;
let sizes = [];
let cameraNames = [];

const sock = net.connect({ host, port }, () => console.log(`connected to ${host}:${port}`));
sock.setNoDelay(true);

function sendStart() {
  if (started) return;
  started = true;
  const mode = sizes.find((m) => m.w === reqW && m.h === reqH && m.fps === fps) || sizes[0];
  const w = mode ? mode.w : reqW;
  const h = mode ? mode.h : reqH;
  console.log(`START ${w}x${h}@${fps} codec=${codec} audio=${wantAudio}`);
  const body = Buffer.alloc(20);
  body.writeUInt32LE(wantAudio ? 1 : 0, 0);
  body.writeUInt32LE(codec, 4);
  body.writeUInt32LE(w, 8);
  body.writeUInt32LE(h, 12);
  body.writeFloatLE(fps, 16);
  sock.write(packet(T.START, body));
  scheduleControls();
}

function scheduleControls() {
  if (!args.control || args.control === true) return;
  args.control.split(',').forEach((c, i) => {
    setTimeout(() => {
      const [name, value] = c.split('=');
      const body = Buffer.alloc(8);
      const ids = { 'lock': 1, torch: 2, mirror: 3, zoom: 4, 'next-camera': 5, ev: 8, wb: 9, camera: 14 };
      body.writeUInt32LE(ids[name], 0);
      if (name === 'ev') body.writeFloatLE(Number(value), 4);
      else body.writeInt32LE(Number(value || 0), 4);
      console.log(`CONTROL ${c}`);
      sock.write(packet(T.CONTROL, body));
    }, 1500 + i * 700);
  });
}

function handle(type, data) {
  switch (type) {
    case T.HELLO: {
      const id = data.subarray(4, 20).toString('hex');
      const kind = data.readUInt16LE(20);
      const version = data.readUInt16LE(22);
      const name = data.subarray(24, 280).toString('utf8').replace(/\0.*$/s, '');
      console.log(`HELLO id=${id} kind=${kind} version=${version} name="${name}"`);
      break;
    }
    case T.SIZES: {
      const flags = data.readUInt32LE(4);
      const n = data.readUInt32LE(8);
      sizes = [];
      for (let i = 0; i < n; i++) {
        const o = 12 + i * 12;
        sizes.push({ w: data.readInt32LE(o), h: data.readInt32LE(o + 4), fps: data.readFloatLE(o + 8) });
      }
      console.log(`SIZES flags=${flags} n=${n}: ${sizes.map((s) => `${s.w}x${s.h}@${s.fps}`).join(' ')}`);
      break;
    }
    case T.CAMERAS: {
      const n = data.readUInt32LE(4);
      const sel = data.readUInt32LE(8);
      cameraNames = [];
      for (let i = 0; i < n; i++) cameraNames.push(data.subarray(12 + i * 256, 12 + (i + 1) * 256).toString('utf8').replace(/\0.*$/s, ''));
      console.log(`CAMERAS selected=${sel}: ${cameraNames.join(' | ')}`);
      break;
    }
    case T.AUTH: {
      const nonce = data.readBigUInt64LE(8);
      const reply = nonce ^ fnv1a64(args.passcode === true ? '' : args.passcode || '');
      const body = Buffer.alloc(12);
      body.writeBigUInt64LE(reply, 4);
      console.log(`AUTH challenge ${nonce.toString(16)} -> ${args.passcode ? 'answering' : 'answering with empty passcode'}`);
      sock.write(packet(T.AUTH, body));
      break;
    }
    case T.VIDEO: {
      const ts = data.readFloatLE(4);
      const payload = data.subarray(8);
      stats.video++;
      {
        const now = process.hrtime.bigint();
        if (lastArrival !== null) gaps.push(Number(now - lastArrival) / 1e6);
        lastArrival = now;
      }
      stats.videoBytes += payload.length;
      if (stats.firstTs === null) stats.firstTs = ts;
      stats.lastTs = ts;
      // Annex-B NAL types: 7=SPS 8=PPS 5=IDR (H.264); 32/33/34=VPS/SPS/PPS 19/20=IDR (HEVC)
      const nal = payload[4] & (codec === 2 ? 0x7e : 0x1f);
      const t = codec === 2 ? nal >> 1 : nal;
      if (ts === 0) stats.config++;
      if (codec === 1 ? t === 5 : t === 19 || t === 20) stats.keyframes++;
      out.write(payload);
      if (!noAck) {
        const body = Buffer.alloc(12);
        body.writeFloatLE(ts, 0);
        sock.write(packet(T.ACK, body));
      }
      break;
    }
    case T.AUDIO:
      if (stats.audio === 0) console.log(`first audio packet: reserved=${data.readUInt32LE(4)} opus=${data.subarray(8, 24).toString('hex')} (len ${data.length - 8})`);
      stats.audio++;
      stats.audioBytes += data.length - 8;
      break;
    default:
      console.log(`packet type 0x${type.toString(16)} len ${data.length}`);
  }
}

sock.on('data', (chunk) => {
  pending = Buffer.concat([pending, chunk]);
  while (pending.length >= 4) {
    const h = pending.readUInt32LE(0);
    const len = h & 0xffffff;
    const type = h >>> 24;
    if (len < 4) throw new Error(`bad length ${len}`);
    if (pending.length < len) return;
    const data = pending.subarray(0, len);
    pending = pending.subarray(len);
    handle(type, data);
    if (type === T.CAMERAS) sendStart(); // the phone announces hello, sizes, cameras (in that order)
  }
});

// Peer info (type 0x11): PC type=1 (Windows), version=1. Sent right away like the real client.
sock.write(packet(T.PEER, Buffer.from([1, 0, 0, 0, 1, 0, 0, 0])));

// Keep-alive: the phone drops peers that are silent for 10 s. Type 1 is ignored by the phone (type 4 would reset its clock).
const keepAlive = setInterval(() => sock.write(packet(T.HELLO, Buffer.alloc(0))), 3000);

sock.on('error', (e) => {
  console.error('socket error', e.message);
  process.exitCode = 1;
});
sock.on('close', () => {
  console.log('connection closed by the phone');
  finish();
});

if (args.verbose) {
  let lastVideo = 0;
  let lastAudio = 0;
  setInterval(() => {
    console.log(`  +1s: video ${stats.video - lastVideo} (ts ${stats.lastTs === null ? '-' : stats.lastTs.toFixed(2)}), audio ${stats.audio - lastAudio}`);
    lastVideo = stats.video;
    lastAudio = stats.audio;
  }, 1000).unref();
}

setTimeout(() => {
  sock.end();
}, seconds * 1000);

let done = false;
function finish() {
  if (done) return;
  done = true;
  clearInterval(keepAlive);
  out.end();
  const dur = stats.lastTs !== null ? stats.lastTs - stats.firstTs : 0;
  gaps.sort((a, b) => a - b);
  const pct = (p) => (gaps.length ? gaps[Math.min(gaps.length - 1, Math.floor(gaps.length * p))] : 0);
  const gapStats = {
    p50: Number(pct(0.5).toFixed(1)), p95: Number(pct(0.95).toFixed(1)), p99: Number(pct(0.99).toFixed(1)),
    max: Number((gaps[gaps.length - 1] || 0).toFixed(1)),
    over100: gaps.filter((g) => g > 100).length, over200: gaps.filter((g) => g > 200).length, over500: gaps.filter((g) => g > 500).length,
  };
  console.log(JSON.stringify({ ...stats, durationSeconds: Number(dur.toFixed(2)), avgFps: dur > 0 ? Number((stats.video / dur).toFixed(1)) : 0, kbps: dur > 0 ? Math.round((stats.videoBytes * 8) / dur / 1000) : 0, arrivalGapMs: gapStats }, null, 2));
}

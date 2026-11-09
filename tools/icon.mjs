// 生成一个简单的启动图标 PNG（避免依赖外部图片库）
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const S = 192;
const px = Buffer.alloc(S * S * 4);

function set(x, y, r, g, b, a = 255) {
  if (x < 0 || y < 0 || x >= S || y >= S) return;
  const i = (y * S + x) * 4;
  px[i] = r; px[i + 1] = g; px[i + 2] = b; px[i + 3] = a;
}

// 圆角矩形背景
const R = 42;
for (let y = 0; y < S; y++) {
  for (let x = 0; x < S; x++) {
    const cx = Math.min(Math.max(x, R), S - 1 - R);
    const cy = Math.min(Math.max(y, R), S - 1 - R);
    const d = Math.hypot(x - cx, y - cy);
    if (d <= R) set(x, y, 0x14, 0x1b, 0x2b, 255);
  }
}
// 人脸轮廓圆
const ccx = S / 2, ccy = S * 0.46, cr = S * 0.235;
for (let y = 0; y < S; y++) {
  for (let x = 0; x < S; x++) {
    const d = Math.hypot(x - ccx, y - ccy);
    if (Math.abs(d - cr) < 6) set(x, y, 0x3f, 0x8d, 0xff, 255);
    else if (d < cr) {
      const t = 1 - d / cr;
      set(x, y, 0x18 + 20 * t, 0x24 + 40 * t, 0x3c + 70 * t, 255);
    }
  }
}
// 两只眼睛 + 嘴（活体/人脸语义）
for (let y = 0; y < S; y++) {
  for (let x = 0; x < S; x++) {
    const e1 = Math.hypot(x - (ccx - cr * 0.36), y - (ccy - cr * 0.18));
    const e2 = Math.hypot(x - (ccx + cr * 0.36), y - (ccy - cr * 0.18));
    if (e1 < 7 || e2 < 7) set(x, y, 0xe9, 0xf2, 0xff, 255);
    const m = Math.hypot((x - ccx) / (cr * 0.42), (y - (ccy + cr * 0.35)) / (cr * 0.24));
    if (m > 0.82 && m < 1.0) set(x, y, 0x5f, 0xd4, 0xa0, 255);
  }
}

// PNG 编码
const raw = Buffer.alloc(S * (S * 4 + 1));
for (let y = 0; y < S; y++) {
  raw[y * (S * 4 + 1)] = 0;
  px.copy(raw, y * (S * 4 + 1) + 1, y * S * 4, (y + 1) * S * 4);
}
function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(td) >>> 0);
  return Buffer.concat([len, td, crc]);
}
let table = null;
function crc32(buf) {
  if (!table) {
    table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      table[n] = c;
    }
  }
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = table[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return c ^ -1;
}
const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(S, 0); ihdr.writeUInt32BE(S, 4);
ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
const png = Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
const out = process.argv[2];
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, png);
console.log('icon written', out, png.length, 'bytes');

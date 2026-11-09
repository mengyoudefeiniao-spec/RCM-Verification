// Minimal robust downloader: node tools/fetch.mjs <url> <outfile>
import fs from 'node:fs';
import path from 'node:path';

const [url, out] = process.argv.slice(2);
if (!url || !out) { console.error('usage: fetch.mjs <url> <outfile>'); process.exit(2); }

const dir = path.dirname(out);
fs.mkdirSync(dir, { recursive: true });

const MAX_TRIES = 3;
for (let attempt = 1; attempt <= MAX_TRIES; attempt++) {
  let start = 0;
  if (fs.existsSync(out)) start = fs.statSync(out).size;
  const headers = { 'accept-encoding': 'identity' };
  if (start > 0) headers.range = `bytes=${start}-`;
  try {
    const res = await fetch(url, { headers, redirect: 'follow' });
    if (res.status === 416) { console.log(`already complete: ${out}`); process.exit(0); }
    if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`);
    if (start > 0 && res.status !== 206) start = 0; // server ignored range
    const total = Number(res.headers.get('content-length') || 0) + start;
    const fh = await fs.promises.open(out, start > 0 ? 'r+' : 'w');
    if (start > 0) await fh.truncate(start);
    let done = start;
    let last = 0;
    const reader = res.body.getReader();
    for (;;) {
      const { done: fin, value } = await reader.read();
      if (fin) break;
      await fh.write(value, 0, value.length, done);
      done += value.length;
      const now = Date.now();
      if (now - last > 1000) {
        last = now;
        const pct = total ? ((done / total) * 100).toFixed(1) : '?';
        process.stdout.write(`\r${path.basename(out)}  ${(done / 1048576).toFixed(1)}MB / ${total ? (total / 1048576).toFixed(1) + 'MB' : '?'}  ${pct}%`);
      }
    }
    await fh.close();
    const size = fs.statSync(out).size;
    if (total && size !== total) throw new Error(`size mismatch ${size} != ${total}`);
    process.stdout.write(`\r${path.basename(out)}  ${(size / 1048576).toFixed(2)}MB  DONE\n`);
    process.exit(0);
  } catch (e) {
    console.error(`\n[attempt ${attempt}/${MAX_TRIES}] ${e.message}`);
    if (attempt === MAX_TRIES) process.exit(1);
    await new Promise(r => setTimeout(r, 2000 * attempt));
  }
}

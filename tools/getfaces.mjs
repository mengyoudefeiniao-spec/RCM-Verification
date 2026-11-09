import fs from 'node:fs';
import path from 'node:path';
const base = 'https://hf-mirror.com';
const repo = '0xmoose/lfw-sample';
const outDir = path.resolve('dl/faces');
fs.mkdirSync(outDir, { recursive: true });

const list = await (await fetch(`${base}/api/datasets/${repo}/tree/main/data`, { headers: { accept: 'application/json' } })).json();
// 取 24 个不同身份
const pick = list.filter(f => f.type === 'file' && f.path.endsWith('.jpg')).slice(0, 24);
for (const f of pick) {
  const name = path.basename(f.path);
  const dest = path.join(outDir, name);
  if (fs.existsSync(dest)) continue;
  const r = await fetch(`${base}/datasets/${repo}/resolve/main/${f.path}`, { redirect: 'follow' });
  if (!r.ok) { console.log('fail', name, r.status); continue; }
  fs.writeFileSync(dest, Buffer.from(await r.arrayBuffer()));
  process.stdout.write('.');
}
console.log('\ndone', fs.readdirSync(outDir).length, 'files');

// Download Maven artifacts (aar/jar/pom) by coordinates: node tools/maven.mjs <repo> <g:a:v:packaging> ...
import fs from 'node:fs';
import path from 'node:path';

const [repo, ...arts] = process.argv.slice(2);
const OUT = path.resolve('dl/maven');

function coordPath(g, a, v, ext) {
  return `${g.replace(/\./g, '/')}/${a}/${v}/${a}-${v}.${ext}`;
}

let fail = 0;
for (const spec of arts) {
  const [g, a, v, ext] = spec.split(':');
  const rel = coordPath(g, a, v, ext);
  const out = path.join(OUT, rel);
  if (fs.existsSync(out) && fs.statSync(out).size > 0) { console.log(`skip ${a}-${v}.${ext}`); continue; }
  fs.mkdirSync(path.dirname(out), { recursive: true });
  const url = repo.replace(/\/$/, '') + '/' + rel;
  let ok = false;
  for (let i = 0; i < 3 && !ok; i++) {
    try {
      const r = await fetch(url, { redirect: 'follow' });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      const buf = Buffer.from(await r.arrayBuffer());
      fs.writeFileSync(out, buf);
      console.log(`ok   ${a}-${v}.${ext}  ${(buf.length / 1048576).toFixed(1)}MB`);
      ok = true;
    } catch (e) {
      console.log(`retry ${a}-${v}.${ext}: ${e.message}`);
      await new Promise(r => setTimeout(r, 1500 * (i + 1)));
    }
  }
  if (!ok) fail++;
}
process.exit(fail ? 1 : 0);

import fs from 'node:fs';
import path from 'node:path';

const base = 'https://hf-mirror.com';
const jobs = [
  ['menglaoda/_insightface', 'w600k_r50.onnx', 'models/w600k_r50.onnx'],
  ['menglaoda/_insightface', 'det_10g.onnx', 'models/det_10g.onnx'],
  ['garciafido/minifasnet-v2-anti-spoofing-onnx', 'minifasnet_v2.onnx', 'models/minifasnet_v2.onnx'],
];

for (const [repo, file, out] of jobs) {
  const url = `${base}/${repo}/resolve/main/${file}`;
  const dest = path.resolve(out);
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  let ok = false;
  for (let attempt = 1; attempt <= 3 && !ok; attempt++) {
    try {
      const r = await fetch(url, { redirect: 'follow', headers: { 'accept-encoding': 'identity' } });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      const total = Number(r.headers.get('content-length') || 0);
      const fh = await fs.promises.open(dest, 'w');
      let done = 0, last = 0;
      for await (const chunk of r.body) {
        await fh.write(chunk);
        done += chunk.length;
        if (Date.now() - last > 1500) { last = Date.now(); process.stdout.write(`\r${file} ${(done / 1048576).toFixed(1)}/${(total / 1048576).toFixed(1)}MB`); }
      }
      await fh.close();
      process.stdout.write(`\r${file} ${(done / 1048576).toFixed(2)}MB DONE\n`);
      ok = true;
    } catch (e) {
      console.log(`\n${file} attempt ${attempt} failed: ${e.message}`);
      await new Promise(r => setTimeout(r, 2000));
    }
  }
}

import fs from 'node:fs';
import path from 'node:path';
import * as h from 'hyparquet';

// FairFace 种族类别顺序（官方 README）
const RACES = ['White', 'Black', 'Indian', 'EastAsian', 'SEAsian', 'MiddleEastern', 'Latino'];
const N_PER_RACE = Number(process.argv[2] || 350);

const file = await h.asyncBufferFromFile('dl/fairface_val.parquet');
const meta = await h.parquetMetadataAsync(file);
const rows = await h.parquetReadObjects({ file, metadata: meta, utf8: false });

const out = 'dl/ff';
fs.mkdirSync(out, { recursive: true });
const count = {};

for (const r of rows) {
  const race = Number(r.race);
  const age = Number(r.age);
  if (age < 3) continue;                 // 只要成年人（20 岁以上）
  const name = RACES[race];
  if (!name) continue;
  count[name] = count[name] || 0;
  if (count[name] >= N_PER_RACE) continue;
  const dir = path.join(out, name);
  fs.mkdirSync(dir, { recursive: true });
  const bytes = r.image.bytes;
  fs.writeFileSync(path.join(dir, String(count[name]).padStart(4, '0') + '.jpg'), Buffer.from(bytes));
  count[name]++;
}

console.log('extracted:', count);

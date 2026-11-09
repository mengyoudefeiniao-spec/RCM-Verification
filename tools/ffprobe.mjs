import fs from 'node:fs';
import path from 'node:path';
import * as h from 'hyparquet';

const file = await h.asyncBufferFromFile('dl/fairface_val.parquet');
const meta = await h.parquetMetadataAsync(file);
console.log('rows:', Number(meta.num_rows));
console.log('columns:', meta.schema.map(s => s.name + ':' + s.type).join(', '));

const read = h.parquetReadObjects || h.parquetQuery;
const rows = await read({ file, metadata: meta });
console.log('loaded rows:', rows.length);
console.log('first row keys:', Object.keys(rows[0]));

const r0 = rows[0];
for (const k of Object.keys(r0)) {
  const v = r0[k];
  if (v && typeof v === 'object' && !Buffer.isBuffer(v)) console.log(' ', k, '->', Object.keys(v), v.bytes ? ('bytes ' + v.bytes.length) : '');
  else console.log(' ', k, '=', String(v).slice(0, 40));
}

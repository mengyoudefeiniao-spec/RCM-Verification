const base = 'https://hf-mirror.com';
const repos = [
  'immich-app/buffalo_l',
  'public-data/insightface',
  'ddrfan/insightface',
  'menglaoda/_insightface',
  'onnx-community/arcface-onnx',
  'garavv/arcface-onnx',
  'HammadNaseer/arcface-glintr100-onnx',
  'kurob1993/MiniFASNetV2',
  'garciafido/minifasnet-v2-anti-spoofing-onnx',
  'rikurunico/minifasnet-onnx',
  'sergey21000/onnx-models-zoo-face-recognition',
];
for (const r of repos) {
  try {
    const res = await fetch(`${base}/api/models/${r}/tree/main`, { headers: { accept: 'application/json' } });
    if (!res.ok) { console.log(`\n### ${r} -> ${res.status}`); continue; }
    const files = await res.json();
    console.log(`\n### ${r}`);
    for (const f of files) console.log(`   ${f.type === 'directory' ? '[D]' : '   '} ${f.path}  ${f.size ? (f.size / 1048576).toFixed(2) + 'MB' : ''}`);
  } catch (e) {
    console.log(`\n### ${r} ERR ${e.message}`);
  }
}

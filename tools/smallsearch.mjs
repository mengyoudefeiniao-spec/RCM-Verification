const base = 'https://hf-mirror.com';
const qs = ['insightface quantized', 'arcface int8', 'w600k', 'mobilefacenet onnx', 'arcface mobilefacenet', 'buffalo_s'];
for (const q of qs) {
  try {
    const r = await fetch(`${base}/api/models?search=${encodeURIComponent(q)}&limit=8`);
    const d = await r.json();
    console.log(`\n### ${q} (${d.length})`);
    for (const m of d) console.log('  ', m.id, '| dl', m.downloads);
  } catch (e) { console.log('ERR', q, e.message); }
}

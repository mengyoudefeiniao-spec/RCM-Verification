const base = 'https://hf-mirror.com';
async function j(u) {
  const r = await fetch(u, { headers: { accept: 'application/json' } });
  if (!r.ok) throw new Error(u + ' -> ' + r.status);
  return r.json();
}
const queries = ['insightface', 'buffalo_l', 'w600k_mbf', 'arcface onnx', 'face recognition onnx', 'minifasnet', 'anti-spoofing onnx'];
for (const q of queries) {
  try {
    const d = await j(`${base}/api/models?search=${encodeURIComponent(q)}&limit=10&full=false`);
    console.log(`\n### ${q}  (${d.length})`);
    for (const m of d) console.log('  ', m.id, '| downloads', m.downloads);
  } catch (e) {
    console.log('ERR', q, e.message);
  }
}

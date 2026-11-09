const base = 'https://hf-mirror.com';
const qs = ['fairface', 'FairFace', 'fair face', 'facet diversity'];
for (const q of qs) {
  try {
    const r = await fetch(`${base}/api/datasets?search=${encodeURIComponent(q)}&limit=10`);
    const d = await r.json();
    console.log(`\n### ${q} (${d.length})`);
    for (const m of d) console.log('  ', m.id, '| dl', m.downloads);
  } catch (e) { console.log('ERR', q, e.message); }
}

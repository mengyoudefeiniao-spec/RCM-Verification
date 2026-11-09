const base = 'https://hf-mirror.com';
for (const r of ['HuggingFaceM4/FairFace', 'Subh775/FairFace_Balanced_3K', 'nateraw/fairface']) {
  try {
    const res = await fetch(`${base}/api/datasets/${r}/tree/main`, { headers: { accept: 'application/json' } });
    if (!res.ok) { console.log(`\n### ${r} -> ${res.status}`); continue; }
    const files = await res.json();
    console.log(`\n### ${r} (${files.length})`);
    for (const f of files.slice(0, 20)) console.log(`   ${f.type === 'directory' ? '[D]' : '   '} ${f.path}  ${f.size ? (f.size / 1048576).toFixed(2) + 'MB' : ''}`);
  } catch (e) { console.log(`\n### ${r} ERR ${e.message}`); }
}

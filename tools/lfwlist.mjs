const base = 'https://hf-mirror.com';
const repos = ['0xmoose/lfw-sample', 'vilsonrodrigues/lfw', 'logasja/lfw', 'bitmind/lfw'];
for (const r of repos) {
  try {
    const res = await fetch(`${base}/api/datasets/${r}/tree/main`, { headers: { accept: 'application/json' } });
    if (!res.ok) { console.log(`\n### ${r} -> ${res.status}`); continue; }
    const files = await res.json();
    console.log(`\n### ${r}  (${files.length} entries)`);
    for (const f of files.slice(0, 25)) console.log(`   ${f.type === 'directory' ? '[D]' : '   '} ${f.path}  ${f.size ? (f.size / 1048576).toFixed(2) + 'MB' : ''}`);
  } catch (e) {
    console.log(`\n### ${r} ERR ${e.message}`);
  }
}

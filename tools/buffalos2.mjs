const base = 'https://hf-mirror.com';
const repos = ['immich-app/buffalo_s', 'hoanguyenthanh07/Buffalo_ver_S_2023_onnx', 'YanChen/insigtface_model_buffalo_s'];

async function tree(repo, path = 'main') {
  for (let i = 0; i < 5; i++) {
    const res = await fetch(`${base}/api/models/${repo}/tree/${path}`, { headers: { accept: 'application/json' } });
    if (res.status === 429) { await new Promise(r => setTimeout(r, 3000 * (i + 1))); continue; }
    if (!res.ok) { console.log(`  ${repo}/${path} -> ${res.status}`); return []; }
    return await res.json();
  }
  console.log(`  ${repo}/${path} -> 429 放弃`);
  return [];
}

for (const r of repos) {
  const files = await tree(r);
  console.log(`\n### ${r} (${files.length})`);
  for (const f of files) console.log(`   ${f.type === 'directory' ? '[D]' : '   '} ${f.path}  ${f.size ? (f.size / 1048576).toFixed(2) + 'MB' : ''}`);
  await new Promise(r => setTimeout(r, 1500));
}

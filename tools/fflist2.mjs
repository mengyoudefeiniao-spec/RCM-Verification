const base = 'https://hf-mirror.com';
async function tree(repo, path) {
  const res = await fetch(`${base}/api/datasets/${repo}/tree/main/${path}`, { headers: { accept: 'application/json' } });
  if (!res.ok) { console.log(`${repo}/${path} -> ${res.status}`); return []; }
  return await res.json();
}
for (const [r, p] of [['HuggingFaceM4/FairFace', '0.25'], ['Subh775/FairFace_Balanced_3K', 'data'], ['Subh775/FairFace_Balanced_3K', 'testimgs']]) {
  const f = await tree(r, p);
  console.log(`\n### ${r}/${p} (${f.length})`);
  for (const x of f.slice(0, 20)) console.log(`   ${x.type === 'directory' ? '[D]' : '   '} ${x.path}  ${x.size ? (x.size / 1048576).toFixed(2) + 'MB' : ''}`);
}

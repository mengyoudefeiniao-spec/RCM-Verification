const base = 'https://hf-mirror.com';
async function tree(repo, path) {
  const res = await fetch(`${base}/api/datasets/${repo}/tree/main/${path}`, { headers: { accept: 'application/json' } });
  if (!res.ok) { console.log(`${repo}/${path} -> ${res.status}`); return []; }
  return await res.json();
}
for (const [repo, path] of [['0xmoose/lfw-sample', 'data'], ['bitmind/lfw', 'data'], ['logasja/lfw', 'pairs'], ['logasja/lfw', 'data']]) {
  const files = await tree(repo, path);
  console.log(`\n### ${repo}/${path} (${files.length})`);
  for (const f of files.slice(0, 15)) console.log(`  ${f.type === 'directory' ? '[D]' : '   '} ${f.path} ${f.size ? (f.size / 1048576).toFixed(2) + 'MB' : ''}`);
}

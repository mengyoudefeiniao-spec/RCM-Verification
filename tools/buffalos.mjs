const base = 'https://hf-mirror.com';
for (const r of ['immich-app/buffalo_s', 'YanChen/insigtface_model_buffalo_s', 'hoanguyenthanh07/Buffalo_ver_S_2023_onnx']) {
  try {
    const res = await fetch(`${base}/api/models/${r}/tree/main`, { headers: { accept: 'application/json' } });
    if (!res.ok) { console.log(`\n### ${r} -> ${res.status}`); continue; }
    const files = await res.json();
    console.log(`\n### ${r}`);
    for (const f of files) console.log(`   ${f.type === 'directory' ? '[D]' : '   '} ${f.path}  ${f.size ? (f.size / 1048576).toFixed(2) + 'MB' : ''}`);
  } catch (e) { console.log(`\n### ${r} ERR ${e.message}`); }
}

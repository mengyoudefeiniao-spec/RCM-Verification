const base = 'https://hf-mirror.com';
async function tryFetch(u, opt = {}) {
  try {
    const t = Date.now();
    const r = await fetch(u, opt);
    return { ok: r.ok, status: r.status, ms: Date.now() - t, len: r.headers.get('content-length') };
  } catch (e) {
    return { ok: false, err: (e.cause && e.cause.code) || e.message };
  }
}
const probes = [
  'https://randomuser.me/api/portraits/men/1.jpg',
  'https://thispersondoesnotexist.com/',
  'https://picsum.photos/400',
];
for (const u of probes) console.log(u, JSON.stringify(await tryFetch(u, { method: 'HEAD' })));

for (const q of ['lfw', 'face recognition dataset', 'lfw-deepfunneled']) {
  try {
    const r = await fetch(`${base}/api/datasets?search=${encodeURIComponent(q)}&limit=8`);
    const d = await r.json();
    console.log(`\n### datasets "${q}"`);
    for (const m of d) console.log('  ', m.id, '| downloads', m.downloads);
  } catch (e) {
    console.log('ERR', q, e.message);
  }
}

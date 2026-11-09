const hosts = ['https://huggingface.co', 'https://hf-mirror.com', 'https://modelscope.cn', 'https://www.modelscope.cn'];
for (const h of hosts) {
  try {
    const t = Date.now();
    const r = await fetch(h);
    console.log(r.status, Date.now() - t + 'ms', h);
  } catch (e) {
    console.log('ERR', h, (e.cause && e.cause.code) || e.message);
  }
}

const t = await (await fetch('https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/')).text();
const files = [...t.matchAll(/href="([^"]+)"/g)].map(m => m[1]).filter(f => f.endsWith('.zip'));
console.log('--- tuna jdk ---');
console.log(files.join('\n'));

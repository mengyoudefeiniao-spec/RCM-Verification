// 扫描 class 常量池，找出「被引用但没打进包」的包，提前发现 NoClassDefFoundError。
import fs from 'node:fs';
import path from 'node:path';

const root = process.argv[2];          // 解压后的 jar 目录
const present = new Set();             // 已打包的包（两级）

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.class')) out.push(p);
  }
  return out;
}

function readClassEntries(file) {
  const b = fs.readFileSync(file);
  if (b.readUInt32BE(0) !== 0xCAFEBABE) return [];
  const count = b.readUInt16BE(8);
  let off = 10;
  const utf8 = [];
  for (let i = 1; i < count; i++) {
    const tag = b[off++];
    switch (tag) {
      case 1: { // Utf8
        const len = b.readUInt16BE(off); off += 2;
        utf8.push(b.toString('latin1', off, off + len));
        off += len;
        break;
      }
      case 3: case 4: off += 4; break;
      case 5: case 6: off += 8; i++; break;   // long/double 占两格
      case 7: case 8: case 16: case 19: case 20: off += 2; break;
      case 9: case 10: case 11: case 12: case 17: case 18: off += 4; break;
      case 15: off += 3; break;
      default: return utf8;                   // 未知 tag，放弃解析
    }
  }
  return utf8;
}

const classes = walk(root);
const referenced = new Map();  // pkg -> 例子
const classNames = [];

for (const c of classes) {
  const relFull = path.relative(root, c).replace(/\\/g, '/').replace(/\.class$/, '');
  const rel = relFull.split('/').slice(1).join('/');   // 去掉 jar 目录名这一层
  classNames.push(rel);
  const parts = rel.split('/');
  if (parts.length >= 2) present.add(parts.slice(0, -1).join('/'));
  let ents;
  try { ents = readClassEntries(c); } catch { continue; }
  for (const s of ents) {
    const m = /^([a-z][a-z0-9_]*\/[a-z0-9_]+\/[A-Za-z0-9_$/]+)$/.exec(s);
    if (!m) continue;
    const pkg = s.split('/').slice(0, -1).join('/');
    if (!referenced.has(pkg)) referenced.set(pkg, s);
  }
}

const allowed = ['android/', 'androidx/', 'java/', 'javax/', 'dalvik/', 'sun/', 'org/json'];
const missing = [];
for (const [pkg, example] of referenced) {
  if (allowed.some(a => (pkg + '/').startsWith(a))) continue;
  if (present.has(pkg)) continue;
  missing.push([pkg, example]);
}
missing.sort();

console.log(`扫描 ${classes.length} 个 class，已打包 ${present.size} 个包`);
console.log('\n=== 被引用但未打包的包 ===');
if (missing.length === 0) console.log('  无');
for (const [pkg, ex] of missing) console.log(`  ${pkg}   例: ${ex}`);

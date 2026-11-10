// 类级缺失扫描：解析每个 class 的常量池，取出所有 CONSTANT_Class 引用，
// 与「实际打包的类集合」比对，找出运行时会 NoClassDefFoundError 的类。
// 用法: node tools/depscan2.mjs <解压目录>
import fs from 'node:fs';
import path from 'node:path';

const root = process.argv[2];
const present = new Set();
const referenced = new Map();   // className -> 例子(引用它的类)

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.class')) out.push(p);
  }
  return out;
}

function parseConstantPool(b) {
  const count = b.readUInt16BE(8);
  let off = 10;
  const utf8 = new Array(count);
  const classes = [];       // 常量池里 tag=7 的名字索引
  for (let i = 1; i < count; i++) {
    const tag = b[off++];
    switch (tag) {
      case 1: { const len = b.readUInt16BE(off); off += 2; utf8[i] = b.toString('latin1', off, off + len); off += len; break; }
      case 3: case 4: off += 4; break;
      case 5: case 6: off += 8; i++; break;
      case 7: classes.push(b.readUInt16BE(off)); off += 2; break;
      case 8: case 16: case 19: case 20: off += 2; break;
      case 9: case 10: case 11: case 12: case 17: case 18: off += 4; break;
      case 15: off += 3; break;
      default: return { utf8, classes };
    }
  }
  return { utf8, classes };
}

const files = walk(root);
for (const f of files) {
  const rel = path.relative(root, f).replace(/\\/g, '/').replace(/\.class$/, '');
  present.add(rel.split('/').slice(1).join('/'));   // 去掉 jar 目录名
}

// org/json 与 apache 的 http 等是 android.jar 里的平台类，不在 build/deps 里，属于正常引用
const ALLOW = ['java/', 'javax/', 'android/', 'androidx/', 'dalvik/', 'sun/', 'org/w3c/', 'org/xml/', 'org/json/'];
for (const f of files) {
  const self = path.relative(root, f).replace(/\\/g, '/').replace(/\.class$/, '').split('/').slice(1).join('/');
  let pool;
  try { pool = parseConstantPool(fs.readFileSync(f)); } catch { continue; }
  for (const idx of pool.classes) {
    let name = pool.utf8[idx];
    if (!name) continue;
    if (name.startsWith('[')) {            // 数组类型，取元素类型
      name = name.replace(/^\[+/, '').replace(/^L/, '').replace(/;$/, '');
    }
    if (!/^[A-Za-z0-9_$]+\//.test(name)) continue;   // 非包路径（如基本类型/内部名）跳过
    if (ALLOW.some(a => name.startsWith(a))) continue;
    if (present.has(name)) continue;
    if (!referenced.has(name)) referenced.set(name, self);
  }
}

const missing = [...referenced.entries()].sort((a, b) => a[0].localeCompare(b[0]));
console.log(`扫描 ${files.length} 个 class，已打包 ${present.size} 个类`);
console.log('\n=== 被引用但不存在的类 ===');
if (missing.length === 0) console.log('  无');
for (const [c, by] of missing.slice(0, 60)) console.log(`  ${c}\n      被 ${by} 引用`);
if (missing.length > 60) console.log(`  ... 另有 ${missing.length - 60} 个`);

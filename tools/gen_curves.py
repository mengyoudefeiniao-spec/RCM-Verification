# 生成 app/java/iq/digitalration/faceverify/card/EcCurve.java
# 数据来源：
#   - NIST P-192/224/256/384/521：JDK SunEC 导出（tools/validate/CurveDump.java -> dl/tmp/nist_curves.txt）
#   - brainpool P192r1..P512r1：RFC 5639（dl/tmp/rfc5639.txt）
# 用法：python tools/gen_curves.py
import re, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NIST = os.path.join(ROOT, 'dl/tmp/nist_curves.txt')
RFC = os.path.join(ROOT, 'dl/tmp/rfc5639.txt')
OUT = os.path.join(ROOT, 'app/java/iq/digitalration/faceverify/card/EcCurve.java')

# ICAO 9303-11 §9.5.1 标准域参数编号
NIST_IDS = {'P192': 8, 'P224': 10, 'P256': 12, 'P384': 15, 'P521': 18}
BP_IDS = {'P192': 9, 'P224': 11, 'P256': 13, 'P320': 14, 'P384': 16, 'P512': 17}


def parse_nist(path):
    out = {}
    cur = None
    raw = open(path, 'rb').read()
    txt = ''
    for enc in ('utf-8', 'utf-16', 'utf-16-le'):
        try:
            txt = raw.decode(enc)
            break
        except UnicodeDecodeError:
            txt = ''
    for line in txt.splitlines():
        line = line.strip()
        m = re.match(r'### (\w+)', line)
        if m:
            cur = m.group(1)
            out[cur] = {}
            continue
        if '=' in line and cur:
            k, v = line.split('=', 1)
            if k in ('p', 'a', 'b', 'gx', 'gy', 'n'):
                out[cur][k] = v
    return out


def parse_bp(path):
    txt = open(path, encoding='utf-8').read()
    out = {}
    for key in ['brainpoolP192r1', 'brainpoolP224r1', 'brainpoolP256r1',
                'brainpoolP320r1', 'brainpoolP384r1', 'brainpoolP512r1']:
        i = txt.find('\n   ' + key + '\n')
        if i < 0:
            i = txt.find(key)
        sec = txt[i:i + 3000]
        d = {}
        for name, tag in [('p', 'p'), ('a', 'A'), ('b', 'B'), ('gx', 'x'), ('gy', 'y'), ('n', 'q')]:
            # RFC 里 160~320 位曲线的十六进制会换行，取到下一条空行为止后去掉空白
            m = re.search(r'(?m)^\s*' + tag + r'\s*=(.*?)(?:\n\s*\n)', sec, re.S)
            if not m:
                raise SystemExit('missing %s in %s' % (tag, key))
            hexstr = re.sub(r'[^0-9A-Fa-f]', '', m.group(1))
            if len(hexstr) < 40:
                raise SystemExit('short %s in %s: %r' % (tag, key, m.group(1)))
            d[name] = hexstr.upper()
        out[key] = d
    return out


nist = parse_nist(NIST)
bp = parse_bp(RFC)

entries = []


def add(name, cid, d):
    entries.append((name, cid, d))


for k in ['P192', 'P224', 'P256', 'P384', 'P521']:
    add('NIST ' + k, NIST_IDS[k], nist[k])
for k in ['P192', 'P224', 'P256', 'P320', 'P384', 'P512']:
    add('brainpool ' + k + 'r1', BP_IDS[k], bp['brainpool' + k + 'r1'])

lines = []
lines.append('package iq.digitalration.faceverify.card;')
lines.append('')
lines.append('import java.math.BigInteger;')
lines.append('')
lines.append('/**')
lines.append(' * ICAO Doc 9303-11 §9.5.1 的标准域参数（PACE 用）。')
lines.append(' *')
lines.append(' * <p>参数全部写入常量，不依赖 JCE 的曲线支持：Android 的 Conscrypt 与 JDK 的 SunEC 对')
lines.append(' * brainpool 的支持并不一致（有的直接抛 InvalidAlgorithmParameterException），')
lines.append(' * 而护照/身份证芯片最常用的恰恰是 brainpoolP256r1，所以这里自己实现点运算。')
lines.append(' *')
lines.append(' * <p>本文件由 tools/gen_curves.py 生成（NIST 参数取自 JDK SunEC，brainpool 取自 RFC 5639），')
lines.append(' * 手改无效——要改请改生成脚本。')
lines.append(' */')
lines.append('public final class EcCurve {')
lines.append('')
lines.append('    public final int id;          // ICAO 标准参数编号（EF.CardAccess 的 parameterId）')
lines.append('    public final String name;')
lines.append('    public final BigInteger p, a, b, gx, gy, n;')
lines.append('    public final int fieldBytes;')
lines.append('')
lines.append('    private EcCurve(int id, String name, String p, String a, String b, String gx, String gy, String n) {')
lines.append('        this.id = id;')
lines.append('        this.name = name;')
lines.append('        this.p = new BigInteger(p, 16);')
lines.append('        this.a = new BigInteger(a, 16);')
lines.append('        this.b = new BigInteger(b, 16);')
lines.append('        this.gx = new BigInteger(gx, 16);')
lines.append('        this.gy = new BigInteger(gy, 16);')
lines.append('        this.n = new BigInteger(n, 16);')
lines.append('        this.fieldBytes = (this.p.bitLength() + 7) / 8;')
lines.append('    }')
lines.append('')
lines.append('    private static final EcCurve[] ALL = {')
for i, (name, cid, d) in enumerate(entries):
    tail = ',' if i < len(entries) - 1 else ''
    lines.append('        new EcCurve(%d, "%s",' % (cid, name))
    lines.append('            "%s",' % d['p'])
    lines.append('            "%s",' % d['a'])
    lines.append('            "%s",' % d['b'])
    lines.append('            "%s",' % d['gx'])
    lines.append('            "%s",' % d['gy'])
    lines.append('            "%s")%s' % (d['n'], tail))
lines.append('    };')
lines.append('')
lines.append('    /** 按 ICAO 参数编号取曲线；不认识的编号返回 null。 */')
lines.append('    public static EcCurve byId(int id) {')
lines.append('        for (EcCurve c : ALL) if (c.id == id) return c;')
lines.append('        return null;')
lines.append('    }')
lines.append('')
lines.append('    /** 支持的全部参数编号（给界面提示用）。 */')
lines.append('    public static String supportedIds() {')
lines.append('        StringBuilder sb = new StringBuilder();')
lines.append('        for (EcCurve c : ALL) {')
lines.append('            if (sb.length() > 0) sb.append(", ");')
lines.append('            sb.append(c.id).append(\'(\').append(c.name).append(\')\');')
lines.append('        }')
lines.append('        return sb.toString();')
lines.append('    }')
lines.append('}')
lines.append('')

os.makedirs(os.path.dirname(OUT), exist_ok=True)
open(OUT, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
print('wrote', OUT, len(entries), 'curves')

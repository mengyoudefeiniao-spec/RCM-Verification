# 生成 NFC/ICAO 测试样本（全部是"假芯片"数据，只用于在 PC 上验证 DER 解析、SOD 验签与 APDU 流程）：
#   dl/tmp/nfc-sample/{dg1,dg2,dg11,com,sod}.bin
#   dl/tmp/nfc-sample/vectors.txt   EC 标量乘 / ECDH 参考值
# 用法：python tools/nfc-proto/gen_nfc_sample.py
import os, hashlib, datetime
from cryptography.hazmat.primitives.asymmetric import ec, rsa, padding
from cryptography.hazmat.primitives import hashes, serialization
from cryptography import x509
from cryptography.x509.oid import NameOID

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, 'tools/fixtures/nfc-sample')
os.makedirs(OUT, exist_ok=True)


# ---------------------------------------------------------------- DER 构造
def der_len(n):
    if n < 0x80:
        return bytes([n])
    b = n.to_bytes((n.bit_length() + 7) // 8, 'big')
    return bytes([0x80 | len(b)]) + b


def tlv(tag, val):
    tb = tag.to_bytes((tag.bit_length() + 7) // 8, 'big') if isinstance(tag, int) else tag
    return tb + der_len(len(val)) + val


def oid(dotted):
    parts = [int(x) for x in dotted.split('.')]
    body = bytes([40 * parts[0] + parts[1]])
    for v in parts[2:]:
        if v < 0x80:
            body += bytes([v])
        else:
            chunk = []
            while v:
                chunk.insert(0, v & 0x7F)
                v >>= 7
            for i in range(len(chunk) - 1):
                chunk[i] |= 0x80
            body += bytes(chunk)
    return tlv(0x06, body)


def seq(*items):
    return tlv(0x30, b''.join(items))


def setof(*items):
    return tlv(0x31, b''.join(items))


def integer(v):
    b = v.to_bytes((v.bit_length() + 8) // 8, 'big') if v else b'\x00'
    return tlv(0x02, b)


def null():
    return b'\x05\x00'


# ---------------------------------------------------------------- MRZ / DG
def check_digit(s):
    w = [7, 3, 1]
    total = 0
    for i, c in enumerate(s):
        if c.isdigit():
            v = int(c)
        elif 'A' <= c <= 'Z':
            v = ord(c) - 55
        else:
            v = 0
        total += v * w[i % 3]
    return total % 10


doc = 'AE0644356'                     # 与参考截图一致（DG1 证件号 AH4798371 是另一张卡）
l1 = 'ID' + 'IRQ' + doc + str(check_digit(doc)) + '199199607328<<<'
dob, exp = '910512', '270724'
l2 = dob + str(check_digit(dob)) + 'F' + exp + str(check_digit(exp)) + 'IRQ' + '<' * 11
l2 = l2[:29].ljust(29, '<')
composite = l1[5:30] + l2[0:7] + l2[8:15] + l2[18:29]
l2 = l2 + str(check_digit(composite))
l3 = ('ALDAWDY<<JWD' + '<' * 30)[:30]
mrz = l1 + l2 + l3
assert len(mrz) == 90, (len(mrz), mrz)
assert check_digit(doc) == int(l1[14]), 'doc check'
assert check_digit(dob) == int(l2[6]) and check_digit(exp) == int(l2[14]), 'dob/exp check'
assert check_digit(composite) == int(l2[29]), 'composite check'

dg1 = tlv(0x61, tlv(0x5F1F, mrz.encode('ascii')))

with open(os.path.join(ROOT, 'app/assets/selftest.jpg'), 'rb') as f:
    photo = f.read()
bht = tlv(0x7F60, tlv(0x87, b'\x01\x01') + tlv(0x88, b'\x00\x08'))
dg2 = tlv(0x75, tlv(0x7F61, tlv(0x02, b'\x01') + bht + tlv(0x5F2E, photo)))

dg11 = tlv(0x6B,
           tlv(0x5C, bytes([0x5F, 0x0E, 0x5F, 0x10, 0x5F, 0x2B])) +
           tlv(0x5F0E, 'ALDAWDY<<JWD'.encode('utf-8')) +
           tlv(0x5F10, b'1002E6996574360150') +
           tlv(0x5F2B, b'19910512'))

com = tlv(0x60, tlv(0x5F01, b'0108') + tlv(0x5F36, b'0400') + tlv(0x5C, bytes([1, 2, 11])))


# ---------------------------------------------------------------- SOD（DS 由 CSCA 签发）
def name(cn, c, o):
    return x509.Name([
        x509.NameAttribute(NameOID.COUNTRY_NAME, c),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, o),
        x509.NameAttribute(NameOID.COMMON_NAME, cn),
    ])


def make_cert(subject, issuer, pubkey, issuer_key, ca):
    now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
    b = (x509.CertificateBuilder()
         .subject_name(subject).issuer_name(issuer).public_key(pubkey)
         .serial_number(x509.random_serial_number())
         .not_valid_before(now - datetime.timedelta(days=1))
         .not_valid_after(now + datetime.timedelta(days=3650))
         .add_extension(x509.BasicConstraints(ca=ca, path_length=None), critical=True))
    return b.sign(issuer_key, hashes.SHA256())


csca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
csca_name = name('TEST CSCA', 'IQ', 'Test CSCA')
csca_cert = make_cert(csca_name, csca_name, csca_key.public_key(), csca_key, True)

ds_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
ds_name = name('TEST DS', 'IQ', 'Test Document Signer')
ds_cert = make_cert(ds_name, csca_name, ds_key.public_key(), csca_key, False)

sha256_alg = seq(oid('2.16.840.1.101.3.4.2.1'), null())
dg_hashes = seq(*[seq(integer(n), tlv(0x04, hashlib.sha256(d).digest()))
                  for n, d in [(1, dg1), (2, dg2), (11, dg11)]])
lds = seq(integer(0), sha256_alg, dg_hashes)

content_type_oid = '2.23.136.1.1.1'      # id-icao-ldsSecurityObject
attrs_content = (seq(oid('1.2.840.113549.1.9.3'), setof(oid(content_type_oid))) +
                 seq(oid('1.2.840.113549.1.9.4'), setof(tlv(0x04, hashlib.sha256(lds).digest()))))
attrs = tlv(0x31, attrs_content)
signature = ds_key.sign(attrs, padding.PKCS1v15(), hashes.SHA256())

signer_info = seq(
    integer(1),
    tlv(0x80, b'\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10\x11\x12\x13\x14'),
    sha256_alg,
    tlv(0xA0, attrs_content),
    seq(oid('1.2.840.113549.1.1.11'), null()),
    tlv(0x04, signature),
)
signed_data = seq(
    integer(3),
    setof(sha256_alg),
    seq(oid(content_type_oid), tlv(0xA0, tlv(0x04, lds))),
    tlv(0xA0, ds_cert.public_bytes(serialization.Encoding.DER) + csca_cert.public_bytes(serialization.Encoding.DER)),
    setof(signer_info),
)
sod = tlv(0x77, signed_data)

for n, d in [('dg1.bin', dg1), ('dg2.bin', dg2), ('dg11.bin', dg11), ('com.bin', com), ('sod.bin', sod)]:
    with open(os.path.join(OUT, n), 'wb') as f:
        f.write(d)
    print('%-10s %5d bytes' % (n, len(d)))


# ---------------------------------------------------------------- EC 向量
def ec_vector(curve, label):
    a = ec.generate_private_key(curve)
    b = ec.generate_private_key(curve)
    shared = a.exchange(ec.ECDH(), b.public_key())
    pa, pb = a.public_key().public_numbers(), b.public_key().public_numbers()
    return '%s|%x|%x|%x|%x|%x|%x\n' % (label,
                                       a.private_numbers().private_value, pa.x, pa.y,
                                       b.private_numbers().private_value, pb.x, pb.y) + \
           'shared|%s|%x\n' % (label, int.from_bytes(shared, 'big'))


with open(os.path.join(OUT, 'vectors.txt'), 'w') as f:
    for curve, label in [(ec.BrainpoolP256R1(), 'brainpoolP256r1'),
                         (ec.SECP256R1(), 'secp256r1'),
                         (ec.BrainpoolP384R1(), 'brainpoolP384r1')]:
        f.write(ec_vector(curve, label))

print('MRZ =', mrz)
print('out =', OUT)

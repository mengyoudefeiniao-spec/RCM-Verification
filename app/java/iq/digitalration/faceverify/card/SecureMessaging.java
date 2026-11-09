package iq.digitalration.faceverify.card;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ICAO 9303-11 §9.8 的安全报文（Secure Messaging）。
 *
 * <p>命令 APDU：CLA 强制 0x0C，数据放 DO87（INS 偶数）或 DO85（INS 奇数），Le 放 DO97，最后 DO8E = MAC。
 * 响应先拆外层状态字，再校验 DO8E、解密 DO85/87、取 DO99 里的真实状态。
 *
 * <p>3DES 用 ISO 9797-1 retail MAC + CBC(IV=0)；AES 用 CMAC(8 字节) + CBC(IV=E(KSenc,SSC))。
 */
public final class SecureMessaging {

    private final boolean aes;
    private final int block;
    private final byte[] ksEnc, ksMac;
    private byte[] ssc;

    public SecureMessaging(boolean aes, byte[] ksEnc, byte[] ksMac, byte[] ssc) {
        this.aes = aes;
        this.block = aes ? 16 : 8;
        this.ksEnc = ksEnc;
        this.ksMac = ksMac;
        this.ssc = ssc.clone();
        if (this.ssc.length != block) throw new IllegalArgumentException("SSC 长度必须是块长");
    }

    /** BAC 的 SSC：RND.IC 后 4 字节 || RND.IFD 后 4 字节（8 字节）。 */
    public static byte[] bacSsc(byte[] rndIc, byte[] rndIfd) {
        byte[] s = new byte[8];
        System.arraycopy(rndIc, 4, s, 0, 4);
        System.arraycopy(rndIfd, 4, s, 4, 4);
        return s;
    }

    public static byte[] zeroSsc(boolean aes) {
        return new byte[aes ? 16 : 8];
    }

    public byte[] ssc() { return ssc.clone(); }

    private void sscIncrement() {
        for (int i = ssc.length - 1; i >= 0; i--) {
            ssc[i]++;
            if (ssc[i] != 0) return;
        }
    }

    private byte[] mac(byte[] data) {
        byte[] padded = Crypt.pad(data, block);
        if (aes) return Crypt.aesCmac(ksMac, padded, 8);
        return Crypt.retailMac(ksMac, padded);
    }

    private byte[] cbc(byte[] data, boolean encrypt) {
        byte[] iv = new byte[block];
        if (aes) {
            // IV = E(KSenc, SSC)
            iv = Crypt.aesEcbBlock(ksEnc, ssc);
        }
        return aes ? Crypt.aesCbc(ksEnc, iv, data, encrypt) : Crypt.desCbc(ksEnc, iv, data, encrypt);
    }

    /** 组受保护命令 APDU。 */
    public byte[] wrap(int ins, int p1, int p2, byte[] data, Integer le) {
        sscIncrement();
        List<byte[]> nodes = new ArrayList<>();

        if (data != null && data.length > 0) {
            int tag = (ins % 2 == 0) ? 0x87 : 0x85;
            byte[] enc = cbc(Crypt.pad(data, block), true);
            byte[] v = new byte[enc.length + 1];
            v[0] = 0x01;                        // 版本号，固定 0x01
            System.arraycopy(enc, 0, v, 1, enc.length);
            nodes.add(Tlv.encode(tag, v));
        }
        if (le != null) {
            int l = (le == 0 || le == 256) ? 256 : le;
            nodes.add(Tlv.encode(0x97, l >= 256 ? new byte[]{0x00} : new byte[]{(byte) l}));
        }

        byte[] header = new byte[]{0x0C, (byte) ins, (byte) p1, (byte) p2};
        ByteArrayOutputStream md = new ByteArrayOutputStream();
        md.write(ssc, 0, ssc.length);
        byte[] hp = Crypt.pad(header, block);
        md.write(hp, 0, hp.length);
        for (byte[] n : nodes) md.write(n, 0, n.length);

        byte[] mac = mac(md.toByteArray());
        nodes.add(Tlv.encode(0x8E, mac));

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] n : nodes) body.write(n, 0, n.length);
        byte[] d = body.toByteArray();
        if (d.length > 255) throw new IllegalStateException("SM 数据超长: " + d.length);
        // Le 固定 0x00（256）——SM 响应的实际长度由芯片决定
        return Apdu.cmd(0x0C, ins, p1, p2, d, 0x00);
    }

    /** 校验并解包响应，返回内层数据 + 真实状态字。 */
    public Apdu.Resp unwrap(byte[] rapdu) {
        Apdu.Resp outer = Apdu.parse(rapdu);
        if (outer.data.length == 0) {
            // 裸响应：芯片没接受 SM，SSC 回退，保持与芯片同步
            sscDecrement();
            return new Apdu.Resp(new byte[0], outer.sw);
        }
        sscIncrement();

        List<Tlv> nodes = Tlv.parse(outer.data);
        Tlv do85 = null, do87 = null, do99 = null, do8e = null;
        for (Tlv t : nodes) {
            if (t.tag == 0x85) do85 = t;
            else if (t.tag == 0x87) do87 = t;
            else if (t.tag == 0x99) do99 = t;
            else if (t.tag == 0x8E) do8e = t;
        }
        if (do8e == null || do99 == null) {
            throw new IllegalStateException("SM 响应缺少 DO8E/DO99（芯片可能未建立安全通道）");
        }
        byte[] macData = Crypt.concat(ssc,
                do85 == null ? new byte[0] : do85.raw,
                do87 == null ? new byte[0] : do87.raw,
                do99.raw);
        byte[] exp = mac(macData);
        if (!java.util.Arrays.equals(exp, do8e.value)) {
            throw new IllegalStateException("SM MAC 校验失败（期望 " + Tlv.hex(exp) + "，实际 " + Tlv.hex(do8e.value) + "）");
        }
        int sw = ((do99.value[0] & 0xFF) << 8) | (do99.value[1] & 0xFF);
        byte[] data = new byte[0];
        Tlv enc = do87 != null ? do87 : do85;
        if (enc != null) {
            byte[] v = enc.value;
            if (v.length < 1 || v[0] != 0x01) throw new IllegalStateException("SM 数据版本号不是 0x01");
            byte[] ct = new byte[v.length - 1];
            System.arraycopy(v, 1, ct, 0, ct.length);
            if (ct.length % block != 0) throw new IllegalStateException("SM 密文未对齐");
            data = Crypt.unpad(cbc(ct, false));
        }
        return new Apdu.Resp(data, sw);
    }

    private void sscDecrement() {
        for (int i = ssc.length - 1; i >= 0; i--) {
            if (ssc[i] != 0) { ssc[i]--; return; }
            ssc[i] = (byte) 0xFF;
        }
    }
}

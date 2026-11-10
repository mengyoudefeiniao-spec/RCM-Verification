package iq.digitalration.faceverify;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 终端回执 / 结果签名（TV-04 的端侧一半、TV-11 的全部）。
 *
 * <p>回执 = 规范化 JSON + 设备 Keystore 私钥的 ECDSA 签名 + 单调计数器 + 设备指纹。
 * 服务端拿到回执后，用设备公钥（或证明链）验签即可判断"这条记录确实由这台设备签过、
 * 且内容没被改过、且不是旧回执重放（计数器单调）"。
 *
 * <p>规范化：按键名排序后序列化，避免不同 JSON 实现产生不同字节导致验签失败。
 */
public final class Receipt {

    public static final String SCHEMA = "rcm-receipt@1";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String PREFS = "lab";
    private static final String K_COUNTER = "receiptCounter";

    private Receipt() {}

    // ------------------------------------------------------------ 签发

    public static JSONObject issue(Context c, String kind, JSONObject payload) throws Exception {
        long t0 = System.nanoTime();
        JSONObject body = new JSONObject();
        body.put("schema", SCHEMA);
        body.put("kind", kind == null ? "" : kind);
        body.put("issuedAt", System.currentTimeMillis());
        body.put("counter", nextCounter(c));
        body.put("device", device(c));
        body.put("payload", payload == null ? new JSONObject() : payload);
        body.put("pubSha256", DeviceKey.publicSha256());

        byte[] data = canonical(body).getBytes(UTF8);
        String digest = DeviceKey.sha256Hex(data);
        byte[] sig = DeviceKey.sign(data);

        JSONObject r = new JSONObject();
        Iterator<String> it = body.keys();
        while (it.hasNext()) {
            String k = it.next();
            r.put(k, body.opt(k));
        }
        r.put("id", digest.substring(0, 32));
        r.put("digest", digest);
        r.put("sigAlg", "SHA256withECDSA");
        r.put("sig", DeviceKey.b64(sig));
        r.put("signMs", (System.nanoTime() - t0) / 1000000L);
        return r;
    }

    // ------------------------------------------------------------ 验证

    public static JSONObject verify(Context c, JSONObject r) {
        JSONObject out = new JSONObject();
        long t0 = System.nanoTime();
        try {
            if (r == null) {
                out.put("ok", false);
                out.put("reason", "空回执");
                return out;
            }
            JSONObject body = signable(r);
            byte[] data = canonical(body).getBytes(UTF8);
            byte[] sig = DeviceKey.unb64(r.optString("sig", ""));
            boolean sigOk = sig.length > 0 && DeviceKey.verify(data, sig);
            String digest = DeviceKey.sha256Hex(data);
            boolean idOk = digest.substring(0, 32).equals(r.optString("id", ""));
            boolean pubOk = DeviceKey.publicSha256().equals(r.optString("pubSha256", ""));
            long counter = r.optLong("counter", -1);
            long lastSeen = prefs(c).getLong("lastVerifiedCounter", 0);
            boolean fresh = counter > lastSeen;
            out.put("signatureOk", sigOk);
            out.put("idOk", idOk);
            out.put("sameDeviceKey", pubOk);
            out.put("counter", counter);
            out.put("counterFresh", fresh);
            out.put("kind", r.optString("kind"));
            out.put("issuedAt", r.optLong("issuedAt"));
            out.put("verifyMs", (System.nanoTime() - t0) / 1000000L);
            boolean ok = sigOk && idOk && pubOk;
            out.put("ok", ok);
            if (!ok) out.put("reason", !sigOk ? "签名不匹配（内容被改或不是本机密钥）"
                    : !idOk ? "摘要与 id 不符" : "公钥不是本机");
            if (ok && fresh) prefs(c).edit().putLong("lastVerifiedCounter", counter).apply();
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("reason", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /**
     * 自证：正常回执必须通过；改字段必须被拒；原样重放必须能看出来。
     *
     * <p>注意「改计数器」这一项**不能写死成一个常数**：全新安装后第一次签发的计数器本来就是 1，
     * 写死 {@code put("counter", 1)} 等于什么都没改，验签当然通过，测试会把自己判成失败。
     * 这里改成「当前值 + 1000」，保证一定改动了被签名的内容。
     */
    public static JSONObject tamperTest(Context c) {
        JSONObject out = new JSONObject();
        try {
            JSONObject payload = new JSONObject();
            payload.put("familyNo", "1002E6996574360150");
            payload.put("amount", 25000);
            payload.put("action", "redeem");

            JSONObject r1 = issue(c, "redeem", payload);
            long counter = r1.optLong("counter");
            boolean clean = verify(c, r1).optBoolean("ok");

            JSONObject r2 = new JSONObject(r1.toString());
            r2.getJSONObject("payload").put("amount", 999999);
            boolean payloadRejected = !verify(c, r2).optBoolean("ok");

            JSONObject r3 = new JSONObject(r1.toString());
            r3.put("issuedAt", r1.optLong("issuedAt") - 86400000L);
            boolean timeRejected = !verify(c, r3).optBoolean("ok");

            JSONObject r4 = new JSONObject(r1.toString());
            r4.put("counter", counter + 1000);
            boolean counterRejected = !verify(c, r4).optBoolean("ok");

            // 原样重放：签名、摘要、公钥都对，但计数器不新鲜——必须能看出来
            JSONObject v5 = verify(c, new JSONObject(r1.toString()));
            boolean replayDetected = v5.optBoolean("signatureOk") && !v5.optBoolean("counterFresh", true);

            out.put("counter", counter);
            out.put("cleanPass", clean);
            out.put("payloadTamperRejected", payloadRejected);
            out.put("timeTamperRejected", timeRejected);
            out.put("counterTamperRejected", counterRejected);
            out.put("replayDetected", replayDetected);
            out.put("verifyMs", v5.optLong("verifyMs"));
            out.put("ok", clean && payloadRejected && timeRejected && counterRejected && replayDetected);
            if (!out.optBoolean("ok")) {
                out.put("reason", !clean ? "正常回执没通过验签"
                        : !payloadRejected ? "改业务字段后仍被接受"
                        : !timeRejected ? "改签发时间后仍被接受"
                        : !counterRejected ? "改计数器后仍被接受"
                        : "原样重放没有被判为旧回执");
            }
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static JSONObject signable(JSONObject r) throws Exception {
        JSONObject body = new JSONObject();
        body.put("schema", r.optString("schema", SCHEMA));
        body.put("kind", r.optString("kind", ""));
        body.put("issuedAt", r.optLong("issuedAt"));
        body.put("counter", r.optLong("counter"));
        JSONObject d = r.optJSONObject("device");
        body.put("device", d == null ? new JSONObject() : d);
        JSONObject p = r.optJSONObject("payload");
        body.put("payload", p == null ? new JSONObject() : p);
        body.put("pubSha256", r.optString("pubSha256", ""));
        return body;
    }

    // ------------------------------------------------------------ 设备信息 / 计数器

    public static JSONObject device(Context c) throws Exception {
        JSONObject d = new JSONObject();
        d.put("pkg", c.getPackageName());
        d.put("model", Build.MANUFACTURER + " " + Build.MODEL);
        d.put("sdk", Build.VERSION.SDK_INT);
        d.put("deviceId", deviceId(c));
        return d;
    }

    public static String deviceId(Context c) {
        try {
            String androidId = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null) androidId = "";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((c.getPackageName() + "|" + androidId).getBytes(UTF8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format(Locale.US, "%02x", d[i]));
            return sb.toString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static long nextCounter(Context c) {
        SharedPreferences p = prefs(c);
        long n = p.getLong(K_COUNTER, 0) + 1;
        p.edit().putLong(K_COUNTER, n).apply();
        return n;
    }

    public static long counter(Context c) {
        return prefs(c).getLong(K_COUNTER, 0);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 规范化

    /** 按键名排序的确定性序列化：同样的内容必定得到同样的字节。 */
    public static String canonical(Object v) {
        if (v == null || v == JSONObject.NULL) return "null";
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            TreeMap<String, Object> m = new TreeMap<>();
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, o.opt(k));
            }
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(JSONObject.quote(e.getKey())).append(':').append(canonical(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) sb.append(',');
                sb.append(canonical(a.opt(i)));
            }
            return sb.append(']').toString();
        }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (!Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d) && Math.abs(d) < 9.0e15) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        if (v instanceof Boolean) return v.toString();
        if (v instanceof String) return JSONObject.quote((String) v);
        return JSONObject.quote(String.valueOf(v));
    }
}

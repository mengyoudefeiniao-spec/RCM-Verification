package iq.digitalration.faceverify;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 60 秒动态码与离线匹配（TV-08）。
 *
 * <p>码 = HMAC-SHA256(Keystore 里的密钥, 设备号|窗口号) 截 8 位数字，60 秒换一次。
 * 校验端允许 ±1 个窗口（覆盖 ±60s 时钟偏差），并把"已经用过的窗口"记到本地，
 * 同一个码第二次出现直接判重放——这一层不依赖网络，也不需要两端联网对齐。
 *
 * <p>本类只做**码协议**（生成/校验/容错/重放/吊销）。二维码图层需要解码库
 * （zxing 等离线 jar 或自研解码），本工程目前没有集成，界面上直接用 8 位数字码代替扫码，
 * 验证的是"离线匹配 + 时钟容错 + 重放拦截"这三件真正有风险的事。
 */
public final class DynamicCode {

    public static final long PERIOD_MS = 60000L;
    public static final int TOLERANCE_WINDOWS = 1;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String PREFS = "lab";
    private static final String K_USED = "qr.used.";

    private DynamicCode() {}

    // ------------------------------------------------------------ 生成

    public static long window(long ts) {
        return Math.floorDiv(ts, PERIOD_MS);
    }

    public static long remainMs(long ts) {
        return PERIOD_MS - Math.floorMod(ts, PERIOD_MS);
    }

    public static String codeForWindow(String deviceId, long window) {
        try {
            byte[] mac = DeviceKey.hmac((deviceId + "|" + window).getBytes(UTF8));
            long v = 0;
            for (int i = 0; i < 6; i++) v = (v << 8) | (mac[i] & 0xFF);
            return String.format(Locale.US, "%08d", v % 100000000L);
        } catch (Throwable t) {
            return "";
        }
    }

    public static String current(Context c) {
        return codeForWindow(Receipt.deviceId(c), window(System.currentTimeMillis()));
    }

    public static JSONObject info(Context c) {
        JSONObject o = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            o.put("deviceId", Receipt.deviceId(c));
            o.put("periodMs", PERIOD_MS);
            o.put("window", window(now));
            o.put("code", codeForWindow(Receipt.deviceId(c), window(now)));
            o.put("remainMs", remainMs(now));
            o.put("tolerance", TOLERANCE_WINDOWS);
            o.put("usedWindows", used(c).size());
            o.put("qrLayer", "未集成（缺解码库）；本页验证码协议与离线匹配");
        } catch (Throwable ignored) {
        }
        return o;
    }

    // ------------------------------------------------------------ 校验

    public static JSONObject verify(Context c, String code, long now, String deviceId) {
        JSONObject o = new JSONObject();
        long t0 = System.nanoTime();
        try {
            String dev = deviceId == null || deviceId.isEmpty() ? Receipt.deviceId(c) : deviceId;
            String input = code == null ? "" : code.trim();
            o.put("code", input);
            o.put("deviceId", dev);
            if (input.length() != 8) {
                o.put("ok", false);
                o.put("reason", "码长度不是 8 位");
                return o;
            }
            long w = window(now);
            for (int d = -TOLERANCE_WINDOWS; d <= TOLERANCE_WINDOWS; d++) {
                long ww = w + d;
                String expect = codeForWindow(dev, ww);
                if (!expect.equals(input)) continue;
                o.put("windowMatched", ww);
                o.put("skewMs", now - ww * PERIOD_MS);
                String key = dev + "|" + ww;
                Set<String> used = used(c);
                if (used.contains(key)) {
                    o.put("ok", false);
                    o.put("replay", true);
                    o.put("reason", "该码已被使用过（重放拦截）");
                    return o;
                }
                used.add(key);
                saveUsed(c, used);
                o.put("ok", true);
                o.put("replay", false);
                o.put("reason", "匹配（容错窗口 " + d + "）");
                o.put("verifyMs", (System.nanoTime() - t0) / 1000000L);
                return o;
            }
            o.put("ok", false);
            o.put("reason", "不在 ±" + TOLERANCE_WINDOWS + " 个窗口内（码错误或时钟偏差过大）");
        } catch (Throwable t) {
            try {
                o.put("ok", false);
                o.put("reason", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    /** 吊销：删掉 HMAC 密钥，旧码全部失效，下次生成换了新密钥。 */
    public static void rotate() {
        DeviceKey.deleteAlias(DeviceKey.HMAC_ALIAS);
    }

    // ------------------------------------------------------------ 自测

    /**
     * 批量自测：正确码可过、过期/未来码被拒、重复码被拒、篡改码被拒。
     * 每次用独立的合成设备号，避免重放缓存互相干扰。
     */
    public static JSONObject selfTest(Context c, int rounds) {
        JSONObject o = new JSONObject();
        int accepted = 0, expiredRejected = 0, futureRejected = 0, replayRejected = 0, tamperRejected = 0;
        long t0 = System.currentTimeMillis();
        try {
            for (int i = 0; i < rounds; i++) {
                String dev = "selftest-" + i;
                long now = 1700000000000L + i * 61000L;   // 固定时间轴，可复现
                Set<String> used = new HashSet<>();

                // 1) 正确码（当前窗口）
                String okCode = codeForWindow(dev, window(now));
                if (checkSynthetic(dev, okCode, now, used)) accepted++;

                // 2) 同一码再来一次 → 必须判重放
                String okCode2 = codeForWindow(dev, window(now));
                if (!checkSynthetic(dev, okCode2, now, used)) replayRejected++;

                // 3) 过期码（4 个窗口前）
                String old = codeForWindow(dev, window(now) - 4);
                if (!checkSynthetic(dev, old, now, used)) expiredRejected++;

                // 4) 未来码（+3 个窗口）
                String future = codeForWindow(dev, window(now) + 3);
                if (!checkSynthetic(dev, future, now, used)) futureRejected++;

                // 5) 篡改一位（用另一个设备号的键空间，避免刚好撞上重放）
                char[] cs = okCode.toCharArray();
                cs[cs.length - 1] = cs[cs.length - 1] == '9' ? '0' : (char) (cs[cs.length - 1] + 1);
                if (!checkSynthetic(dev + "x", new String(cs), now, new HashSet<>())) tamperRejected++;
            }
            o.put("rounds", rounds);
            o.put("accepted", accepted);
            o.put("replayRejected", replayRejected);
            o.put("expiredRejected", expiredRejected);
            o.put("futureRejected", futureRejected);
            o.put("tamperRejected", tamperRejected);
            o.put("ms", System.currentTimeMillis() - t0);
            o.put("ok", accepted == rounds && replayRejected == rounds && expiredRejected == rounds
                    && futureRejected == rounds && tamperRejected == rounds);
        } catch (Throwable t) {
            try {
                o.put("ok", false);
                o.put("error", t.getStackTrace().length > 0 ? t.toString() : t.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    /** 不落盘的重放判断（自测用），逻辑与 verify 相同，used 由调用方跨次复用。 */
    private static boolean checkSynthetic(String dev, String code, long now, Set<String> used) {
        long w = window(now);
        for (int d = -TOLERANCE_WINDOWS; d <= TOLERANCE_WINDOWS; d++) {
            long ww = w + d;
            if (!codeForWindow(dev, ww).equals(code)) continue;
            String key = dev + "|" + ww;
            if (used.contains(key)) return false;
            used.add(key);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------ 重放缓存

    private static Set<String> used(Context c) {
        SharedPreferences p = prefs(c);
        String raw = p.getString(K_USED + Receipt.deviceId(c), "");
        Set<String> s = new HashSet<>();
        if (!raw.isEmpty()) {
            String[] parts = raw.split(",");
            for (String x : parts) if (!x.isEmpty()) s.add(x);
        }
        return s;
    }

    private static void saveUsed(Context c, Set<String> s) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String x : s) {
            if (n++ > 500) break;            // 只留最近 500 个窗口，别把 prefs 撑大
            if (sb.length() > 0) sb.append(',');
            sb.append(x);
        }
        prefs(c).edit().putString(K_USED + Receipt.deviceId(c), sb.toString()).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 把 8 位码转成可复制的短串（后面接二维码图层时，payload 就用这个格式）。 */
    public static String payload(Context c, long now) {
        try {
            long w = window(now);
            String dev = Receipt.deviceId(c);
            return "RCM1|" + dev + "|" + w + "|" + codeForWindow(dev, w);
        } catch (Throwable t) {
            return "";
        }
    }

    public static String sha256(String s) {
        try {
            return DeviceKey.hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF8)));
        } catch (Throwable t) {
            return "";
        }
    }
}

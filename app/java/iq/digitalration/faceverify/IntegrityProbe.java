package iq.digitalration.faceverify;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONObject;

/**
 * 端侧核验结果可信性（TV-04）：先探测"这台机器能不能用平台完整性能力"。
 *
 * <p>要点：
 * <ul>
 *   <li>Play Integrity 的**云端判决**必须联网 + Google Cloud 项目号 + Play 服务依赖，
 *       本 APK 为了"飞行模式可用"没有申请 INTERNET 权限，所以这里只报"能不能调"和"本地证据"，
 *       不假装拿到了 verdict；</li>
 *   <li>真正能离线做的是：改包检测（签名指纹）、Root/模拟器迹象、以及**用设备密钥给结果签名**
 *       （{@link Receipt}）——服务端验签即可拒掉被改过的客户端结论；</li>
 *   <li>下面这些字段就是判定"结果可不可信"的输入，界面和报告里原样给出。</li>
 * </ul>
 */
public final class IntegrityProbe {

    private IntegrityProbe() {}

    public static JSONObject collect(Context c) {
        JSONObject dev = DeviceInfo.collect(c);
        JSONObject o = new JSONObject();
        try {
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("device", dev.optJSONObject("device"));
            o.put("app", dev.optJSONObject("app"));
            o.put("integrity", dev.optJSONObject("integrity"));

            JSONObject gms = pkg(c, "com.google.android.gms");
            JSONObject play = pkg(c, "com.android.vending");
            o.put("gms", gms);
            o.put("playStore", play);

            JSONObject api = new JSONObject();
            boolean playCore = classExists("com.google.android.play.core.integrity.IntegrityManagerFactory")
                    || classExists("com.google.android.play.core.integrity.IntegrityTokenRequest");
            boolean gmsCommon = classExists("com.google.android.gms.common.GoogleApiAvailability");
            boolean gmsIntegrity = classExists("com.google.android.gms.integrity.IntegrityManagerFactory");
            api.put("playCoreIntegrity", playCore);
            api.put("gmsCommon", gmsCommon);
            api.put("gmsIntegrity", gmsIntegrity);
            o.put("api", api);

            // 先让签名密钥真的生成出来：没生成过就只能读到 exists=false，
            // 也就拿不到 attestation 证书链与 KeyStore 安全等级（v2.5 首版真机就是 unknown）。
            try {
                DeviceKey.ensureSigningKey();
            } catch (Throwable ignored) {
            }
            JSONObject key = DeviceKey.describe();
            o.put("deviceKey", key);

            JSONObject app = dev.optJSONObject("app");
            JSONObject it = dev.optJSONObject("integrity");
            boolean netPerm = app != null && app.optBoolean("networkPermission");
            boolean canCall = playCore || gmsIntegrity;
            o.put("hasNetworkPermission", netPerm);
            o.put("canCallPlayIntegrity", canCall);

            JSONObject verdict = new JSONObject();
            verdict.put("localResultSigning", key.optBoolean("exists"));
            verdict.put("hardwareBackedKey", key.optBoolean("hardwareAttested"));
            verdict.put("keystoreLevel", key.optString("securityLevel", "unknown"));
            verdict.put("rootSuspected", it != null && it.optBoolean("rootSuspected"));
            verdict.put("emulatorSuspected", it != null && it.optBoolean("emulatorSuspected"));
            verdict.put("debuggable", app != null && app.optBoolean("debuggable"));
            verdict.put("cloudVerdictAvailable", false);
            verdict.put("cloudBlockedReason", netPerm
                    ? "可联网，但本包未集成 Play Integrity SDK 与 Cloud 项目号"
                    : "本包未申请 INTERNET 权限（飞行模式可用），无法请求 Play Integrity 云端判决");
            o.put("verdict", verdict);
        } catch (Throwable t) {
            try {
                o.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    private static JSONObject pkg(Context c, String id) {
        JSONObject o = new JSONObject();
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(id, 0);
            o.put("installed", true);
            o.put("versionName", pi.versionName == null ? "" : pi.versionName);
            o.put("versionCode", Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (Throwable t) {
            try {
                o.put("installed", false);
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}

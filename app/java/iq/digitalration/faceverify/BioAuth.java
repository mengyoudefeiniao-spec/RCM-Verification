package iq.digitalration.faceverify;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.UserNotAuthenticatedException;

import org.json.JSONObject;

import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Signature;

/**
 * 手机本机生物识别登录（TV-05）。
 *
 * <p>不是"弹个框就算登录"：登录令牌必须由**绑定了用户认证的 Keystore 私钥**签名，
 * 也就是没有过一次生物识别（或锁屏密码）就拿不到签名。这样能同时验证三件事：
 * <ol>
 *   <li>认证耗时（判据 ≤1s）；</li>
 *   <li>拿不到认证就无法签名（{@link #enforcementProbe()}）；</li>
 *   <li>录入新指纹/面容后旧密钥会失效（{@code setInvalidatedByBiometricEnrollment(true)}，
 *       表现为 {@code KeyPermanentlyInvalidatedException}），旧令牌自然作废。</li>
 * </ol>
 *
 * <p>API 28+ 用 {@code android.hardware.biometrics.BiometricPrompt}（工程没有 androidx，
 * 用不了 androidx.biometric）；API 24—27 退化为系统锁屏确认 + 30 秒有效的密钥。
 */
public final class BioAuth {

    public static final int REQ_KEYGUARD = 0x8A21;

    public interface Callback {
        void onResult(JSONObject result);
    }

    private static Callback pending;
    private static String pendingUser;
    private static long pendingStart;

    private BioAuth() {}

    // ------------------------------------------------------------ 能力

    public static JSONObject capabilities(Context c) {
        JSONObject o = new JSONObject();
        try {
            KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("apiPrompt", Build.VERSION.SDK_INT >= 28);
            o.put("fingerprint", c.getPackageManager().hasSystemFeature("android.hardware.fingerprint"));
            o.put("face", c.getPackageManager().hasSystemFeature("android.hardware.biometrics.face"));
            o.put("iris", c.getPackageManager().hasSystemFeature("android.hardware.biometrics.iris"));
            o.put("deviceSecure", km != null && km.isDeviceSecure());
            o.put("perUseKey", DeviceKey.hasBiometricKey());
            o.put("timedKey", DeviceKey.hasTimedKey());
            // 为什么以前"只弹指纹"：带 CryptoObject 的认证只放 BIOMETRIC_STRONG，
            // 而多数国产机的 2D 人脸属于 BIOMETRIC_WEAK；更要紧的是 public SDK 的
            // KeyProperties 根本没有 AUTH_BIOMETRIC_WEAK —— 弱模态**无法**解锁 Keystore 密钥。
            // 所以人脸登录只能是"不绑定密钥"的那一档（authenticateWeak），报告里如实标注。
            if (Build.VERSION.SDK_INT >= 30) {
                BiometricManager bm = c.getSystemService(BiometricManager.class);
                int strong = bm == null ? -1
                        : bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
                int weak = bm == null ? -1
                        : bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
                int cred = bm == null ? -1
                        : bm.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL);
                o.put("canStrong", strong);
                o.put("canWeak", weak);
                o.put("canCredential", cred);
                o.put("strongAvailable", strong == BiometricManager.BIOMETRIC_SUCCESS);
                // 只能"推断"存在哪些模态：公开 API 没有"是哪种生物识别"的查询
                boolean fp = c.getPackageManager().hasSystemFeature("android.hardware.fingerprint");
                boolean fc = c.getPackageManager().hasSystemFeature("android.hardware.biometrics.face");
                o.put("modalityGuess", fp && fc ? "指纹 + 人脸（都存在；系统弹窗用哪个不可控）"
                        : fp ? "仅指纹"
                        : fc ? "仅人脸（没有指纹时系统弹窗只能用它）"
                        : "无生物识别");
                // 这条是本次的结论：setAllowedAuthenticators 只声明"允许哪些档位"，
                // 不能指定"只要人脸"；OEM 会挑最顺手的那一个（有指纹就永远给指纹）。
                o.put("promptPolicy", "system-decided（应用无法指定模态）");
                o.put("faceLogin", "人脸刷脸走 App 内前置相机（FaceLoginActivity）；"
                        + "系统弹窗那档确认的是「系统认证已通过」，不区分指纹还是人脸");
            } else {
                o.put("faceLogin", "unsupported（API<30 无法放开弱模态，走锁屏确认）");
            }
            String mode = Build.VERSION.SDK_INT >= 28 ? "BiometricPrompt(CryptoObject)"
                    : "Keyguard 锁屏确认 + 30s 密钥";
            o.put("mode", mode);
            o.put("keyguardFallback", !(Build.VERSION.SDK_INT >= 28));
        } catch (Throwable t) {
            try {
                o.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    // ------------------------------------------------------------ 登录

    public static void authenticate(final Activity a, final String user, Callback cb) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                // 强模态（绑定密钥）：只能放 BIOMETRIC_STRONG，否则密钥不会解锁。
                // 人脸走 authenticateWeak()，两条路的证据强度不同，报告里分开记。
                final KeyPair kp = DeviceKey.ensureBiometricKey();
                final String mode = "biometric-prompt-strong";
                final Signature sig = Signature.getInstance("SHA256withECDSA");
                sig.initSign(kp.getPrivate());
                final long t0 = System.currentTimeMillis();

                BiometricPrompt prompt = new BiometricPrompt.Builder(a)
                        .setTitle("本机生物识别登录（强）")
                        .setSubtitle("指纹 / 3D 人脸；通过后用绑定密钥签发登录令牌")
                        .setNegativeButton("取消", a.getMainExecutor(), (dialog, which) ->
                                cb.onResult(fail("用户取消")))
                        .build();
                // API 28 起带 CryptoObject 的版本必须传 CancellationSignal（没有两参数重载）
                prompt.authenticate(new BiometricPrompt.CryptoObject(sig), new CancellationSignal(),
                        a.getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                try {
                                    Signature s = result.getCryptoObject() == null
                                            ? null : result.getCryptoObject().getSignature();
                                    if (s == null) {
                                        cb.onResult(fail("没有得到可用的签名对象"));
                                        return;
                                    }
                                    cb.onResult(sign(a, user, mode, s,
                                            kp.getPublic(), System.currentTimeMillis() - t0));
                                } catch (Throwable t) {
                                    cb.onResult(fail(t.getClass().getSimpleName() + ": " + t.getMessage()));
                                }
                            }

                            @Override
                            public void onAuthenticationError(int code, CharSequence msg) {
                                cb.onResult(fail("认证错误 " + code + "：" + msg));
                            }
                        });
            } catch (Throwable t) {
                cb.onResult(fail("无法发起生物识别：" + t.getClass().getSimpleName() + " " + t.getMessage()));
            }
            return;
        }

        // API 24-27：系统没有 BiometricPrompt.CryptoObject，走锁屏确认 + 30s 密钥
        try {
            KeyguardManager km = (KeyguardManager) a.getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null || !km.isDeviceSecure()) {
                cb.onResult(fail("设备未设置锁屏（无生物识别也无密码），无法绑定认证"));
                return;
            }
            Intent i = km.createConfirmDeviceCredentialIntent("本机生物识别登录", "用锁屏密码或指纹确认身份");
            if (i == null) {
                cb.onResult(fail("系统没有可用的锁屏确认界面"));
                return;
            }
            pending = cb;
            pendingUser = user;
            pendingStart = System.currentTimeMillis();
            a.startActivityForResult(i, REQ_KEYGUARD);
        } catch (Throwable t) {
            cb.onResult(fail("无法发起锁屏确认：" + t.getMessage()));
        }
    }

    /** 由 Activity.onActivityResult 转发；返回 true 表示这次结果属于本模块。 */
    public static boolean onActivityResult(Activity a, int requestCode, int resultCode, Callback cb) {
        if (requestCode != REQ_KEYGUARD) return false;
        String user = pendingUser == null ? "operator" : pendingUser;
        Callback target = cb != null ? cb : pending;
        pending = null;
        pendingUser = null;
        if (target == null) return true;
        if (resultCode != Activity.RESULT_OK) {
            target.onResult(fail("用户取消或锁屏验证失败"));
            return true;
        }
        try {
            KeyPair kp = DeviceKey.ensureTimedAuthKey();
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign(kp.getPrivate());
            target.onResult(sign(a, user, "keyguard-timed", sig, kp.getPublic(),
                    System.currentTimeMillis() - pendingStart));
        } catch (Throwable t) {
            target.onResult(fail("锁屏通过但签名失败：" + t.getClass().getSimpleName() + " " + t.getMessage()));
        }
        return true;
    }

    private static JSONObject sign(Activity a, String user, String mode, Signature sig,
                                   PublicKey pub, long ms) throws Exception {
        return sign(a, user, mode, sig, pub, ms, null);
    }

    /** @param extra 追加进令牌 payload 的字段（例如系统认证的 authType），可为 null。 */
    private static JSONObject sign(Activity a, String user, String mode, Signature sig,
                                   PublicKey pub, long ms, JSONObject extra) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("user", user == null ? "" : user);
        payload.put("issuedAt", System.currentTimeMillis());
        payload.put("deviceId", Receipt.deviceId(a));
        payload.put("scope", "login");
        if (extra != null) {
            java.util.Iterator<String> it = extra.keys();
            while (it.hasNext()) {
                String k = it.next();
                payload.put(k, extra.opt(k));
            }
        }
        String data = Receipt.canonical(payload);
        sig.update(data.getBytes("UTF-8"));
        byte[] sigBytes = sig.sign();
        boolean ok = DeviceKey.verify(data.getBytes("UTF-8"), sigBytes, pub);

        JSONObject r = new JSONObject();
        r.put("ok", ok);
        r.put("mode", mode);
        r.put("ms", ms);
        r.put("payload", payload);
        r.put("payloadText", data);
        r.put("pubSha256", DeviceKey.publicSha256());
        r.put("sig", DeviceKey.b64(sigBytes));
        r.put("verified", ok);
        if (!ok) r.put("reason", "签名未通过本机公钥验证");
        return r;
    }

    private static JSONObject fail(String reason) {
        JSONObject r = new JSONObject();
        try {
            r.put("ok", false);
            r.put("reason", reason);
        } catch (Throwable ignored) {
        }
        return r;
    }

    // ------------------------------------------------------------ 认证强制

    /**
     * 不过生物识别直接签一次：应当抛 UserNotAuthenticatedException。
     * 这证明"令牌不能被后台偷偷签发"，也能顺手发现旧密钥被生物特征变更作废的情况。
     */
    public static JSONObject enforcementProbe() {
        JSONObject r = new JSONObject();
        try {
            // 探测的必须是**强模态那把钥匙**（绑定密钥的那把），否则结论对不上
            KeyPair kp = DeviceKey.ensureBiometricKey();
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign(kp.getPrivate());
            sig.update("probe".getBytes("UTF-8"));
            sig.sign();
            r.put("blocked", false);
            r.put("reason", "未认证也能签名（异常：密钥未绑定用户认证）");
        } catch (UserNotAuthenticatedException e) {
            try {
                r.put("blocked", true);
                r.put("reason", "未通过认证时签名被拒绝（符合预期）");
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            try {
                boolean invalidated = t.getClass().getName().contains("KeyPermanentlyInvalidated");
                r.put("blocked", true);
                r.put("invalidated", invalidated);
                r.put("reason", (invalidated
                        ? "密钥已因录入新生物特征/锁屏变更而作废，需重新登录（符合预期）"
                        : "签名失败：" + t.getClass().getSimpleName() + " " + t.getMessage()));
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    /** 换绑/清空：删掉绑定密钥，模拟"生物变更后旧凭据失效"。 */
    public static void resetKey() {
        DeviceKey.deleteBiometricKey();
    }

    // ------------------------------------------------------------ 人脸 / 弱模态登录

    /**
     * 人脸登录：系统弹窗放开到 {@code BIOMETRIC_WEAK}，2D 人脸、屏下光学指纹都会出现。
     *
     * <p><b>代价必须写清楚</b>：weak 模态解锁不了 Keystore 密钥（public SDK 里没有
     * {@code AUTH_BIOMETRIC_WEAK}），所以这条路没有 CryptoObject —— 签名用的是普通设备密钥。
     * 它只能证明"这台设备发起了这次登录"，<b>不能</b>证明"签名那一刻用户刚做过生物识别"。
     * 结果里 {@code cryptoBound=false} 就是这个意思，报告里也照实写。
     */
    public static void authenticateWeak(final Activity a, final String user, final Callback cb) {
        if (Build.VERSION.SDK_INT < 30) {
            cb.onResult(fail("API < 30：系统不允许放开弱生物识别，无法用人脸登录"));
            return;
        }
        try {
            final long t0 = System.currentTimeMillis();
            BiometricPrompt prompt = new BiometricPrompt.Builder(a)
                    .setTitle("人脸 / 弱模态登录")
                    .setSubtitle("系统允许的全部生物识别（含 2D 人脸）")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    .setConfirmationRequired(false)
                    .setNegativeButton("取消", a.getMainExecutor(), (dialog, which) ->
                            cb.onResult(fail("用户取消")))
                    .build();

            prompt.authenticate(new CancellationSignal(), a.getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            try {
                                KeyPair kp = DeviceKey.ensureSigningKey();
                                Signature sig = Signature.getInstance("SHA256withECDSA");
                                sig.initSign(kp.getPrivate());
                                JSONObject r = sign(a, user, "biometric-prompt-weak", sig,
                                        kp.getPublic(), System.currentTimeMillis() - t0);
                                r.put("cryptoBound", false);
                                r.put("authType", result.getAuthenticationType());
                                r.put("note", "弱模态：签名不绑定生物认证，仅记录系统回调结果");
                                cb.onResult(r);
                            } catch (Throwable t) {
                                cb.onResult(fail("人脸通过但签发令牌失败：" + t.getClass().getSimpleName()));
                            }
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence msg) {
                            cb.onResult(fail("认证错误 " + code + "：" + msg));
                        }
                    });
        } catch (Throwable t) {
            cb.onResult(fail("无法发起人脸认证：" + t.getClass().getSimpleName() + " " + t.getMessage()));
        }
    }

    // ------------------------------------------------------------ 系统认证（指纹 / 人脸 / 密码）

    /**
     * 系统认证通道：指纹、2D/3D 人脸、锁屏密码**任一**通过，都算"系统认证通过"，随后用设备密钥签发凭据。
     *
     * <p><b>能证明的</b>：这次登录确实过了一次系统认证（应用内伪造不了），
     * {@code payload.authType} 记下系统回的是"生物识别"还是"设备凭据"。
     *
     * <p><b>不能证明的</b>：具体是指纹还是人脸。BiometricPrompt 的公开 API 既不提供模态信息，
     * 也不允许"只要人脸"——有指纹的机器上 OEM 一律先给指纹（Redmi 25102RKBEC / Android 16 实测）。
     * 想拿到"确实是这张脸"的证据，走 {@link FaceLoginActivity}（前置相机 + 活体 + 端侧比对）。
     *
     * <p>与 {@link #authenticate} 的区别：这里没有 CryptoObject，签名不绑定生物认证
     * （{@code cryptoBound=false}）；好处是可以把"系统认证"这一事实（含密码兜底）固化成票据。
     */
    public static void authenticateSystem(final Activity a, final String user, final Callback cb) {
        if (Build.VERSION.SDK_INT < 30) {
            // API 30 以下没有 setAllowedAuthenticators，退回锁屏确认（密码/生物识别都行）
            authenticate(a, user, cb);
            return;
        }
        try {
            final long t0 = System.currentTimeMillis();
            BiometricPrompt prompt = new BiometricPrompt.Builder(a)
                    .setTitle("系统认证（指纹 / 人脸 / 密码）")
                    .setSubtitle("通过系统认证即可；用哪种方式由系统决定")
                    // 注意：含 DEVICE_CREDENTIAL 时**不能**再设 negative button，否则构造直接抛异常
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                            | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build();
            prompt.authenticate(new CancellationSignal(), a.getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            try {
                                int type = result.getAuthenticationType();
                                boolean biometric =
                                        type == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC;
                                KeyPair kp = DeviceKey.ensureSigningKey();
                                Signature sig = Signature.getInstance("SHA256withECDSA");
                                sig.initSign(kp.getPrivate());
                                JSONObject extra = new JSONObject();
                                extra.put("systemAuth", true);
                                extra.put("authMode", "system-prompt");
                                extra.put("authType", type);
                                extra.put("authTypeText", biometric
                                        ? "BIOMETRIC（指纹或人脸，系统不细分）"
                                        : "DEVICE_CREDENTIAL（锁屏密码/图案）");
                                JSONObject r = sign(a, user, "system-prompt", sig, kp.getPublic(),
                                        System.currentTimeMillis() - t0, extra);
                                r.put("cryptoBound", false);
                                r.put("note", "系统认证已通过并签发凭据；BiometricPrompt 不告诉应用用的是"
                                        + "指纹还是人脸，人脸证据见 faceAuth（前置相机那条路）");
                                cb.onResult(r);
                            } catch (Throwable t) {
                                cb.onResult(fail("系统认证通过但签发凭据失败："
                                        + t.getClass().getSimpleName() + " " + t.getMessage()));
                            }
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence msg) {
                            cb.onResult(fail("系统认证错误 " + code + "：" + msg));
                        }
                    });
        } catch (Throwable t) {
            cb.onResult(fail("无法发起系统认证：" + t.getClass().getSimpleName() + " " + t.getMessage()));
        }
    }
}

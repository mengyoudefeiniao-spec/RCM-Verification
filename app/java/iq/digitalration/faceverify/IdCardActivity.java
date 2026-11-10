package iq.digitalration.faceverify;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import iq.digitalration.faceverify.card.CardSession;
import iq.digitalration.faceverify.card.IcaoReader;
import iq.digitalration.faceverify.card.Mrz;
import iq.digitalration.faceverify.ocr.CardFields;
import iq.digitalration.faceverify.ocr.OcrCore;

/**
 * 证件核验：① 卡背光学识别（家庭编号 + MRZ）② NFC 读 ICAO 9303 芯片（DG1/DG2/DG11/SOD）。
 *
 * <p>两步是配套的：MRZ 里只有证件号/出生/有效期，芯片的 BAC/PACE 密码要用它们派生，
 * 所以先用 OCR 读卡背拿到 MRZ，再去贴卡读芯片；也允许手输这三项作为兜底。
 */
public class IdCardActivity extends Activity implements CameraController.FrameListener {

    private static final int REQ_CAM = 3001;
    private static final int REQ_PICK = 3002;

    private ImageView preview;
    private TextView statusTv, familyTv, ocrTv, nfcTv, compareTv, logTv, hintTv;
    private ImageView faceIv;
    private Button shotBtn, pickBtn, nfcBtn, manualBtn, nfcLogBtn;
    private CameraController cam;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile Bitmap lastFrame;
    private volatile boolean busy;
    private volatile String mrzInfo;
    private volatile String ocrFamilyNo;
    private volatile String ocrDocNumber;
    /** 本次读卡拿到的卡片 ATS/历史字节（写进日志，便于判断卡类型）。 */
    private volatile String lastAts = "";
    /** 最近一次读卡日志文件（可分享）。 */
    private volatile java.io.File lastNfcLog;
    /** 最近一次卡背 OCR 日志（每行原文，用于分析"看着清楚却不识别"）。 */
    private volatile java.io.File lastOcrLog;

    private NfcAdapter nfcAdapter;
    private volatile boolean reading;
    private volatile boolean destroyed;
    /** 单次读卡进行中（用于秒表，避免现场怀疑"没贴够时间"）。 */
    private volatile boolean nfcBusy;
    private volatile long nfcStartMs;

    private final Runnable nfcTick = new Runnable() {
        @Override public void run() {
            if (!nfcBusy || destroyed) return;
            long s = Math.max(0, (System.currentTimeMillis() - nfcStartMs) / 1000);
            statusTv.setText("读卡中… 已 " + s + " 秒 · 保持贴合不要移动（读人像通常 5~15 秒）");
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);   // buildUi 里要用它决定提示文案
        setContentView(buildUi());
        if (nfcAdapter == null) {
            nfcTv.setText("这台设备没有 NFC，只能做光学识别。");
        } else if (nfcBtn != null) {
            nfcBtn.setEnabled(true);
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
        } else {
            initCamera();
        }
    }

    private void initCamera() {
        cam = new CameraController(this, this);
        // 拍卡背用后置摄像头（前置是给人脸核验用的）；1280x960 兼顾"MRZ 每字符 ~20px"与每帧 3.7MB
        cam.setBackCamera(true);
        cam.setTargetSize(1280, 960);
        cam.setRotationOverride(RefStore.rotOverride(this));
        cam.start();
        if (!hasBackCamera()) {
            hintTv.setText("这台设备没有后置摄像头，只能用前置拍卡背；请让卡背正对镜头");
        }
    }

    private boolean hasBackCamera() {
        try {
            android.hardware.camera2.CameraManager cm =
                    (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                if (f != null && f == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] g) {
        super.onRequestPermissionsResult(req, p, g);
        if (req == REQ_CAM) {
            if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) initCamera();
            else toast("需要相机权限（也可以用相册选择）");
        }
    }

    private volatile long lastPreviewMs;

    /** 最近几帧（间隔 ≥120ms），拍摄时取 2 帧做投票，单帧错一个字符能被纠正。 */
    private final List<Bitmap> recent = new ArrayList<>();
    private volatile long lastKeepMs;

    @Override
    public void onFrame(Bitmap bmp, long ts) {
        if (destroyed) return;
        lastFrame = bmp;
        long now = System.currentTimeMillis();
        if (now - lastKeepMs >= 120) {
            lastKeepMs = now;
            synchronized (recent) {
                recent.add(bmp);
                while (recent.size() > 3) recent.remove(0);
            }
        }
        // 1280x960 每帧 3.7MB，30fps 全推给 ImageView 会明显掉帧，限到 ~6fps
        if (now - lastPreviewMs < 160) return;
        lastPreviewMs = now;
        ui.post(() -> {
            if (!destroyed) preview.setImageBitmap(bmp);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cam == null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initCamera();
        }
        // 从系统设置打开 NFC 后回到本页：MRZ 已就绪就自动恢复感应
        if (mrzInfo != null) armReader(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopReader();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopReader();
        if (cam != null) cam.stop();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 光学识别

    private void shootAndOcr() {
        List<Bitmap> frames = new ArrayList<>();
        synchronized (recent) {
            frames.addAll(recent);
        }
        if (frames.isEmpty() && lastFrame != null) frames.add(lastFrame);
        if (frames.isEmpty()) {
            toast("相机还没出画面，稍等一下或改用相册");
            return;
        }
        // 取最后 2 帧：一帧就够时第二帧不会再跑（见 runOcrInternal 的提前返回）
        if (frames.size() > 2) frames = new ArrayList<>(frames.subList(frames.size() - 2, frames.size()));
        runOcr(frames, "拍摄");
    }

    private void pickAndOcr() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            toast("无法打开相册：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        statusTv.setText("正在解码图片…");
        new Thread(() -> {
            try {
                Bitmap bmp = ImgUtil.decodeUri(this, uri, 1600);
                if (bmp == null) throw new IllegalStateException("图片解码失败");
                List<Bitmap> one = new ArrayList<>();
                one.add(bmp);
                runOcrInternal(one, "相册");
            } catch (Throwable t) {
                ui.post(() -> statusTv.setText("识别失败：" + t.getMessage()));
            }
        }, "ocr-decode").start();
    }

    private void runOcr(List<Bitmap> frames, String from) {
        busy = true;
        statusTv.setText("正在识别（" + from + "）…首次会加载 OCR 模型，约 3~6 秒");
        new Thread(() -> runOcrInternal(frames, from), "ocr").start();
    }

    private void runOcrInternal(List<Bitmap> frames, String from) {
        try {
            long t0 = System.currentTimeMillis();
            App app = App.I;
            if (!app.ready) {
                long w0 = System.currentTimeMillis();
                while (!app.ready && app.error == null && System.currentTimeMillis() - w0 < 120000) {
                    Thread.sleep(100);
                }
            }
            iq.digitalration.faceverify.ocr.OcrEngine engine = app.ocrEngine();

            // 逐帧识别：第一帧就齐了（户号 + MRZ 可用）就不再跑第二帧；否则两帧投票
            List<CardFields> results = new ArrayList<>();
            StringBuilder detail = new StringBuilder();
            int used = 0;
            for (Bitmap bmp : frames) {
                if (busy && used >= 2) break;
                long f0 = System.currentTimeMillis();
                // 第一帧做深度识别（下半部放大补扫 + 条带兜底），第二帧只做全图，
                // 否则两帧都跑兜底会把一次识别拖到 20 秒以上
                OcrCore.Scan scan = engine.scan(bmp, used == 0);
                CardFields cf = CardFields.parse(scan.lines);
                results.add(cf);
                used++;
                detail.append(String.format(Locale.US, "— 第 %d 帧 · 方向 %d° · %d 行 · %dms%n",
                        used, scan.rotation, scan.lines.size(), System.currentTimeMillis() - f0));
                for (OcrCore.Line l : scan.lines) {
                    // 带上框坐标：MRZ 被切碎时，只有坐标能判断"两段是不是同一行、聚类对不对"
                    detail.append(String.format(Locale.US, "   %4.0f%%  %-42s x=%d y=%d w=%d h=%d%n",
                            l.conf * 100, l.text, (int) l.cx, (int) l.cy, (int) l.w, (int) l.h));
                }
                if (cf.familyNo != null && cf.mrzUsable()) break;
            }
            CardFields cf = CardFields.vote(results);
            long ms = System.currentTimeMillis() - t0;

            ocrFamilyNo = cf.familyNo;
            ocrDocNumber = cf.docNumber;
            if (cf.mrzUsable()) mrzInfo = cf.mrzInfo();

            detail.append('\n').append(String.format(Locale.US, "%d 帧 · %dms%n", used, ms));
            if (cf.mrzUsable()) detail.append("MRZi: ").append(cf.mrzInfo()).append('\n');
            final String detailText = detail.toString();
            final int framesUsed = used;

            // 现场验证清单：登记本次光学识别结果（纯本机文件）
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("familyNo", cf.familyNo == null ? "" : cf.familyNo);
                o.put("familyConf", cf.familyConf);
                o.put("familyLabel", cf.familyLabel == null ? "" : cf.familyLabel);
                org.json.JSONArray alt = new org.json.JSONArray();
                for (String a : cf.familyAlternatives) alt.put(a);
                o.put("alternatives", alt);
                o.put("docNumber", cf.docNumber == null ? "" : cf.docNumber);
                o.put("dob", cf.dob == null ? "" : Mrz.fmtDate(cf.dob));
                o.put("expiry", cf.expiry == null ? "" : Mrz.fmtDate(cf.expiry));
                o.put("checkDoc", cf.checkDocOk);
                o.put("checkDob", cf.checkDobOk);
                o.put("checkExpiry", cf.checkExpiryOk);
                o.put("mrzFound", cf.mrzFound);
                o.put("mrzUsable", cf.mrzUsable());
                o.put("frames", framesUsed);
                o.put("ms", ms);
                LabStore.put(this, "ocr", o);
            } catch (Throwable ignored) {
            }

            ui.post(() -> {
                busy = false;
                boolean mrzChecksOk = cf.checkDocOk && cf.checkDobOk && cf.checkExpiryOk;
                String mrzState = !cf.mrzUsable() ? "MRZ 不完整，可手输补全"
                        : mrzChecksOk ? "MRZ 可用，可贴卡读芯片"
                        : "MRZ 可用但校验位有误，读卡可能失败（建议手输）";
                statusTv.setText((cf.familyNo != null ? "户号已识别" : "户号未识别")
                        + " · " + mrzState + "（" + framesUsed + " 帧 · " + ms + "ms）");
                familyTv.setText(cf.familyNo == null ? "未识别到家庭编号" : cf.familyNo);
                familyTv.setTextColor(cf.familyNo == null ? 0xFFFFB020 : 0xFF5FD4A0);

                StringBuilder r = new StringBuilder();
                if (cf.familyLabel != null && !cf.familyLabel.isEmpty()) {
                    r.append("标签：").append(cf.familyLabel).append("（الرقم العائلي / ژمارەی خێزانی）\n");
                }
                if (cf.familyAlternatives.size() > 1) {
                    r.append("候选：");
                    for (int i = 0; i < cf.familyAlternatives.size(); i++) {
                        r.append(i == 0 ? "" : "  /  ").append(cf.familyAlternatives.get(i));
                    }
                    r.append("\n（有歧义时请对照卡面选，或用下面「改正家庭编号」）\n");
                }
                r.append("证件号：").append(cf.docNumber == null ? "—" : cf.docNumber).append('\n');
                r.append("出生：").append(cf.dob == null ? "—" : Mrz.fmtDate(cf.dob)).append('\n');
                r.append("有效期：").append(cf.expiry == null ? "—" : Mrz.fmtDate(cf.expiry)).append('\n');
                r.append("证件号校验：").append(cf.checkDocOk ? "✓" : "✗")
                        .append("   出生校验：").append(cf.checkDobOk ? "✓" : "✗")
                        .append("   有效期校验：").append(cf.checkExpiryOk ? "✓" : "✗").append('\n');
                if (cf.mrzFound) {
                    r.append("MRZ：").append(cf.mrzLine1).append('\n').append("     ").append(cf.mrzLine2).append('\n');
                }
                if (!cf.mrzUsable()) {
                    r.append("\n提示：MRZ 三行在卡片下部、字号很小。请把卡**靠近**手机（让卡片占满取景框）再拍一次；\n")
                            .append("      仍读不到就用下面「手输 MRZ」填三项（证件号 / 出生 / 有效期），读芯片同样有效。\n");
                }
                ocrTv.setText(r.toString());
                ocrTv.setVisibility(View.VISIBLE);

                nfcBtn.setEnabled(nfcAdapter != null);
                if (mrzInfo != null) {
                    nfcBtn.setText("② 贴卡读取芯片（靠近自动识别）");
                    armReader(true);          // MRZ 一就绪就自动开着感应，贴卡即读
                }
            });
            // OCR 日志：每行原文 + 方向 + 耗时。现场"看着清楚却识别不到"时，这份日志是唯一的判断依据
            lastOcrLog = LabStore.saveTextLog(this, "ocr-log", buildOcrLog(cf, detailText, framesUsed, ms));
            ui.post(() -> logTv.setText(detailText));
        } catch (Throwable t) {
            ui.post(() -> {
                busy = false;
                statusTv.setText("识别失败：" + t.getClass().getSimpleName() + " " + t.getMessage());
            });
        }
    }

    // ---------------------------------------------------------------- NFC

    /** 点按钮：有 MRZ 就直接进入"自动感应"，没有就让人先手输。 */
    private void startReading() {
        if (mrzInfo == null) {
            askManualMrz();
            return;
        }
        armReader(false);
    }

    /**
     * 打开 NFC 自动感应（ReaderMode）：卡一贴上来就自动读，不需要再点按钮。
     * 读成功后会关掉感应，避免反复触发；失败时保持感应，重新贴卡即可重试。
     */
    private void armReader(boolean auto) {
        if (reading) return;
        if (nfcAdapter == null) {
            nfcTv.setText("这台设备没有 NFC，只能用①光学识别。");
            return;
        }
        if (!nfcAdapter.isEnabled()) {
            nfcTv.setText("NFC 未开启：请到 系统设置 → 连接 → NFC 打开，然后回到这里（会自动重新感应）。");
            toast("请先打开系统 NFC");
            return;
        }
        if (mrzInfo == null) {
            return;
        }
        reading = true;
        nfcTv.setText((auto ? "MRZ 已就绪，NFC 自动感应中。\n" : "NFC 自动感应中。\n")
                + "把证件背面（有照片/国徽那一面）贴到手机 NFC 区域——"
                + "大多数机型在背面上半部/摄像头附近，贴上后会自动开始读取，不用点按钮。");
        nfcBtn.setText("② 正在感应…把卡贴上来");
        Bundle opts = new Bundle();
        // 平台每 N 毫秒做一次"卡还在不在"的探测。250ms 对部分 eMRTD 芯片太密：
        // 探测本身会插一次 ISO-DEP 交互，遇到不喜欢的帧就可能把会话重置（现场表现成 TagLost）。
        opts.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 600);
        try {
            nfcAdapter.enableReaderMode(this, tag -> onTag(tag),
                    NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                            | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, opts);
        } catch (Throwable t) {
            reading = false;
            nfcTv.setText("无法开启 NFC 感应：" + t.getMessage());
        }
    }

    private void stopReader() {
        if (nfcAdapter != null && reading) {
            try {
                nfcAdapter.disableReaderMode(this);
            } catch (Throwable ignored) {
            }
        }
        reading = false;
        if (nfcBtn != null) {
            nfcBtn.setText(mrzInfo == null ? "② 贴卡读取芯片" : "② 贴卡读取芯片（靠近自动识别）");
        }
    }

    /** ReaderMode 回调在 binder 线程，直接在这里阻塞读卡；成功后关闭感应，失败保持感应等重贴。 */
    private void onTag(Tag tag) {
        final IsoDep iso = IsoDep.get(tag);
        if (iso == null) {
            ui.post(() -> nfcTv.setText("感应到的是普通 NFC 标签，不是 ISO-DEP（ISO 14443-4）证件芯片。"
                    + "\n请确认贴的是证件有芯片的那一面。"));
            return;
        }
        final String mrzi = mrzInfo;
        nfcBusy = true;
        nfcStartMs = System.currentTimeMillis();
        ui.post(nfcTick);
        ui.post(() -> nfcTv.setText("已感应到芯片，正在建立安全通道…（保持贴合，读取人像需要 5~15 秒）"));
        boolean ok = false;
        String atsText = "";
        try {
            iso.connect();
            iso.setTimeout(8000);
            // 卡片类型诊断：ATS/历史字节能区分"读不到是卡太远"还是"卡片本身不按 ICAO 组织"
            try {
                byte[] hb = iso.getHistoricalBytes();
                byte[] hi = iso.getHiLayerResponse();
                atsText = (hb == null ? "" : DeviceKey.hex(hb))
                        + (hi == null ? "" : (hb == null ? "" : " / ") + DeviceKey.hex(hi));
            } catch (Throwable ignored) {
            }
            lastAts = atsText;
            IcaoReader.Result r = IcaoReader.read(new CardSession.Transport() {
                @Override public byte[] transceive(byte[] command) throws Exception {
                    return iso.transceive(command);
                }
            }, mrzi, text -> ui.post(() -> nfcTv.setText(text)));
            ok = r.ok;
            try {
                iso.close();
            } catch (Throwable ignored) {
            }
            ui.post(() -> showNfcResult(r));
            if (!r.ok) {
                final String fAts = atsText;
                ui.post(() -> nfcTv.append("\n\n重新把卡贴上来可以再试一次（仍在自动感应中）。"
                        + (fAts.isEmpty() ? "" : "\n卡片 ATS/历史字节：" + fAts)));
            }
        } catch (Throwable t) {
            try {
                iso.close();
            } catch (Throwable ignored) {
            }
            final String msg = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " " + t.getMessage());
            final String fAts = atsText;
            ui.post(() -> nfcTv.setText("读卡中断：" + msg
                    + (fAts.isEmpty() ? "" : "\n卡片 ATS/历史字节：" + fAts)
                    + "\n\n重新把卡贴上来可以再试一次（仍在自动感应中）。"));
        } finally {
            nfcBusy = false;
            if (ok) ui.post(this::stopReader);
        }
    }

    /** 读卡日志正文：设备 / 卡片 / 结果 / 每步日志 —— 失败时现场把这份 txt 发回来即可定位。 */
    private String buildNfcLog(IcaoReader.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 证件核验 · NFC 读卡日志 ===\n");
        sb.append("时间：").append(DeviceInfo.now()).append('\n');
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            sb.append("应用：").append(getPackageName()).append(' ')
                    .append(pi.versionName).append(" (").append(pi.versionCode).append(")\n");
        } catch (Throwable ignored) {
            sb.append("应用：").append(getPackageName()).append('\n');
        }
        sb.append("设备：").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append(" / Android ")
                .append(android.os.Build.VERSION.RELEASE).append(" / sdk ")
                .append(android.os.Build.VERSION.SDK_INT).append('\n');
        sb.append("AID：A0000002471001\n");
        sb.append("MRZi：").append(mrzInfo == null ? "（未就绪）" : mrzInfo).append('\n');
        sb.append("卡片 ATS/历史字节：")
                .append(lastAts == null || lastAts.isEmpty() ? "（未取到）" : lastAts).append('\n');
        sb.append("结果：").append(r.ok ? "成功" : "失败").append('\n');
        if (!r.ok && r.error != null) sb.append("失败原因：").append(r.error).append('\n');
        sb.append("安全通道：").append(r.accessControl == null ? "（未建立）" : r.accessControl).append('\n');
        sb.append("APDU：").append(r.apduCount).append(" 条 · ").append(r.ms).append("ms\n");
        sb.append("\n--- 步骤日志 ---\n");
        for (String l : r.log) sb.append(l).append('\n');
        return sb.toString();
    }

    /** 卡背 OCR 日志：设备 / 结果 / 每帧每行原文（"看着清楚却不识别"只能靠这份判断）。 */
    private String buildOcrLog(CardFields cf, String detail, int frames, long ms) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 证件核验 · 卡背 OCR 日志 ===\n");
        sb.append("时间：").append(DeviceInfo.now()).append('\n');
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            sb.append("应用：").append(getPackageName()).append(' ')
                    .append(pi.versionName).append(" (").append(pi.versionCode).append(")\n");
        } catch (Throwable ignored) {
            sb.append("应用：").append(getPackageName()).append('\n');
        }
        sb.append("设备：").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append(" / Android ")
                .append(android.os.Build.VERSION.RELEASE).append(" / sdk ")
                .append(android.os.Build.VERSION.SDK_INT).append('\n');
        sb.append("帧数：").append(frames).append(" · 耗时：").append(ms).append("ms\n");
        sb.append("结果：户号=").append(cf.familyNo == null ? "未识别" : cf.familyNo)
                .append("  MRZ可用=").append(cf.mrzUsable())
                .append("  校验位(doc/dob/exp)=").append(cf.checkDocOk).append('/')
                .append(cf.checkDobOk).append('/').append(cf.checkExpiryOk).append('\n');
        sb.append("\n--- 每帧每行 OCR 原文（百分比 = 置信度）---\n").append(detail).append('\n');
        return sb.toString();
    }

    /**
     * 一键导出识别日志：把卡背 OCR 日志与 NFC 读卡日志合并成一份 txt，打成 zip 再分享。
     * zip 是微信/邮件都认的形态（直接发 txt 时微信对 text/plain 的 content URI 支持不稳）。
     */
    private void shareDiagLog() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 证件核验 · 识别日志（OCR + NFC）===\n");
        sb.append("时间：").append(DeviceInfo.now()).append("\n\n");
        boolean any = false;
        if (lastOcrLog != null && lastOcrLog.isFile()) {
            sb.append("--- 卡背 OCR 日志 ---\n").append(readText(lastOcrLog)).append('\n');
            any = true;
        }
        java.io.File nfc = lastNfcLog != null ? lastNfcLog : LabStore.latestNfcLog(this);
        if (nfc != null && nfc.isFile()) {
            sb.append("--- NFC 读卡日志 ---\n").append(readText(nfc)).append('\n');
            any = true;
        }
        if (!any) {
            toast("还没有识别日志：先拍一次卡背，或贴卡读一次");
            return;
        }
        java.io.File txt = LabStore.saveTextLog(this, "idcard-diag", sb.toString());
        if (txt == null) {
            toast("日志写入失败");
            return;
        }
        java.io.File zip = LabStore.zipLog(this, txt);
        final java.io.File send = zip != null ? zip : txt;
        toast("日志已生成：\n" + send.getAbsolutePath());
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(zip != null ? "application/zip" : "text/plain");
            i.putExtra(Intent.EXTRA_STREAM, ReportProvider.uriFor(this, send));
            i.putExtra(Intent.EXTRA_SUBJECT, "证件核验识别日志 " + send.getName());
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "发送识别日志"));
        } catch (Throwable t) {
            toast("没有可用的分享目标：" + t.getMessage() + "\n文件在：" + send.getAbsolutePath());
        }
    }

    private static String readText(java.io.File f) {
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            in.close();
            return new String(b, 0, n, "UTF-8");
        } catch (Throwable t) {
            return "（读取失败：" + t.getMessage() + "）";
        }
    }

    private void showNfcResult(IcaoReader.Result r) {
        // 顶部状态给一个"读卡用时"的结论：现场一眼能看出是"读完失败"还是"刚贴上就断"
        statusTv.setText((r.ok ? "读卡完成 · " : "读卡失败 · ")
                + r.ms + "ms · " + r.apduCount + " 条 APDU");
        // 每次读卡（成功或失败）都落一份 txt：现场直接分享出来，随报告 ZIP 也会带上
        lastNfcLog = LabStore.saveNfcLog(this, buildNfcLog(r));

        StringBuilder sb = new StringBuilder();
        if (!r.ok) {
            sb.append("✗ ").append(r.error == null ? "读取失败" : r.error).append('\n');
            sb.append(String.format(Locale.US, "%d 条 APDU · %dms（在哪一步断的看下方日志）%n",
                    r.apduCount, r.ms));
        } else {
            sb.append("✓ 读取成功 · ").append(r.accessControl).append('\n');
            sb.append(String.format(Locale.US, "%d 条 APDU · %dms%n", r.apduCount, r.ms));
        }
        Mrz m = r.doc.mrz;
        if (m != null) {
            sb.append("\n【DG1 机读区】\n");
            sb.append("证件号：").append(m.docNumber).append("（").append(m.format).append("）\n");
            sb.append("签发国：").append(m.issuingState).append("   国籍：").append(m.nationality).append('\n');
            sb.append("出生：").append(Mrz.fmtDate(m.dob)).append("   性别：").append(m.sex).append('\n');
            sb.append("有效期：").append(Mrz.fmtDate(m.expiry)).append('\n');
            sb.append("姓名：").append(m.nameDisplay()).append('\n');
            sb.append("校验位：证件号").append(m.docNumberOk ? "✓" : "✗")
                    .append(" 出生").append(m.dobOk ? "✓" : "✗")
                    .append(" 有效期").append(m.expiryOk ? "✓" : "✗")
                    .append(" 综合").append(m.compositeOk ? "✓" : "✗").append('\n');
        }
        if (r.doc.faceImage != null) {
            sb.append("\n【DG2 人像】").append(r.faceInfo()).append('\n');
            Bitmap bmp = decodeChipImage(r.doc.faceImage);
            if (bmp != null) {
                faceIv.setImageBitmap(bmp);
                faceIv.setVisibility(View.VISIBLE);
                sb.append("尺寸 ").append(bmp.getWidth()).append('x').append(bmp.getHeight()).append('\n');
            } else {
                faceIv.setVisibility(View.GONE);
                sb.append("⚠ ").append("JPEG2000".equals(r.doc.faceFormat)
                        ? "芯片人像是 JPEG2000，Android 自带解码器不支持（只能显示字节数）"
                        : "人像解码失败").append('\n');
            }
        }
        if (!r.doc.person.name.isEmpty() || !r.doc.person.personalNumber.isEmpty()) {
            sb.append("\n【DG11 附加信息】\n");
            if (!r.doc.person.name.isEmpty()) sb.append("姓名：").append(r.doc.person.name.replace('<', ' ')).append('\n');
            if (!r.doc.person.personalNumber.isEmpty())
                sb.append("个人编号/家庭编号：").append(r.doc.person.personalNumber).append('\n');
            if (!r.doc.person.fullDob.isEmpty()) sb.append("完整出生日期：").append(r.doc.person.fullDob).append('\n');
            if (!r.doc.person.placeOfBirth.isEmpty()) sb.append("出生地：").append(r.doc.person.placeOfBirth).append('\n');
            if (!r.doc.person.address.isEmpty()) sb.append("地址：").append(r.doc.person.address).append('\n');
        }
        if (r.sod != null) {
            sb.append("\n【被动认证（EF.SOD）】\n");
            sb.append("SOD 签名：").append(r.sod.signatureOk ? "✓ 有效" : "✗ 无效").append('\n');
            sb.append("数据组哈希：").append(r.sod.dgMatched).append('/').append(r.sod.dgVerified)
                    .append(r.sod.dgHashesOk ? " ✓" : " ✗")
                    .append(r.sod.dgMissing > 0 ? "（另有 " + r.sod.dgMissing + " 组未读取）" : "")
                    .append('\n');
            sb.append("证书链自洽：").append(r.sod.chainOk ? "✓（未锚定 CSCA）" : "— 未找到签发者").append('\n');
            sb.append("证书有效期：").append(r.sod.certValidOk ? "✓" : "✗").append('\n');
            sb.append("摘要算法：").append(r.sod.hashAlg).append('\n');
            if (!r.sod.dsSubject.isEmpty()) sb.append("签名证书：").append(r.sod.dsSubject).append('\n');
        }
        nfcTv.setText(sb.toString());

        // 光学 vs 芯片
        StringBuilder cmp = new StringBuilder();
        boolean any = false;
        Boolean docMatch = null;
        Boolean familyMatch = null;
        String familyDiff = "";
        if (ocrDocNumber != null && m != null) {
            any = true;
            docMatch = ocrDocNumber.equals(m.docNumber);
            cmp.append("证件号：光学 ").append(ocrDocNumber).append(" / 芯片 ").append(m.docNumber)
                    .append(docMatch ? "  ✓ 一致\n" : "  ✗ 不一致\n");
        }
        if (ocrFamilyNo != null && !r.doc.person.personalNumber.isEmpty()) {
            any = true;
            String chipFam = CardFields.sanitizeFamily(r.doc.person.personalNumber);
            if (chipFam == null) chipFam = r.doc.person.personalNumber.trim();
            familyMatch = ocrFamilyNo.equals(chipFam);
            cmp.append("家庭编号：光学 ").append(ocrFamilyNo).append(" / 芯片 ").append(chipFam).append(' ');
            if (familyMatch) {
                cmp.append("✓ 一致\n");
            } else {
                String diff = diffPositions(ocrFamilyNo, chipFam);
                familyDiff = diff == null ? "" : diff;
                cmp.append("✗ 不一致");
                if (diff != null) {
                    // 只差 1~3 位 → 基本可以判定是 OCR 认错，芯片里的值是权威值
                    cmp.append("，OCR 读错 ").append(diff).append("，以芯片为准\n");
                    ocrFamilyNo = chipFam;
                    final String chip = chipFam;
                    ui.post(() -> {
                        familyTv.setText(chip);
                        familyTv.setTextColor(0xFF5FD4A0);
                    });
                } else {
                    cmp.append("（长度不同，无法逐位比较）\n");
                }
            }
        }
        compareTv.setVisibility(any ? View.VISIBLE : View.GONE);
        compareTv.setText(any ? "【光学 vs 芯片】\n" + cmp : "");
        saveNfcResult(r, docMatch, familyMatch, familyDiff);

        StringBuilder log = new StringBuilder();
        for (String l : r.log) log.append(l).append('\n');
        logTv.setText(log.toString());
    }

    /**
     * 把这次芯片读取的结果登记到现场验证清单（本机 JSON，不联网）。
     * 报告里要用的就是这几项：读卡是否成功、DG1/DG2/DG11 拿到什么、SOD 四项判定、光学与芯片是否一致。
     */
    private void saveNfcResult(final IcaoReader.Result r, final Boolean docMatch,
                               final Boolean familyMatch, final String familyDiff) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("ok", r.ok);
                    o.put("error", r.error == null ? "" : r.error);
                    o.put("accessControl", r.accessControl == null ? "" : r.accessControl);
                    o.put("apduCount", r.apduCount);
                    o.put("ms", r.ms);

                    Mrz m = r.doc.mrz;
                    if (m != null) {
                        org.json.JSONObject dg1 = new org.json.JSONObject();
                        dg1.put("docNumber", m.docNumber);
                        dg1.put("format", m.format);
                        dg1.put("issuingState", m.issuingState);
                        dg1.put("nationality", m.nationality);
                        dg1.put("expiry", m.expiry);
                        dg1.put("name", m.nameDisplay());
                        dg1.put("checks", m.docNumberOk && m.dobOk && m.expiryOk);
                        o.put("dg1", dg1);
                    }
                    if (r.doc.faceImage != null) {
                        org.json.JSONObject dg2 = new org.json.JSONObject();
                        dg2.put("format", r.doc.faceFormat);
                        dg2.put("bytes", r.doc.faceImage.length);
                        BitmapFactory.Options opt = new BitmapFactory.Options();
                        opt.inJustDecodeBounds = true;
                        try {
                            BitmapFactory.decodeByteArray(r.doc.faceImage, 0, r.doc.faceImage.length, opt);
                        } catch (Throwable ignored) {
                        }
                        dg2.put("decoded", opt.outWidth > 0);
                        dg2.put("w", opt.outWidth);
                        dg2.put("h", opt.outHeight);
                        o.put("dg2", dg2);
                    }
                    if (!r.doc.person.personalNumber.isEmpty()) {
                        org.json.JSONObject dg11 = new org.json.JSONObject();
                        dg11.put("personalNumber", r.doc.person.personalNumber);
                        dg11.put("fullDob", r.doc.person.fullDob);
                        dg11.put("placeOfBirth", r.doc.person.placeOfBirth);
                        dg11.put("address", r.doc.person.address);
                        o.put("dg11", dg11);
                    }
                    if (r.sod != null) {
                        org.json.JSONObject sod = new org.json.JSONObject();
                        sod.put("signatureOk", r.sod.signatureOk);
                        sod.put("dgHashesOk", r.sod.dgHashesOk);
                        sod.put("dgMatched", r.sod.dgMatched);
                        sod.put("dgVerified", r.sod.dgVerified);
                        sod.put("chainOk", r.sod.chainOk);
                        sod.put("certValidOk", r.sod.certValidOk);
                        sod.put("hashAlg", r.sod.hashAlg == null ? "" : r.sod.hashAlg);
                        o.put("sod", sod);
                    }
                    org.json.JSONObject cmp = new org.json.JSONObject();
                    if (docMatch != null) cmp.put("docMatch", docMatch.booleanValue());
                    if (familyMatch != null) {
                        cmp.put("familyMatch", familyMatch.booleanValue());
                        cmp.put("familyDiff", familyDiff == null ? "" : familyDiff);
                    }
                    o.put("compare", cmp);

                    LabStore.put(IdCardActivity.this, "nfc", o);
                    if ("JPEG".equals(r.doc.faceFormat) && r.doc.faceImage != null) {
                        LabStore.saveChipFace(IdCardActivity.this, r.doc.faceImage);
                    }
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }

    /** 逐位比较并描述差异（长度不同返回 null）。 */
    private static String diffPositions(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return null;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) == b.charAt(i)) continue;
            if (n++ > 0) sb.append("，");
            sb.append("第").append(i + 1).append("位 ").append(a.charAt(i)).append("→").append(b.charAt(i));
        }
        return n == 0 ? null : sb.toString();
    }

    private Bitmap decodeChipImage(byte[] data) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = false;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) return null;
            int max = 480;
            if (Math.max(b.getWidth(), b.getHeight()) > max) {
                float s = max / (float) Math.max(b.getWidth(), b.getHeight());
                b = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)),
                        Math.max(1, Math.round(b.getHeight() * s)), true);
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 户号手工改正：OCR 把 M 认成 4 这类错误人工一眼就能看出，给个输入框比反复重拍快。 */
    private void askFixFamily() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        et.setText(ocrFamilyNo == null ? "" : ocrFamilyNo);
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("改正家庭编号")
                .setMessage("18 位（4 位数字 + 1 位字母 + 13 位字母数字），例如 1002L00M9770006805")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String v = CardFields.sanitizeFamily(et.getText().toString());
                    if (v == null) {
                        toast("格式不对：需要 18 位、前 4 位是数字、第 5 位是字母");
                        return;
                    }
                    ocrFamilyNo = v;
                    familyTv.setText(v);
                    familyTv.setTextColor(0xFF5FD4A0);
                    statusTv.setText("已手工改正家庭编号：" + v);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** MRZ 三道字段手输，OCR 失败时兜底。 */
    private void askManualMrz() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(4));

        EditText doc = new EditText(this);
        doc.setHint("证件号（MRZ 第 6~14 位，如 AE0644356）");
        doc.setInputType(InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText dob = new EditText(this);
        dob.setHint("出生日期 YYMMDD（如 910512）");
        dob.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText exp = new EditText(this);
        exp.setHint("有效期 YYMMDD（如 270724）");
        exp.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(doc);
        box.addView(dob);
        box.addView(exp);

        new AlertDialog.Builder(this)
                .setTitle("手输 MRZ 三项")
                .setView(box)
                .setMessage("芯片的 BAC/PACE 密码由 MRZ 的证件号、出生日期、有效期派生；OCR 读不全时手输也能读。")
                .setPositiveButton("确定", (d, w) -> {
                    Mrz m = Mrz.fromFields(doc.getText().toString(), dob.getText().toString(), exp.getText().toString());
                    if (!m.usable()) {
                        toast("三项都需要填写（数字 6 位）");
                        return;
                    }
                    mrzInfo = m.mrzInfo();
                    nfcBtn.setText("② 正在感应…把卡贴上来");
                    startReading();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1117);
        final int screenH = getResources().getDisplayMetrics().heightPixels;
        final int panelH = Math.round(screenH * 0.52f);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(0xFF000000);
        root.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 取景提示框
        FrameLayout guide = new FrameLayout(this);
        View rect = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0x00000000);
        gd.setStroke(dp(2), 0x889CC4FF);
        gd.setCornerRadius(dp(10));
        rect.setBackground(gd);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.leftMargin = dp(24);
        rp.rightMargin = dp(24);
        rp.topMargin = dp(96);
        rp.bottomMargin = panelH + dp(8);
        guide.addView(rect, rp);
        root.addView(guide, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部标题
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(0xCC000000);
        top.setPadding(dp(16), dp(14), dp(16), dp(8));
        TextView title = text("证件核验 · 卡背 OCR + NFC 芯片", 16, 0xFFF2F5FA);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title);
        hintTv = text("把卡背放进取景框（竖着拍也可以），点「拍摄识别」", 12, 0xFF9CC4FF);
        hintTv.setPadding(0, dp(4), 0, 0);
        top.addView(hintTv);
        root.addView(top, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 底部面板
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF210141B);
        panel.setPadding(dp(14), dp(10), dp(14), dp(14));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        shotBtn = smallButton("拍摄识别");
        shotBtn.setOnClickListener(v -> shootAndOcr());
        pickBtn = smallButton("相册识别");
        pickBtn.setOnClickListener(v -> pickAndOcr());
        row.addView(shotBtn, weight());
        row.addView(pickBtn, weight());
        panel.addView(row);

        statusTv = text("等待识别…", 12, 0xFF7FB2FF);
        statusTv.setPadding(0, dp(8), 0, dp(4));
        panel.addView(statusTv);

        familyTv = text("未识别到家庭编号", 20, 0xFF9AA6B8);
        familyTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        familyTv.setTextIsSelectable(true);
        panel.addView(familyTv);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        ocrTv = text("", 12, 0xFFCDD6E3);
        ocrTv.setTypeface(Typeface.MONOSPACE);
        ocrTv.setTextIsSelectable(true);
        ocrTv.setVisibility(View.GONE);
        col.addView(ocrTv);

        manualBtn = smallButton("手输 MRZ（OCR 读不全时）");
        manualBtn.setOnClickListener(v -> askManualMrz());
        col.addView(manualBtn);

        Button fixFamily = smallButton("改正家庭编号（OCR 有歧义时）");
        fixFamily.setOnClickListener(v -> askFixFamily());
        col.addView(fixFamily);

        nfcBtn = smallButton("② 贴卡读取芯片");
        nfcBtn.setEnabled(false);
        nfcBtn.setOnClickListener(v -> startReading());
        col.addView(nfcBtn);

        nfcTv = text(nfcAdapter == null
                ? "本机没有 NFC 模块：只能用①光学识别。"
                : "NFC 感应是自动的：点一下上面的按钮（或先识别 MRZ）后，把卡贴上来就会开始读，不用再按。",
                12, 0xFFCDD6E3);
        nfcTv.setTypeface(Typeface.MONOSPACE);
        nfcTv.setTextIsSelectable(true);
        nfcTv.setPadding(0, dp(8), 0, dp(4));
        col.addView(nfcTv);

        // 识别日志一键导出：OCR 每行原文 + NFC 读卡步骤，合并成 zip（微信可直接发文件）
        nfcLogBtn = smallButton("导出识别日志（OCR + 读卡，一键发微信）");
        nfcLogBtn.setEnabled(true);
        nfcLogBtn.setOnClickListener(v -> shareDiagLog());
        col.addView(nfcLogBtn);

        faceIv = new ImageView(this);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(dp(160), dp(200));
        fp.topMargin = dp(6);
        faceIv.setLayoutParams(fp);
        faceIv.setVisibility(View.GONE);
        faceIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        faceIv.setBackgroundColor(0xFF1A1F29);
        col.addView(faceIv);

        compareTv = text("", 12, 0xFFFFD479);
        compareTv.setTextIsSelectable(true);
        compareTv.setVisibility(View.GONE);
        compareTv.setPadding(0, dp(6), 0, 0);
        col.addView(compareTv);

        logTv = text("", 10, 0xFF6E7A8C);
        logTv.setTextIsSelectable(true);
        logTv.setPadding(0, dp(8), 0, 0);
        col.addView(logTv);

        sv.addView(col);
        panel.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, panelH, Gravity.BOTTOM);
        root.addView(panel, pp);
        return root;
    }

    private Button smallButton(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(13);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.rightMargin = dp(6);
        return lp;
    }

    private TextView text(String t, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.START);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

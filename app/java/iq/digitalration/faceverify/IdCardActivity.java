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
    private Button shotBtn, pickBtn, nfcBtn, manualBtn;
    private CameraController cam;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile Bitmap lastFrame;
    private volatile boolean busy;
    private volatile String mrzInfo;
    private volatile String ocrFamilyNo;
    private volatile String ocrDocNumber;

    private NfcAdapter nfcAdapter;
    private volatile boolean reading;
    private volatile boolean destroyed;

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
                OcrCore.Scan scan = engine.scan(bmp);
                CardFields cf = CardFields.parse(scan.lines);
                results.add(cf);
                used++;
                detail.append(String.format(Locale.US, "— 第 %d 帧 · 方向 %d° · %d 行 · %dms%n",
                        used, scan.rotation, scan.lines.size(), System.currentTimeMillis() - f0));
                for (OcrCore.Line l : scan.lines) {
                    detail.append(String.format(Locale.US, "   %4.0f%%  %s%n", l.conf * 100, l.text));
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
                ChecklistStore.putResult(this, "ocr", o);
            } catch (Throwable ignored) {
            }

            ui.post(() -> {
                busy = false;
                statusTv.setText((cf.familyNo != null ? "户号已识别" : "户号未识别")
                        + " · " + (cf.mrzUsable() ? "MRZ 可用，可贴卡读芯片" : "MRZ 不完整，可手输补全")
                        + "（" + framesUsed + " 帧 · " + ms + "ms）");
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
                ocrTv.setText(r.toString());
                ocrTv.setVisibility(View.VISIBLE);

                nfcBtn.setEnabled(nfcAdapter != null);
                if (mrzInfo != null) {
                    nfcBtn.setText("② 贴卡读取芯片（靠近自动识别）");
                    armReader(true);          // MRZ 一就绪就自动开着感应，贴卡即读
                }
            });
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
        opts.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
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
        ui.post(() -> nfcTv.setText("已感应到芯片，正在建立安全通道…（保持贴合，读取人像需要 5~15 秒）"));
        boolean ok = false;
        try {
            iso.connect();
            iso.setTimeout(8000);
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
                ui.post(() -> nfcTv.append("\n\n重新把卡贴上来可以再试一次（仍在自动感应中）。"));
            }
        } catch (Throwable t) {
            try {
                iso.close();
            } catch (Throwable ignored) {
            }
            final String msg = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " " + t.getMessage());
            ui.post(() -> nfcTv.setText("读卡中断：" + msg
                    + "\n\n重新把卡贴上来可以再试一次（仍在自动感应中）。"));
        } finally {
            if (ok) ui.post(this::stopReader);
        }
    }

    private void showNfcResult(IcaoReader.Result r) {
        StringBuilder sb = new StringBuilder();
        if (!r.ok) {
            sb.append("✗ ").append(r.error == null ? "读取失败" : r.error).append('\n');
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

                    ChecklistStore.putResult(IdCardActivity.this, "nfc", o);
                    if ("JPEG".equals(r.doc.faceFormat) && r.doc.faceImage != null) {
                        ChecklistStore.saveChipFace(IdCardActivity.this, r.doc.faceImage);
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

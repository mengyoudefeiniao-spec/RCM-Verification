package iq.digitalration.faceverify;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Camera2 最小封装：只输出「已旋转为正向的 ARGB 帧」，不建预览 Surface。
 * 界面直接显示这些帧（所见即算法所见），避免 TextureView 变换在不同机型上出错。
 */
public final class CameraController {

    public interface FrameListener {
        void onFrame(Bitmap bmp, long tsMs);
    }

    private static final String TAG = "Cam";
    private static final int ANALYSIS_W = 640, ANALYSIS_H = 480;

    private final Context ctx;
    private final FrameListener listener;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private int sensorOrientation = 90;
    private boolean frontFacing = true;
    private volatile int rotationOverride = -1;
    private android.graphics.SurfaceTexture dummyTexture;
    private boolean triedDummy;
    private volatile Size analysisSize = new Size(640, 480);
    private int targetW = ANALYSIS_W, targetH = ANALYSIS_H;
    private boolean preferBack;

    public CameraController(Context ctx, FrameListener l) {
        this.ctx = ctx;
        this.listener = l;
    }

    /** 卡片/文字识别需要更高分辨率（人脸活体 640x480 就够），在 start() 前设置。 */
    public void setTargetSize(int w, int h) {
        targetW = w;
        targetH = h;
    }

    /** 默认用前置（自拍核验）；证件/证件卡背要用后置，在 start() 前设置。 */
    public void setBackCamera(boolean back) {
        preferBack = back;
    }

    private volatile String camId = "?";
    private volatile String error;

    public void setRotationOverride(int deg) { rotationOverride = deg; }
    public Size analysisSize() { return analysisSize; }
    public String cameraId() { return camId; }
    public int sensorOrientation() { return sensorOrientation; }
    public int rotationDeg() { return rotation(); }
    public boolean isFront() { return frontFacing; }
    public String lastError() { return error; }

    public void start() {
        bgThread = new HandlerThread("cam");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        bgHandler.post(this::open);
    }

    private void open() {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = pickCamera(cm);
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            frontFacing = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so;

            StreamConfigurationMap scm = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] yuvSizes = scm.getOutputSizes(ImageFormat.YUV_420_888);
            analysisSize = pickSize(yuvSizes, targetW, targetH);
            camId = id + (frontFacing ? " (前)" : " (后)");
            Log.i(TAG, "camera=" + id + " sensor=" + sensorOrientation + " front=" + frontFacing
                    + " analysis=" + analysisSize + " rot=" + rotation());

            reader = ImageReader.newInstance(analysisSize.getWidth(), analysisSize.getHeight(),
                    ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(ir -> {
                Image img = null;
                try {
                    img = ir.acquireLatestImage();
                    if (img == null) return;
                    if (!busy.compareAndSet(false, true)) return;
                    Bitmap bmp = ImgUtil.yuv420ToBitmap(img, rotation(), false);
                    listener.onFrame(bmp, System.currentTimeMillis());
                } catch (Throwable t) {
                    Log.w(TAG, "frame error: " + t);
                } finally {
                    busy.set(false);
                    if (img != null) img.close();
                }
            }, bgHandler);

            if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "no camera permission");
                return;
            }
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice cam) { device = cam; createSession(); }
                @Override public void onDisconnected(CameraDevice cam) { cam.close(); device = null; }
                @Override public void onError(CameraDevice cam, int err) {
                    Log.e(TAG, "camera error " + err);
                    cam.close();
                    device = null;
                }
            }, bgHandler);
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.e(TAG, "open failed", e);
        }
    }

    /** 需要把 buffer 顺时针转多少度才是正向。 */
    private int rotation() {
        if (rotationOverride >= 0) return rotationOverride;
        int dr = 0;
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            int r = wm.getDefaultDisplay().getRotation();
            dr = r == Surface.ROTATION_90 ? 90 : r == Surface.ROTATION_180 ? 180 : r == Surface.ROTATION_270 ? 270 : 0;
        }
        return frontFacing ? (sensorOrientation + dr) % 360 : (sensorOrientation - dr + 360) % 360;
    }

    private void createSession() {
        try {
            Surface readerSurface = reader.getSurface();
            java.util.List<Surface> targets = new java.util.ArrayList<>();
            targets.add(readerSurface);
            if (triedDummy) {
                // 有些机型不接受「只挂 ImageReader」的会话，补一个离屏 dummy 预览面
                if (dummyTexture == null) {
                    dummyTexture = new android.graphics.SurfaceTexture(0);
                    dummyTexture.setDefaultBufferSize(analysisSize.getWidth(), analysisSize.getHeight());
                }
                targets.add(new Surface(dummyTexture));
            }
            CaptureRequest.Builder rb = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            for (Surface s2 : targets) rb.addTarget(s2);
            rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            rb.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            device.createCaptureSession(targets,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                s.setRepeatingRequest(rb.build(), null, bgHandler);
                            } catch (Exception e) {
                                Log.e(TAG, "setRepeating failed", e);
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            if (!triedDummy) {
                                Log.w(TAG, "configure failed, retry with dummy preview surface");
                                triedDummy = true;
                                createSession();
                            } else {
                                error = "capture session 配置失败（含 dummy 预览面也不行）";
                                Log.e(TAG, "configure failed (final)");
                            }
                        }
                    }, bgHandler);
        } catch (Exception e) {
            Log.e(TAG, "createSession failed", e);
        }
    }

    private static Size pickSize(Size[] sizes, int tw, int th) {
        Size best = sizes[0];
        double target = (double) tw / th;
        double bestScore = Double.MAX_VALUE;
        for (Size s : sizes) {
            double ar = (double) s.getWidth() / s.getHeight();
            double score = Math.abs(ar - target) * 10.0 + Math.abs(s.getWidth() - tw) / (double) tw;
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best;
    }

    private String pickCamera(CameraManager cm) throws CameraAccessException {
        String front = null, back = null;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Integer f = ch.get(CameraCharacteristics.LENS_FACING);
            if (f == null) continue;
            if (f == CameraCharacteristics.LENS_FACING_FRONT && front == null) front = id;
            if (f == CameraCharacteristics.LENS_FACING_BACK && back == null) back = id;
        }
        if (preferBack && back != null) return back;
        if (!preferBack && front != null) return front;
        if (back != null) return back;
        if (front != null) return front;
        throw new IllegalStateException("no camera");
    }

    public void stop() {
        try { if (session != null) session.close(); } catch (Throwable ignored) {}
        session = null;
        try { if (device != null) device.close(); } catch (Throwable ignored) {}
        device = null;
        try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
        reader = null;
        try { if (dummyTexture != null) dummyTexture.release(); } catch (Throwable ignored) {}
        dummyTexture = null;
        if (bgThread != null) { bgThread.quitSafely(); bgThread = null; }
    }
}

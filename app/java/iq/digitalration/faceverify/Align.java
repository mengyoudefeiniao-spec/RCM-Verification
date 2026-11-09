package iq.digitalration.faceverify;

import android.graphics.Bitmap;

/**
 * ArcFace 标准人脸对齐：5 点相似变换（Umeyama）到 112x112 模板。
 * 模板与 insightface (norm_crop) 一致。
 */
public final class Align {
    public static final int SIZE = 112;

    /** insightface 5 点目标模板：image-left 眼、image-right 眼、鼻尖、image-left 嘴角、image-right 嘴角。 */
    public static final float[][] DST = {
            {38.2946f, 51.6963f},
            {73.5318f, 51.5014f},
            {56.0252f, 71.7366f},
            {41.5493f, 92.3655f},
            {70.7299f, 92.2041f}
    };

    private Align() {}

    /**
     * 由 5 个源点求相似变换矩阵 [ [a,b,tx], [c,d,ty] ]。
     * src/dst 均为 5x2（x0,y0,x1,y1,...）。
     */
    public static float[] similarity(float[] src, float[] dst) {
        int n = 5;
        double sx = 0, sy = 0, dx = 0, dy = 0;
        for (int i = 0; i < n; i++) {
            sx += src[i * 2]; sy += src[i * 2 + 1];
            dx += dst[i * 2]; dy += dst[i * 2 + 1];
        }
        sx /= n; sy /= n; dx /= n; dy /= n;

        double varSrc = 0;
        double[][] C = new double[2][2];
        for (int i = 0; i < n; i++) {
            double x = src[i * 2] - sx, y = src[i * 2 + 1] - sy;
            double u = dst[i * 2] - dx, v = dst[i * 2 + 1] - dy;
            varSrc += x * x + y * y;
            C[0][0] += u * x; C[0][1] += u * y;
            C[1][0] += v * x; C[1][1] += v * y;
        }
        C[0][0] /= n; C[0][1] /= n; C[1][0] /= n; C[1][1] /= n;
        varSrc /= n;
        if (varSrc < 1e-9) return null;

        // C = U S V^T  (2x2 闭式 SVD)
        double[] svd = svd2x2(C);
        // svd = {u00,u01,u10,u11, s0,s1, v00,v01,v10,v11}
        double u00 = svd[0], u01 = svd[1], u10 = svd[2], u11 = svd[3];
        double s0 = svd[4], s1 = svd[5];
        double v00 = svd[6], v01 = svd[7], v10 = svd[8], v11 = svd[9];

        // det(U) * det(V)
        double detU = u00 * u11 - u01 * u10;
        double detV = v00 * v11 - v01 * v10;
        double sgn = (detU * detV) < 0 ? -1 : 1;

        // R = U * diag(1, sgn) * V^T
        double[][] S = {{1, 0}, {0, sgn}};
        double[][] US = new double[2][2];
        // 显式计算 US = U*S
        US[0][0] = u00 * S[0][0] + u01 * S[1][0];
        US[0][1] = u00 * S[0][1] + u01 * S[1][1];
        US[1][0] = u10 * S[0][0] + u11 * S[1][0];
        US[1][1] = u10 * S[0][1] + u11 * S[1][1];
        // R = US * V^T
        double r00 = US[0][0] * v00 + US[0][1] * v01;
        double r01 = US[0][0] * v10 + US[0][1] * v11;
        double r10 = US[1][0] * v00 + US[1][1] * v01;
        double r11 = US[1][0] * v10 + US[1][1] * v11;

        double scale = (s0 + s1 * sgn) / varSrc;

        double tx = dx - scale * (r00 * sx + r01 * sy);
        double ty = dy - scale * (r10 * sx + r11 * sy);

        return new float[]{
                (float) (scale * r00), (float) (scale * r01), (float) tx,
                (float) (scale * r10), (float) (scale * r11), (float) ty
        };
    }

    /** 2x2 SVD，返回 {u00,u01,u10,u11, s0,s1, v00,v01,v10,v11}。 */
    private static double[] svd2x2(double[][] C) {
        double a = C[0][0], b = C[0][1], c = C[1][0], d = C[1][1];
        // C^T C 对称
        double p = a * a + c * c;
        double q = a * b + c * d;
        double r = b * b + d * d;
        double tr = p + r;
        double disc = Math.sqrt(Math.max(0, (p - r) * (p - r) / 4 + q * q));
        double l1 = tr / 2 + disc;
        double l2 = Math.max(0, tr / 2 - disc);
        double s0 = Math.sqrt(Math.max(0, l1));
        double s1 = Math.sqrt(l2);

        double v1x, v1y, v2x, v2y;
        if (Math.abs(q) < 1e-12) {
            if (p >= r) { v1x = 1; v1y = 0; v2x = 0; v2y = 1; }
            else { v1x = 0; v1y = 1; v2x = 1; v2y = 0; }
        } else {
            v1x = q; v1y = l1 - p;
            double n1 = Math.hypot(v1x, v1y);
            if (n1 < 1e-12) { v1x = 1; v1y = 0; n1 = 1; }
            v1x /= n1; v1y /= n1;
            v2x = -v1y; v2y = v1x;
        }
        // U = C V S^-1
        double u00, u01, u10, u11;
        if (s0 > 1e-12) {
            u00 = (a * v1x + b * v1y) / s0;
            u10 = (c * v1x + d * v1y) / s0;
        } else { u00 = 1; u10 = 0; }
        if (s1 > 1e-12) {
            u01 = (a * v2x + b * v2y) / s1;
            u11 = (c * v2x + d * v2y) / s1;
        } else {
            // 补正交
            u01 = -u10; u11 = u00;
        }
        return new double[]{u00, u01, u10, u11, s0, s1, v1x, v2x, v1y, v2y};
    }

    /** 按 5 点把 src 裁剪/仿射到 112x112。返回 null 表示退化。 */
    public static Bitmap warp(Bitmap src, float[] src5) {
        float[] dst5 = new float[10];
        for (int i = 0; i < 5; i++) {
            dst5[i * 2] = DST[i][0];
            dst5[i * 2 + 1] = DST[i][1];
        }
        float[] m = similarity(src5, dst5);
        if (m == null) return null;
        return warpAffine(src, m, SIZE, SIZE);
    }

    /**
     * 用 [a,b,tx,c,d,ty] 把 src 映射到 dstW x dstH（反向映射 + 双线性）。
     * dst(x,y) = src(a*x + b*y + tx, c*x + d*y + ty)
     */
    public static Bitmap warpAffine(Bitmap src, float[] m, int dstW, int dstH) {
        // 求逆： 2x2 M = [[a,b],[c,d]]，inv(M) 用于把 dst 坐标映射回 src
        double a = m[0], b = m[1], tx = m[2];
        double c = m[3], d = m[4], ty = m[5];
        double det = a * d - b * c;
        if (Math.abs(det) < 1e-9) return null;
        double ia = d / det, ib = -b / det, ic = -c / det, id = a / det;
        double itx = -(ia * tx + ib * ty);
        double ity = -(ic * tx + id * ty);

        int sw = src.getWidth(), sh = src.getHeight();
        int[] sp = new int[sw * sh];
        src.getPixels(sp, 0, sw, 0, 0, sw, sh);
        int[] dp = new int[dstW * dstH];
        for (int y = 0; y < dstH; y++) {
            double fx = ia * 0 + ib * y + itx;
            double fy = ic * 0 + id * y + ity;
            for (int x = 0; x < dstW; x++) {
                double sx = ia * x + ib * y + itx;
                double sy = ic * x + id * y + ity;
                dp[y * dstW + x] = sampleBilinear(sp, sw, sh, sx, sy);
                fx += ia; fy += ic;
            }
        }
        return Bitmap.createBitmap(dp, dstW, dstH, Bitmap.Config.ARGB_8888);
    }

    private static int sampleBilinear(int[] p, int w, int h, double x, double y) {
        if (x < 0 || y < 0 || x > w - 1 || y > h - 1) return 0xFF000000;
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        double fx = x - x0, fy = y - y0;
        int p00 = p[y0 * w + x0], p10 = p[y0 * w + x1], p01 = p[y1 * w + x0], p11 = p[y1 * w + x1];
        int r = (int) ((1 - fx) * (1 - fy) * ((p00 >> 16) & 0xFF) + fx * (1 - fy) * ((p10 >> 16) & 0xFF)
                + (1 - fx) * fy * ((p01 >> 16) & 0xFF) + fx * fy * ((p11 >> 16) & 0xFF));
        int g = (int) ((1 - fx) * (1 - fy) * ((p00 >> 8) & 0xFF) + fx * (1 - fy) * ((p10 >> 8) & 0xFF)
                + (1 - fx) * fy * ((p01 >> 8) & 0xFF) + fx * fy * ((p11 >> 8) & 0xFF));
        int b = (int) ((1 - fx) * (1 - fy) * (p00 & 0xFF) + fx * (1 - fy) * (p10 & 0xFF)
                + (1 - fx) * fy * (p01 & 0xFF) + fx * fy * (p11 & 0xFF));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}

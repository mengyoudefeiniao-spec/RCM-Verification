# PP-OCRv4 det+rec 最小管线原型（与 App 内 Java 实现一一对应，用于先在 PC 上验证参数）。
# 用法: python proto.py card_back.png
import sys, math, re
import numpy as np
import cv2
import onnxruntime as ort

DET = "models/ppocr_det.onnx"
REC = "models/ppocr_rec.onnx"
DICT = "models/ppocr_keys_v1.txt"

chars = ["blank"] + [l.rstrip("\r\n") for l in open(DICT, encoding="utf-8")] + [" "]

det = ort.InferenceSession(DET, providers=["CPUExecutionProvider"])
rec = ort.InferenceSession(REC, providers=["CPUExecutionProvider"])

MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)


def limit_size(h, w, max_side=960):
    s = max_side / float(max(h, w))
    if s >= 1.0:
        return h, w
    return max(32, int(round(h * s))), max(32, int(round(w * s)))


def det_forward(bgr):
    h, w = bgr.shape[:2]
    nh, nw = limit_size(h, w)
    # 32 对齐
    nh = max(32, int(round(nh / 32.0)) * 32)
    nw = max(32, int(round(nw / 32.0)) * 32)
    im = cv2.resize(bgr, (nw, nh))
    x = im[:, :, ::-1].astype(np.float32) / 255.0   # BGR->RGB
    x = (x - MEAN) / STD
    x = x.transpose(2, 0, 1)[None]
    prob = det.run(None, {det.get_inputs()[0].name: x})[0][0, 0]
    # 还原到原图尺寸
    prob = cv2.resize(prob, (w, h))
    return prob


def boxes_from_prob(prob, thr=0.3, min_area=12, unclip=1.6):
    mask = (prob > thr).astype(np.uint8)
    n, lab, stats, cents = cv2.connectedComponentsWithStats(mask, 8)
    out = []
    for i in range(1, n):
        area = stats[i, cv2.CC_STAT_AREA]
        if area < min_area:
            continue
        ys, xs = np.where(lab == i)
        pts = np.stack([xs, ys], 1).astype(np.float32)
        rect = cv2.minAreaRect(pts)
        (cx, cy), (bw, bh), ang = rect
        if min(bw, bh) < 3:
            continue
        bw2, bh2 = bw * unclip, bh * unclip
        if max(bw2, bh2) < 8:
            continue
        out.append((cx, cy, bw2, bh2, ang))
    # 去掉被更大框包含的框
    out.sort(key=lambda b: -b[2] * b[3])
    keep = []
    for b in out:
        inside = False
        for k in keep:
            if abs(b[0] - k[0]) < k[2] * 0.6 and abs(b[1] - k[1]) < k[3] * 0.6:
                inside = True
                break
        if not inside:
            keep.append(b)
    return keep


def crop_rot(bgr, cx, cy, bw, bh, ang):
    """按 minAreaRect 的 (w,h,angle) 抠图：输出宽=bw 高=bh 的水平条。"""
    M = cv2.getRotationMatrix2D((cx, cy), ang, 1.0)
    rot = cv2.warpAffine(bgr, M, (bgr.shape[1], bgr.shape[0]), flags=cv2.INTER_CUBIC,
                         borderMode=cv2.BORDER_REPLICATE)
    x0 = int(round(cx - bw / 2)); y0 = int(round(cy - bh / 2))
    x0 = max(0, min(x0, bgr.shape[1] - 1)); y0 = max(0, min(y0, bgr.shape[0] - 1))
    x1 = min(bgr.shape[1], x0 + max(1, int(round(bw))))
    y1 = min(bgr.shape[0], y0 + max(1, int(round(bh))))
    return rot[y0:y1, x0:x1]


def rec_forward(img):
    h, w = img.shape[:2]
    if h < 2 or w < 2:
        return "", 0.0
    tw = int(round(48 * w / float(h)))
    tw = max(16, min(tw, 640))
    im = cv2.resize(img, (tw, 48))
    x = im[:, :, ::-1].astype(np.float32) / 255.0
    x = (x - 0.5) / 0.5
    x = x.transpose(2, 0, 1)[None]
    o = rec.run(None, {rec.get_inputs()[0].name: x})[0][0]  # [T, C]
    idx = o.argmax(1)
    score = o.max(1)
    chars_out, confs = [], []
    prev = -1
    for t, i in enumerate(idx):
        if i != 0 and i != prev:
            chars_out.append(chars[i])
            confs.append(float(score[t]))
        prev = i
    return "".join(chars_out), float(np.mean(confs)) if confs else 0.0


def ocr(bgr, thr=0.3):
    prob = det_forward(bgr)
    boxes = boxes_from_prob(prob, thr=thr)
    res = []
    for (cx, cy, bw, bh, ang) in boxes:
        # minAreaRect 的角度在 [-90,0)，统一成"水平条"
        if bw < bh:
            bw, bh = bh, bw
            ang = ang + 90
        crop = crop_rot(bgr, cx, cy, bw, bh, ang)
        if crop.size == 0:
            continue
        txt, conf = rec_forward(crop)
        # 竖排（家庭编号）候选：把条旋转 90 再识别一次，取分数高的
        txt2, conf2 = rec_forward(cv2.rotate(crop, cv2.ROTATE_90_CLOCKWISE)) if crop.shape[0] > crop.shape[1] else ("", 0)
        if conf2 > conf:
            txt, conf = txt2, conf2
        res.append((txt, conf, int(cx), int(cy), int(bw), int(bh), ang))
    return res


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else "card_back.png"
    img = cv2.imread(path)
    for i in range(4):
        bgr = img if i == 0 else cv2.rotate(img, [None, cv2.ROTATE_90_CLOCKWISE, cv2.ROTATE_180, cv2.ROTATE_90_COUNTERCLOCKWISE][i])
        r = ocr(bgr)
        joined = "".join(t for t, *_ in r)
        print("== rot", i * 90, "boxes", len(r), "score", round(sum(c for _, c, *_ in r), 2))
        for t, c, x, y, w, h, a in r:
            print("   %-45s %.2f  (%d,%d) %dx%d %.0f" % (repr(t), c, x, y, w, h, a))
        m = re.search(r"\d{4}[A-Z]\d{13}", joined.replace(" ", ""))
        print("   family:", m.group(0) if m else None)

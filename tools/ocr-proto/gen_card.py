# -*- coding: utf-8 -*-
"""
生成"伊拉克配给卡卡背"仿真图，用来在没有真实卡面的情况下回归 OCR 参数。

真实卡背的版式（对着参考照片）：
    右列（阿拉伯文/库尔德文标签）       左列（值）
    الرقم العائلي :                    1002E6996574360150
    الاسم / ناو                        ALDAWDY JWD
    تاريخ الميلاد / ڕێکەوتی لەدایکبوون   1991/05/12
    ...
    底部两行 TD1 机读区

阿拉伯文用 arabic_reshaper + python-bidi 预先整形（PIL 没有 raqm 时不会自己连写），
这样生成的字形与真实印刷一致，避免"测试图本身就不像"的问题。

用法：
    python gen_card.py                 # 生成 tools/fixtures/card_back.png 与 _hard.png
    python gen_card.py out.png         # 指定输出（同时生成 _hard / _rot90）
"""
import os
import random
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

try:
    import arabic_reshaper
    from bidi.algorithm import get_display
except Exception:                                    # 没装就退化为不整形
    def get_display(s):
        return s

    class _R:
        @staticmethod
        def reshape(s):
            return s

    arabic_reshaper = _R()

FAMILY = "1002E6996574360150"
DOC = "AE0644356"
DOB = "910512"
EXP = "270724"
MRZ1 = "IDIRQ" + DOC + "2199199607328<<<"
MRZ2 = "9105126F2707244IRQ<<<<<<<<<<<<4"
MRZ3 = "ALDAWDY<<JWD<<<<<<<<<<<<<<<<<<"


def ar(s):
    return get_display(arabic_reshaper.reshape(s))


def font(path, size):
    for p in path:
        try:
            return ImageFont.truetype(p, size)
        except Exception:
            continue
    return ImageFont.load_default()


AR_FONT = ["C:/Windows/Fonts/tahoma.ttf", "C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf"]
LAT_FONT = ["C:/Windows/Fonts/arial.ttf", "C:/Windows/Fonts/segoeui.ttf"]
MONO_FONT = ["C:/Windows/Fonts/consola.ttf", "C:/Windows/Fonts/cour.ttf", "C:/Windows/Fonts/arial.ttf"]


def build(path, hard=False):
    W, H = 1000, 700
    img = Image.new("RGB", (W, H), (247, 243, 232))

    # 底纹 + 淡色水印，模拟真实卡面的印刷背景
    d = ImageDraw.Draw(img)
    for i in range(0, W, 26):
        d.line([(i, 0), (i + 200, H)], fill=(238, 232, 214), width=7)
    d.rectangle([12, 12, W - 12, H - 12], outline=(150, 160, 175), width=3)
    d.ellipse([W - 250, -110, W + 60, 200], fill=(228, 234, 240))
    d.text((30, 26), "جمهورية العراق - وزارة التجارة", font=font(AR_FONT, 26), fill=(30, 40, 60))

    ar_f = font(AR_FONT, 30)
    ar_s = font(AR_FONT, 26)
    lat_f = font(LAT_FONT, 28)
    lat_b = font(LAT_FONT, 30)

    # (标签, 值, y)  —— 标签靠右（RTL），值靠左；两家文字各写一行，模拟双语文卡面
    rows = [
        (ar("الرقم العائلي :"), FAMILY, 145),
        (ar("ناو :"), "ALDAWDY JWD", 245),
        (ar("تاريخ الميلاد :"), "1991/05/12", 315),
        (ar("جنس :"), "IRQ / M", 385),
        (ar("تاريخ الاصدار :"), "2024/06/01", 455),
        (ar("تاريخ الانتهاء :"), "2031/04/26", 500),
    ]
    for label, value, y in rows:
        d.text((W - 60 - d.textlength(label, font=ar_f), y), label, font=ar_f, fill=(25, 32, 45))
        d.text((60, y + 2), value, font=lat_b if value == FAMILY else lat_f, fill=(20, 24, 32))

    # 库尔德文标签（第二语言），放在同一行的下方
    d.text((W - 60 - d.textlength(ar("ژمارەی خێزانی :"), font=ar_s), 180),
           ar("ژمارەی خێزانی :"), font=ar_s, fill=(40, 46, 60))
    d.text((W - 60 - d.textlength(ar("ڕێکەوتی لەدایکبوون :"), font=ar_s), 350),
           ar("ڕێکەوتی لەدایکبوون :"), font=ar_s, fill=(40, 46, 60))

    # 家庭编号加个浅色框（真实卡面也是框起来的）
    d.rectangle([54, 142, 54 + d.textlength(FAMILY, font=lat_b) + 16, 190], outline=(190, 90, 80), width=2)

    # MRZ
    mz = font(MONO_FONT, 26)
    d.text((40, 568), MRZ3, font=mz, fill=(20, 20, 20))
    d.text((40, 600), MRZ1, font=mz, fill=(20, 20, 20))
    d.text((40, 632), MRZ2, font=mz, fill=(20, 20, 20))

    img.save(path)

    rot = img.rotate(90, expand=True)
    rot.save(path.replace(".png", "_rot90.png"))

    if hard:
        # "手机拍出来"的样子：整幅缩到 62%、轻微旋转 + 模糊 + 噪声
        small = rot.resize((int(rot.width * 0.62), int(rot.height * 0.62)), Image.LANCZOS)
        small = small.rotate(-4, expand=True, resample=Image.BICUBIC, fillcolor=(240, 238, 232))
        small = small.filter(ImageFilter.GaussianBlur(0.6))
        px = small.load()
        rnd = random.Random(7)
        for _ in range(small.width * small.height // 6):
            x = rnd.randrange(small.width)
            y = rnd.randrange(small.height)
            r, g, b = px[x, y][:3]
            v = rnd.randint(-16, 16)
            px[x, y] = (max(0, min(255, r + v)), max(0, min(255, g + v)), max(0, min(255, b + v)))
        small.save(path.replace(".png", "_hard.png"))
        print("wrote", path, "and _rot90 / _hard")


if __name__ == "__main__":
    default_out = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                               "fixtures", "card_back.png")
    out = sys.argv[1] if len(sys.argv) > 1 else default_out
    build(out, hard=True)

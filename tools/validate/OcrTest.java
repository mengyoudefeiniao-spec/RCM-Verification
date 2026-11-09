import iq.digitalration.faceverify.ocr.CardFields;
import iq.digitalration.faceverify.ocr.OcrCore;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * PC 端 OCR 端到端验证：直接跑 App 里的 OcrCore（同一个类、同一套参数），
 * 输入一张卡背照片，输出每行文字 + 标签 + 家庭编号 + MRZ。
 *
 * 用法: java -cp ... OcrTest 图片 [det] [recEn] [keyEn] [recAr] [keyAr]
 */
public class OcrTest {

    static int[] load(String path, int[] size) throws Exception {
        BufferedImage bi = ImageIO.read(new File(path));
        int w = bi.getWidth(), h = bi.getHeight();
        int[] px = new int[w * h];
        bi.getRGB(0, 0, w, h, px, 0, w);
        size[0] = w; size[1] = h;
        return px;
    }

    public static void main(String[] a) throws Exception {
        String img = a.length > 0 ? a[0] : "tools/fixtures/card_back.png";
        String det = a.length > 1 ? a[1] : "models/ppocr_det.onnx";
        String recEn = a.length > 2 ? a[2] : "models/ppocr_rec_en.onnx";
        String keyEn = a.length > 3 ? a[3] : "models/ppocr_keys_en.txt";
        String recAr = a.length > 4 ? a[4] : "models/ppocr_rec_ar.onnx";
        String keyAr = a.length > 5 ? a[5] : "models/ppocr_keys_ar.txt";

        int[] size = new int[2];
        int[] px = load(img, size);
        OcrCore core = new OcrCore(det, recEn, keyEn, recAr, keyAr, 4);
        long t0 = System.currentTimeMillis();
        OcrCore.Scan scan = core.recognizeAuto(px, size[0], size[1]);
        List<OcrCore.Line> lines = scan.lines;
        long ms = System.currentTimeMillis() - t0;

        System.out.println("图 " + size[0] + "x" + size[1] + " · 方向 " + scan.rotation
                + "° · 行数 " + lines.size() + " · " + ms + "ms");
        for (OcrCore.Line l : lines) System.out.println("  " + l);

        CardFields cf = CardFields.parse(lines);
        System.out.println("标签    : " + (cf.familyLabel == null ? "(未识别)" : cf.familyLabel));
        System.out.println("家庭编号: " + cf.familyNo + (cf.familyNo == null ? "" : "（可信度 " + cf.familyConf + "）"));
        System.out.println("证件号  : " + cf.docNumber);
        System.out.println("出生/有效期: " + cf.dob + " / " + cf.expiry + " / " + cf.nationality);
        System.out.println("MRZ 行  : " + cf.mrzLine1 + " | " + cf.mrzLine2);
        System.out.println("MRZ 校验: doc=" + cf.checkDocOk + " dob=" + cf.checkDobOk + " exp=" + cf.checkExpiryOk
                + " composite=" + cf.checkCompositeOk);
        System.out.println(cf.summary());
        core.close();
    }
}

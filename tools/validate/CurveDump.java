import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;

/** 从 JDK 的 SunEC 导出 NIST 曲线参数（生成 Curves.java 用，不参与打包）。 */
public class CurveDump {
    static String hex(BigInteger b) { return b.toString(16).toUpperCase(); }

    public static void main(String[] a) throws Exception {
        String[] names = {"secp192r1", "secp224r1", "secp256r1", "secp384r1", "secp521r1"};
        String[] keys = {"P192", "P224", "P256", "P384", "P521"};
        for (int i = 0; i < names.length; i++) {
            AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
            ap.init(new java.security.spec.ECGenParameterSpec(names[i]));
            ECParameterSpec s = ap.getParameterSpec(ECParameterSpec.class);
            ECFieldFp fp = (ECFieldFp) s.getCurve().getField();
            ECPoint g = s.getGenerator();
            System.out.println("### " + keys[i] + " (" + names[i] + ")");
            System.out.println("p=" + hex(fp.getP()));
            System.out.println("a=" + hex(s.getCurve().getA()));
            System.out.println("b=" + hex(s.getCurve().getB()));
            System.out.println("gx=" + hex(g.getAffineX()));
            System.out.println("gy=" + hex(g.getAffineY()));
            System.out.println("n=" + hex(s.getOrder()));
            System.out.println("bits=" + fp.getP().bitLength());
        }
    }
}

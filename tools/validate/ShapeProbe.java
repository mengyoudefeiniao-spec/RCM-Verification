import ai.onnxruntime.*;
import java.io.File;
import java.util.*;

public class ShapeProbe {
    public static void main(String[] args) throws Exception {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        for (String path : args) {
            try (OrtSession s = env.createSession(new File(path).getAbsolutePath(), new OrtSession.SessionOptions())) {
                System.out.println("== " + path);
                for (Map.Entry<String, NodeInfo> e : s.getInputInfo().entrySet()) {
                    TensorInfo ti = (TensorInfo) e.getValue().getInfo();
                    System.out.println("   IN  " + e.getKey() + "  " + ti.type + " " + Arrays.toString(ti.getShape()));
                }
                for (Map.Entry<String, NodeInfo> e : s.getOutputInfo().entrySet()) {
                    TensorInfo ti = (TensorInfo) e.getValue().getInfo();
                    System.out.println("   OUT " + e.getKey() + "  " + ti.type + " " + Arrays.toString(ti.getShape()));
                }
            }
        }
    }
}

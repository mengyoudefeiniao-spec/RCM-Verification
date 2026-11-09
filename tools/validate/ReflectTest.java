// 验证：加载 Graph 类并解析其全部方法签名时，缺少 GraphTemplateProto 会不会报错。
// HotSpot 与 ART 都采用惰性解析，若这里能过，说明签名解析不强制要求该类型。
public class ReflectTest {
    public static void main(String[] args) throws Exception {
        String[] targets = {
                "com.google.mediapipe.framework.Graph",
                "com.google.mediapipe.framework.GraphProfiler",
                "com.google.mediapipe.tasks.core.TaskRunner",
                "com.google.mediapipe.tasks.core.logging.RemoteLoggingClient"
        };
        for (String t : targets) {
            try {
                // initialize=false：不触发静态初始化（避免 System.loadLibrary）
                Class<?> c = Class.forName(t, false, ReflectTest.class.getClassLoader());
                int m = c.getDeclaredMethods().length;
                int f = c.getDeclaredFields().length;
                System.out.printf("OK   %-60s methods=%d fields=%d%n", t, m, f);
            } catch (Throwable e) {
                System.out.printf("FAIL %-60s %s: %s%n", t, e.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}

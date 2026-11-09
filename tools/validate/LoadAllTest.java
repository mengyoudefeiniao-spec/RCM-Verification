// 全类加载测试：把 classpath 上所有类挨个 Class.forName(initialize=false)，
// 任何「签名引用了不存在的类」都会在这一步暴露（这正是 Graph 踩雷的方式）。
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class LoadAllTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        String rel = root.relativize(p).toString().replace(File.separatorChar, '/');
                        rel = rel.substring(rel.indexOf('/') + 1);          // 去掉 jar 目录这一层
                        names.add(rel.substring(0, rel.length() - 6).replace('/', '.'));
                    });
        }
        // 我们自己的类不在 jar 目录下（j0_own 下）
        try (Stream<Path> s = Files.walk(root.resolve("j0_own"))) {
            s.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                String rel = root.relativize(p).toString().replace(File.separatorChar, '/');
                names.add(rel.substring(0, rel.length() - 6).replace('/', '.'));
            });
        }

        int ok = 0, fail = 0;
        ClassLoader cl = LoadAllTest.class.getClassLoader();
        for (String n : names) {
            if (n.startsWith("j0_own.") || n.equals("module-info")) continue;
            try {
                Class.forName(n, false, cl);
                ok++;
            } catch (Throwable t) {
                fail++;
                if (fail <= 40) System.out.printf("FAIL %-72s %s: %s%n", n,
                        t.getClass().getSimpleName(), String.valueOf(t.getMessage()).split("\n")[0]);
            }
        }
        System.out.printf("%n加载成功 %d 个，失败 %d 个（共 %d）%n", ok, fail, ok + fail);
        if (fail == 0) System.out.println("ALL CLASSES LOADABLE");
    }
}

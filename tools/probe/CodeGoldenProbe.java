import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * 把「章节 → Java 源码片段」的产出冻结成 golden 文件，用来保证重构不改行为。
 *
 * <p>背景：弹出框和侧边栏伪装要共用同一份内容生成逻辑，所以要把
 * SourceDisguisePanel 里那套拼装/转义抽到 CodeDocument。抽的过程很容易
 * 手滑改掉一处转义或一个空格，而这种变化在界面上极难看出来。
 * 所以先用当前实现（未经改动的那份）跑出快照，重构后再跑一次逐字节对比。
 *
 * <p>用反射调真实的 {@code SourceDisguisePanel.buildLines}，不复刻逻辑：
 * 复刻出来的那份不会有 bug，有 bug 的是原来那份。
 *
 * <p>用法：
 * <pre>
 *   java CodeGoldenProbe tools/probe/golden/code-lines.txt            # 生成快照
 *   java CodeGoldenProbe tools/probe/golden/code-lines.txt --verify   # 对比快照
 * </pre>
 */
public class CodeGoldenProbe {

    private static final String[][] SAMPLES = {
            {"第 84 章 好舅舅", "　　他推开门的时候，屋里的灯还亮着。\n"
                    + "桌上摊着一封信，字迹潦草。\n"
                    + "\n"
                    + "a * b / c —— 这里塞一个注释结束符 */ 和制表符\t以及 \"引号\" 与反斜杠\\\n"
                    + "最后一行"}, 
            {"第 1 章 序", "只有一行正文\n"},
            {"空章节", ""},
    };

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "tools/probe/golden/code-lines.txt";
        boolean verify = args.length > 1 && "--verify".equals(args[1]);
        String actual = dump();
        File file = new File(path);

        if (!verify) {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.write(file.toPath(), actual.getBytes(StandardCharsets.UTF_8));
            System.out.println("已写入快照 " + path + "（" + actual.split("\n").length + " 行）");
            return;
        }

        if (!file.isFile()) {
            System.out.println("GOLDEN MISSING: " + path);
            System.exit(2);
        }
        String expected = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (expected.equals(actual)) {
            System.out.println("GOLDEN OK：产出与快照逐字节一致");
            return;
        }
        System.out.println("GOLDEN MISMATCH：产出变了");
        System.out.println(firstDiff(expected, actual));
        System.exit(1);
    }

    /** 逐行反射调真实的 buildLines，按「颜色类别 + 文本（换行转义）」落盘 */
    private static String dump() throws Exception {
        Class<?> cls = Class.forName("com.github.ideanovel.ui.SourceDisguisePanel");
        Method build = cls.getDeclaredMethod("buildLines", String.class, String.class, int.class);
        build.setAccessible(true);

        StringBuilder sb = new StringBuilder();
        sb.append("# 章节 → Java 源码片段的产出快照。改了渲染逻辑后重跑 --verify，必须逐字节一致。\n");
        sb.append("# 格式：kind \\t text（换行写成 \\\\n）\n");
        for (int i = 0; i < SAMPLES.length; i++) {
            String title = SAMPLES[i][0];
            String body = SAMPLES[i][1];
            int index = i * 3;                       // 顺带覆盖 className 的位数对齐
            sb.append("# ---- sample ").append(i).append(" | index=").append(index)
              .append(" | title=").append(title.replace("\n", " ")).append('\n');
            List<?> lines = (List<?>) build.invoke(null, title, body, index);
            for (Object line : lines) {
                sb.append(field(line, "kind")).append('\t')
                  .append(((String) field(line, "text")).replace("\n", "\\n")).append('\n');
            }
        }
        return sb.toString();
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static String firstDiff(String a, String b) {
        String[] la = a.split("\n", -1);
        String[] lb = b.split("\n", -1);
        int n = Math.max(la.length, lb.length);
        for (int i = 0; i < n; i++) {
            String x = i < la.length ? la[i] : "<缺行>";
            String y = i < lb.length ? lb[i] : "<缺行>";
            if (!x.equals(y)) {
                return "第 " + (i + 1) + " 行不同：\n  快照: " + x + "\n  实际: " + y;
            }
        }
        return "行内容相同但总长度不同";
    }
}

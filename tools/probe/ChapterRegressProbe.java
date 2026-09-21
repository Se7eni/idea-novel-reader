import com.github.ideanovel.model.Chapter;
import com.github.ideanovel.parser.ChapterParser;

import java.util.List;

/**
 * 回归用例：确认去重逻辑不会把正常的章节、粘连排版、口语化开头误伤掉。
 *
 * 每一组都手写输入和期望，跑的是真实的 ChapterParser.parse。
 */
public class ChapterRegressProbe {

    private static int failed = 0;

    public static void main(String[] args) {
        // 1. 正常章节不该被合并
        check("正常切分", 3,
                "第1章 开头\n正文一。\n\n第2章 中间\n正文二。\n\n第3章 结尾\n正文三。\n");

        // 2. 连续重复标题 → 只留一个
        check("连写两遍标题", 2,
                "第1章 甲\n正文一。\n\n第2章 乙\n第2章 乙\n正文二。\n");

        // 3. 连写三遍
        check("连写三遍标题", 2,
                "第1章 甲\n正文一。\n\n第2章 乙\n第2章 乙\n第2章 乙\n正文二。\n");

        // 4. 缩进版重复（全角空格已换成普通空格后的形态，真实场景）
        check("缩进的重复标题", 2,
                "第1章 甲\n正文一。\n\n第2章 乙\n  第2章 乙\n  正文二。\n");

        // 5. 半角/全角括号的近似重复（本书第302章的真实情况）
        check("近似标题重复", 2,
                "第1章 甲\n正文一。\n\n第2章 乙(6k)\n第2章 乙（6k）\n正文二。\n");

        // 6. 正文里出现「第一节是病生」这类句子，不该被当成章节消失
        //    （这条只是确认行为稳定：它仍会被算作章节，因为默认正则就是这么宽松的）
        List<Chapter> r6 = parse("第一节是病生，老谢点名很严。\n教室里。\n\n第2章 乙\n正文二。\n");
        report("正文里的误判句仍保留", r6.size() == 2, "实际 " + r6.size() + " 章");

        // 7. 空书名 / 无匹配 → 退回到分块
        List<Chapter> r7 = parse("随便一段没有任何章节标题的文字。\n再来一段。\n");
        report("无匹配时兜底分块", !r7.isEmpty(), "实际 " + r7.size() + " 章");

        // 8. 空输入
        List<Chapter> r8 = ChapterParser.parse("", true, ChapterParser.DEFAULT_REGEX, 4000);
        report("空输入不炸", r8.isEmpty(), "实际 " + r8.size() + " 章");

        // 9. 最后一章只有标题（不应被丢弃）
        check("末章只有标题", 2,
                "第1章 甲\n正文一。\n\n第2章 乙\n");

        System.out.println(failed == 0 ? "\n全部通过" : "\n失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static List<Chapter> parse(String text) {
        return ChapterParser.parse(text, true, ChapterParser.DEFAULT_REGEX, 4000);
    }

    private static void check(String name, int expect, String text) {
        List<Chapter> r = parse(text);
        report(name, r.size() == expect, "期望 " + expect + " 章，实际 " + r.size());
    }

    private static void report(String name, boolean ok, String detail) {
        System.out.println((ok ? "  [通过] " : "  [失败] ") + name + " — " + detail);
        if (!ok) {
            failed++;
        }
    }
}

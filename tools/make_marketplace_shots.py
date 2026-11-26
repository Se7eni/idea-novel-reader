# -*- coding: utf-8 -*-
"""
生成 JetBrains Marketplace 用的插件截图（1200x760，深色 IDE 风格）。

为什么是矢量重绘而不是直接放大探针截图：
探针产物是几百像素的孤立组件，放大到 1200 宽会糊；这里用绘图 API 按目标分辨率
重画 IDE 外壳（项目树 / 代码 / 工具窗 / 状态栏），文字清晰，配色对齐 Darcula。

输出：docs/screenshots/marketplace/*.png
"""
import os
from PIL import Image, ImageDraw, ImageFont

W, H = 1200, 760

# Darcula 近似配色
BG = "#1E1F22"
EDITOR = "#2B2D30"
GUTTER = "#1E1F22"
PANEL = "#2B2D30"
BAR = "#3C3F41"
BORDER = "#43454A"
FG = "#BCBEC4"
FG_DIM = "#7A7E85"
FG_LINE = "#5A5D63"
KEY = "#CC7832"
STR = "#6A8759"
COMMENT = "#7A7E85"
NUM = "#6897BB"
ACCENT = "#4A88C7"
SEL = "#214283"
WHITE = "#E8E8E8"
RED = "#C75450"

FONT_UI = "C:/Windows/Fonts/msyh.ttc"
FONT_MONO = "C:/Windows/Fonts/consola.ttf"


def ui(size, bold=False):
    return ImageFont.truetype(FONT_UI, size, index=1 if bold else 0)


def mono(size):
    return ImageFont.truetype(FONT_MONO, size)


def canvas():
    img = Image.new("RGB", (W, H), BG)
    return img, ImageDraw.Draw(img)


def code_block(d, x, y, rows, f, line_h=21, num_w=34, f_cjk=None):
    """rows: [(行号, [(片段, 颜色), ...]), ...]

    含中文的片段用 f_cjk 渲染——Consolas 没有中文字形，硬用会变成方块。
    """
    for i, (num, parts) in enumerate(rows):
        yy = y + i * line_h
        if num is not None:
            d.text((x + num_w - d.textlength(str(num), font=f) - 6, yy), str(num),
                   font=f, fill=FG_LINE)
        cx = x + num_w
        for text, color in parts:
            use = f_cjk if (f_cjk is not None and any('\u4e00' <= c <= '\u9fff' for c in text)) else f
            d.text((cx, yy), text, font=use, fill=color)
            cx += d.textlength(text, font=use)


def title_bar(d, text, right=""):
    d.rectangle([0, 0, W, 36], fill=BAR)
    d.text((16, 10), text, font=ui(12), fill=FG)
    if right:
        d.text((W - 16 - d.textlength(right, font=ui(12)), 10), right,
               font=ui(12), fill=FG_DIM)


def status_bar(d, left, right="UTF-8   4 spaces   LF"):
    y = H - 26
    d.rectangle([0, y, W, H], fill=BAR)
    d.text((10, y + 5), left, font=ui(11), fill=FG)
    d.text((W - 10 - d.textlength(right, font=ui(11)), y + 5), right,
           font=ui(11), fill=FG_DIM)


# ---------------------------------------------------------------- 图 1 主图

JAVA_ROWS = [
    (1, [("package ", KEY), ("com.tcm.edu.service.impl;", FG)]),
    (2, []),
    (3, [("import ", KEY), ("com.tcm.edu.model.User;", FG)]),
    (4, [("import ", KEY), ("org.springframework.stereotype.Service;", FG)]),
    (5, []),
    (6, [("@Service", ACCENT)]),
    (7, [("public class ", KEY), ("LoginServiceImpl ", WHITE), ("implements ", KEY), ("LoginService {", FG)]),
    (8, []),
    (9, [("    @Override", ACCENT)]),
    (10, [("    public ", KEY), ("User ", WHITE), ("login(", FG), ("String ", WHITE), ("username, ", FG), ("String ", WHITE), ("password) {", FG)]),
    (11, [("        User user = userMapper.selectByUsername(username);", FG)]),
    (12, [("        if ", KEY), ("(user == ", FG), ("null ", KEY), ("|| !passwordMatches(user, password)) {", FG)]),
    (13, [("            throw ", KEY), ("new ", KEY), ("BizException(", FG), ('"bad credentials"', STR), (");", FG)]),
    (14, [("        }", FG)]),
    (15, [("        return ", KEY), ("user;", FG)]),
    (16, [("    }", FG)]),
    (17, [("}", FG)]),
]


def shot_sidebar():
    img, d = canvas()
    title_bar(d, "tcm-edu  —  LoginServiceImpl.java", "src/main/java")

    # 左侧项目树
    tree_w = 200
    d.rectangle([0, 36, tree_w, H - 26], fill=GUTTER)
    d.text((12, 48), "tcm-edu", font=ui(12, True), fill=WHITE)
    tree = [("src/main/java", FG), ("  com.tcm.edu", FG),
            ("    service", FG), ("      LoginServiceImpl.java", ACCENT),
            ("    controller", FG), ("      LoginController.java", FG),
            ("    model", FG), ("      User.java", FG)]
    for i, (t, c) in enumerate(tree):
        if i == 3:
            d.rectangle([0, 74 + i * 24 - 2, tree_w, 74 + i * 24 + 20], fill=SEL)
        d.text((14, 74 + i * 24), t, font=ui(12), fill=c)

    # 中间代码（字号压到 12：460px 宽里放得下最长那行）
    d.rectangle([tree_w, 36, 660, H - 26], fill=EDITOR)
    code_block(d, tree_w + 6, 48, JAVA_ROWS, mono(12), line_h=21, num_w=30)

    # 右侧插件工具窗
    px = 660
    d.rectangle([px, 36, W, H - 26], fill=PANEL)
    d.line([(px, 36), (px, H - 26)], fill=BORDER)

    # 工具窗标题
    d.rectangle([px, 36, W, 70], fill=BAR)
    d.text((px + 12, 46), "摸鱼小说", font=ui(13, True), fill=WHITE)

    # 工具栏
    btns = ["打开", "网络", "书架", "目录", "上章", "下章", "自动", "隐身"]
    bx = px + 10
    for b in btns:
        w = d.textlength(b, font=ui(11)) + 14
        d.rounded_rectangle([bx, 78, bx + w, 100], radius=4, fill="#35373A")
        d.text((bx + 7, 82), b, font=ui(11), fill=FG)
        bx += w + 4

    # 书名
    d.text((px + 12, 112), "全民恐怖求生：我觉醒加点序列！", font=ui(12, True), fill=WHITE)
    d.text((px + 12, 132), "第 17 章 · 本地 · 本章约 2,418 字", font=ui(10), fill=FG_DIM)

    # 目录
    chapters = ["第14章 门后的东西", "第15章 他一直在看着我", "第16章 第二个求生者",
                "第17章 绝境！", "第18章 用命换来的线索"]
    cy = 158
    d.text((px + 12, cy), "目录", font=ui(10), fill=FG_DIM)
    cy += 20
    for i, c in enumerate(chapters):
        if c.startswith("第17章"):
            d.rectangle([px + 6, cy - 3, W - 6, cy + 18], fill=SEL)
        d.text((px + 14, cy), c, font=ui(11), fill=WHITE if c.startswith("第17章") else FG)
        cy += 23

    # 正文
    d.line([(px + 10, cy + 6), (W - 10, cy + 6)], fill=BORDER)
    body_y = cy + 18
    lines = [
        "第17章 绝境！",
        "",
        "　　郑笙拿着菜刀，手起刀落，手起刀落。",
        "　　食物和水已经收集的差不多了，他必须",
        "要在诡异找来之前，找到一辆能开的车。",
        "　　门外传来指甲刮擦铁皮的声音，一下，",
        "又一下，不紧不慢，像是在数着什么。",
    ]
    for i, line in enumerate(lines):
        f = ui(12, True) if i == 0 else ui(12)
        color = WHITE if i == 0 else FG
        d.text((px + 14, body_y + i * 22), line, font=f, fill=color)

    status_bar(d, "第 18 / 500 章 · 第17章 绝境！· 本章约 2,418 字")
    return img


# ---------------------------------------------------------------- 图 2 代码弹窗

def shot_code_popup():
    img, d = canvas()
    title_bar(d, "tcm-edu  —  OrderServiceImpl.java", "src/main/java")

    # 铺满代码
    d.rectangle([0, 36, W, H - 26], fill=EDITOR)
    rows = []
    for i, (n, parts) in enumerate(JAVA_ROWS):
        rows.append((n, parts))
    # 再补几行，让弹窗压在代码上更真实
    extra = [
        (18, []),
        (19, [("    private ", KEY), ("boolean ", WHITE), ("passwordMatches(", FG), ("User ", WHITE), ("u, ", FG), ("String ", WHITE), ("raw) {", FG)]),
        (20, [("        return ", KEY), ("encoder.matches(raw, u.getPassword());", FG)]),
        (21, [("    }", FG)]),
        (22, [("}", FG)]),
    ]
    code_block(d, 20, 52, rows + extra, mono(14), line_h=24)

    # 弹窗（贴光标下方，模拟 IDE 悬停提示）
    px, py, pw, ph = 250, 300, 640, 54
    d.rounded_rectangle([px + 3, py + 4, px + pw + 3, py + ph + 4], radius=8, fill="#141516")
    d.rounded_rectangle([px, py, px + pw, py + ph], radius=8, fill="#3C3F41", outline="#4E5254")
    # 文字垂直居中
    ty = py + (ph - 20) // 2
    d.text((px + 16, ty), "食物和水已经收集的差不多了，他必须要在诡异找来之前，找到一辆", font=ui(13), fill="#D6D6D6")
    d.text((px + pw - 74, ty + 1), "(18/115)", font=ui(11), fill="#8C8C8C")

    # 提示文字
    d.text((px, py + ph + 20), "Alt+Shift+N 唤起 · 一句一行 · 自动换句 · Esc 关闭",
           font=ui(11), fill="#7A7E85")

    status_bar(d, "第 18 / 500 章 · 第17章 绝境！")
    return img


# ---------------------------------------------------------------- 图 3 老板键伪装

def shot_disguise():
    img, d = canvas()
    title_bar(d, "Chapter0002.java  —  com.example.demo", "src")

    d.rectangle([0, 36, W, H - 26], fill=EDITOR)
    rows = [
        (1, [("package ", KEY), ("com.example.demo;", FG)]),
        (2, []),
        (3, [("import ", KEY), ("java.util.List;", FG)]),
        (4, [("import ", KEY), ("java.util.Objects;", FG)]),
        (5, []),
        (6, [("/**", COMMENT)]),
        (7, [(" * 第二章  夜访", COMMENT)]),
        (8, [(" *", COMMENT)]),
        (9, [(" * 他推开门的时候，屋里的灯还亮着。", STR)]),
        (10, [(" * 桌上摊着一封信，字迹潦草，落款处被人用指甲划掉了。", STR)]),
        (11, [(" *", COMMENT)]),
        (12, [(" * 他看了很久，久到窗外的雨停了又下，下了又停。", STR)]),
        (13, [(" * 最后他把信折好，塞进外套内袋。", STR)]),
        (14, [(" */", COMMENT)]),
        (15, [("@SuppressWarnings", ACCENT), ("(", FG), ('"unused"', STR), (")", FG)]),
        (16, [("public final class ", KEY), ("Chapter0002 {", WHITE)]),
        (17, []),
        (18, [("    private static final String TITLE = ", FG), ('"第二章  夜访"', STR), (";", FG)]),
        (19, []),
        (20, [("    public static String title() {", FG)]),
        (21, [("        return Objects.requireNonNull(TITLE);", FG)]),
        (22, [("    }", FG)]),
        (23, [("}", FG)]),
    ]
    code_block(d, 20, 52, rows, mono(14), line_h=25, num_w=40, f_cjk=ui(13))

    # 右下角说明
    d.text((W - 420, H - 60), "老板键：看起来在读源码，其实正文就在注释里", font=ui(11), fill="#7A7E85")

    status_bar(d, "Chapter0002.java")
    return img


# ---------------------------------------------------------------- 图 4 书架

def shot_shelf():
    img, d = canvas()
    title_bar(d, "tcm-edu  —  LoginServiceImpl.java", "src/main/java")
    d.rectangle([0, 36, W, H - 26], fill=EDITOR)
    code_block(d, 20, 52, JAVA_ROWS, mono(13), line_h=22)

    # 半透明遮罩
    overlay = Image.new("RGBA", (W, H), (0, 0, 0, 110))
    img = Image.alpha_composite(img.convert("RGBA"), overlay).convert("RGB")
    d = ImageDraw.Draw(img)

    # 书架弹窗
    px, py, pw, ph = 330, 150, 520, 400
    d.rounded_rectangle([px + 6, py + 8, px + pw + 6, py + ph + 8], radius=12, fill="#141516")
    d.rounded_rectangle([px, py, px + pw, py + ph], radius=12, fill="#2B2D30", outline="#4E5254")

    d.text((px + 22, py + 20), "最近读过", font=ui(15, True), fill=WHITE)
    d.line([(px + 18, py + 52), (px + pw - 18, py + 52)], fill=BORDER)

    books = [
        ("全民恐怖求生：我觉醒加点序列！", "第 17 章 · 本地 · 2 分钟前", True),
        ("诡秘之主", "第 1320 章 · 网络 · 昨天", False),
        ("道诡异仙", "第 88 章 · 本地 · 3 天前", False),
        ("宿命之环", "第 402 章 · 网络 · 上周", False),
    ]
    by = py + 66
    for title, sub, current in books:
        if current:
            d.rounded_rectangle([px + 12, by - 6, px + pw - 12, by + 52], radius=6, fill="#35373A")
        d.text((px + 24, by), title, font=ui(12, True), fill=WHITE)
        d.text((px + 24, by + 22), sub, font=ui(10), fill=FG_DIM)
        if current:
            d.rounded_rectangle([px + pw - 74, by + 4, px + pw - 26, by + 24], radius=10, fill="#2D5B8E")
            d.text((px + pw - 66, by + 7), "当前", font=ui(10), fill=WHITE)
        by += 68

    d.line([(px + 18, py + ph - 46), (px + pw - 18, py + ph - 46)], fill=BORDER)
    d.text((px + 24, py + ph - 34), "清空记录", font=ui(11), fill=RED)

    status_bar(d, "第 18 / 500 章 · 第17章 绝境！")
    return img


# ---------------------------------------------------------------- 图 5 状态栏

def shot_statusbar():
    img, d = canvas()
    title_bar(d, "tcm-edu  —  LoginServiceImpl.java", "src/main/java")
    d.rectangle([0, 36, W, H - 34], fill=EDITOR)
    code_block(d, 20, 52, JAVA_ROWS, mono(14), line_h=24)

    # 状态栏：加高 + 加大字号，否则 1200 宽里根本看不清
    y = H - 34
    d.rectangle([0, y, W, H], fill=BAR)
    d.text((12, y + 8), "摸鱼小说: 郑笙拿着菜刀，手起刀落，手起刀落。  (18/115)",
           font=ui(13), fill="#D6D6D6")
    d.text((W - 10 - d.textlength("UTF-8   4 spaces   LF", font=ui(11)), y + 11),
           "UTF-8   4 spaces   LF", font=ui(11), fill=FG_DIM)

    # 把状态栏这一条框出来，侧边加说明，否则整张图太空
    d.rectangle([6, y - 4, W - 6, H - 4], outline=ACCENT, width=2)
    d.line([(60, y - 42), (60, y - 8)], fill=ACCENT, width=2)
    d.text((72, y - 40), "正文直接显示在状态栏：不占编辑区、不弹浮层", font=ui(12), fill=ACCENT)
    d.text((12, y - 74), "小键盘 + / - 翻段，章尾自动进下一章", font=ui(11), fill="#7A7E85")
    return img


def main():
    out = os.path.join("docs", "screenshots", "marketplace")
    os.makedirs(out, exist_ok=True)
    jobs = [
        ("01-sidebar.png", shot_sidebar),
        ("02-code-popup.png", shot_code_popup),
        ("03-disguise.png", shot_disguise),
        ("04-shelf.png", shot_shelf),
        ("05-statusbar.png", shot_statusbar),
    ]
    for name, fn in jobs:
        img = fn()
        path = os.path.join(out, name)
        img.save(path, "PNG", optimize=True)
        print("wrote", path, img.size)


if __name__ == "__main__":
    main()

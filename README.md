# 摸鱼小说阅读器（IDEA 插件）

在 IntelliJ IDEA 侧边栏里看小说，支持本地 txt 与网络小说，带章节目录、进度记忆和一键隐身。

## 功能

| 功能 | 说明 |
| --- | --- |
| 侧边工具窗 | 右侧「摸鱼小说」窗口，平时缩成小图标，点开就能读 |
| 本地 txt | 自动识别 UTF-8 / GBK / UTF-16 编码，按章节切分 |
| 网络小说 | 解析目录页得到章节列表，正文按需懒加载 |
| 章节目录 | 左侧目录树，支持搜索，双击跳章 |
| 阅读设置 | 字号、行距、字体、配色（跟随 IDE / 护眼绿 / 夜间 / 羊皮纸 / 纯白） |
| 进度记忆 | 自动记录章节和滚动位置，下次打开回到原处 |
| 自动滚动 | 按设定速度匀速下滚，滚到底自动翻下一章 |
| 老板键 | `Ctrl+Alt+Shift+X`，一键切换成 Maven 构建日志 / Java 源码 / 终端输出 |

## 快捷键

| 快捷键 | 作用 |
| --- | --- |
| `Ctrl+Alt+Shift+X` | 老板键，隐身或还原 |
| `Ctrl+Alt+→` | 下一章 |
| `Ctrl+Alt+←` | 上一章 |

也可以在 **Tools** 菜单里找到「打开本地小说...」和「打开网络小说...」。

## 安装

### 方式一：从磁盘安装（推荐，无需 Gradle）

项目里已经有打包脚本，它会直接用 IDEA 自带的 JBR 编译源码：

```bash
python tools/package_local.py
# 如果 IDEA 不在默认位置：
python tools/package_local.py --idea "D:/你的路径/IntelliJ IDEA 2026.2.0.1"
```

产出 `build/dist/idea-novel-reader-1.0.0.zip`，然后在 IDEA 里：
**Settings → Plugins → 右上角齿轮 → Install Plugin from Disk**，选中这个 zip，重启 IDE。

### 方式二：Gradle 构建

```bash
./gradlew buildPlugin
# 产出 build/distributions/idea-novel-reader-1.0.0.zip
```

构建依赖在 `gradle.properties` 里配置：

- `ideaLocalPath`：本机 IDEA 安装目录。**强烈建议配置**，否则 Gradle 会从 CDN 下载约 1GB 的 IDE 依赖。
- `org.gradle.java.installations.paths`：构建用 JDK，默认指向本机 IDEA 自带的 JBR。

## 项目结构

```
src/main/java/com/github/ideanovel/
├── model/      书籍、章节、进度模型
├── parser/     章节正则切分、编码识别、HTML 正文抽取
├── source/     本地文件源、网络小说源
├── service/    阅读服务（当前书、切章、异步加载、进度）
├── settings/   设置持久化与设置页
├── ui/         工具窗、阅读面板、章节目录、伪装面板
└── action/     各菜单项与快捷键动作
```

## 实现要点

- **章节切分**：优先按正则匹配「第X章」这类标题，匹配不到就按固定字数分块，保证任何 txt 都能读。
- **编码识别**：BOM → 严格 UTF-8 解码 → GBK，顺序探测，避免国内 txt 常见的乱码问题。
- **网络小说**：先判断打开的是目录页还是正文页；目录页解析出链接后，正文在切到该章时才异步拉取。
- **进度保存**：滚动位置每 2 秒采样一次，切章、关闭面板时落盘到 `ideanovel.xml`。
- **隐身**：阅读区用 CardLayout 与伪装面板叠在同一位置，老板键只是切换卡片，不留切换痕迹。

## 已知限制

- 网络小说依赖站点 HTML 结构，站点改版或加反爬时可能解析失败，可在设置里调整「网络小说解析章节上限」或改用本地 txt。
- 伪装只是视觉层面，不影响 IDE 的实际窗口和标题。
- 插件依赖 `com.intellij.modules.platform`，不绑定 Java 语言插件，理论上其他 JetBrains IDE 也能用（仅针对 2026.2 编译验证过）。

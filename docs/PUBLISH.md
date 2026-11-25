# 上架 JetBrains 插件市场（Marketplace）

目标：把这个插件发布到 JetBrains Marketplace，之后 IDEA 里能**一键安装 + 自动更新**，不用再手动装 zip。

> 现状：Gradle 发布配置、GitHub Actions 工作流都已就绪。**剩下需要你（账号所有者）做的事都在本文档里**，一共四步，约 20 分钟。

---

## 全景图

```
你（一次性）          GitHub（已配好）         JetBrains Marketplace
─────────           ────────────────        ─────────────────────
1. 注册 JetBrains 账号
2. 创建插件条目 ──────────────────────────→  插件 ID 就绪
3. 生成 Token ─────────────┐
4. 生成签名证书 ───────────┤
5. 填到 GitHub Secrets ←───┤
                          │
6. 推 tag / 手动触发 ──────┼──→ gradle publishPlugin ──→ 自动上传
                          └──（CI 里签名 + 上传，token 从 Secret 注入）
```

---

## 第 1 步：注册 JetBrains 账号（如果还没有）

打开 <https://account.jetbrains.com/>，注册一个账号。

> 免费的 JetBrains 账号即可，不需要购买产品。

---

## 第 2 步：在 Marketplace 创建插件条目

1. 登录后打开 <https://plugins.jetbrains.com/author/me>
2. 点 **Add new plugin**（添加新插件）
3. 填表单：
   - **Name**：`摸鱼小说阅读器`（或你想起的英文名）
   - **Plugin ID**：`com.github.ideanovel.reader`（**必须和 plugin.xml 里 `<id>` 一致**，见 `src/main/resources/META-INF/plugin.xml` 第 2 行）
   - 其他按提示填（Category 选 Other / Tools）
4. 提交后，插件条目创建完成。

> 首次创建后，JetBrains 可能要求**手动上传第一个版本**才能解锁后续自动发布。
> 如果提示要手动上传，用 IDEA 里 `Build > Prepare Plugin Module '...' for Deployment`，
> 或本地跑一次 `gradle buildPlugin` 产出 zip 后上传即可（这一步也可以跳过，先试自动发布）。

---

## 第 3 步：生成 Personal Access Token

1. 打开你的 profile 页：<https://plugins.jetbrains.com/author/me>
2. 找到 **My Tokens** 区块
3. 点 **Generate Token**，起个名字（如 `github-actions`）
4. **复制生成的 token**（`perm:...` 开头的一长串）

> ⚠️ 这个 token 只在生成时显示一次，关掉页面就再也看不到了。**先复制保存好**。

---

## 第 4 步：生成签名证书

Marketplace 要求插件必须签名。用下面任一方式生成一对 RSA 证书（私钥 + 证书链）。

### 方式 A：openssl（推荐，Windows 的 Git Bash / Linux / macOS 都有）

```bash
# 1. 生成私钥（不加密，方便 CI 注入）
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out privateKey.pem

# 2. 生成自签名证书（有效期 10 年）
openssl req -new -x509 -key privateKey.pem -out certificateChain.crt \
  -days 3650 -subj "/CN=idea-novel-reader"
```

### 方式 B：keytool（JDK 自带）

```bash
keytool -genkeypair -keystore chain.ks -storetype PKCS12 \
  -alias signKey -keyalg RSA -keysize 2048 -validity 3650 -storepass <你的密码>
```

> 不管哪种方式，最终需要三个东西：
> - **证书链**（certificateChain）——`certificateChain.crt` 的内容，或 keytool 导出的证书
> - **私钥**（privateKey）——`privateKey.pem` 的内容
> - **私钥密码**（password）——openssl 不加密就是空字符串

---

## 第 5 步：把证书公钥上传到 Marketplace

1. 打开插件详情页：<https://plugins.jetbrains.com/plugin/你的插件>
2. 找到 **Signing** 区块
3. 把 `certificateChain.crt` 的内容（`-----BEGIN CERTIFICATE-----` 到 `-----END CERTIFICATE-----`）粘贴进去并保存

> 这样 Marketplace 才知道「这个插件是由持有对应私钥的人发布的」。

---

## 第 6 步：配置 GitHub Secrets

在 GitHub 仓库（`Se7eni/idea-novel-reader`）里：

1. 打开仓库 **Settings → Secrets and variables → Actions → New repository secret**
2. 添加以下 4 个 secret：

| Secret 名 | 值 |
| --- | --- |
| `PUBLISH_TOKEN` | 第 3 步生成的 token |
| `CERTIFICATE_CHAIN` | `certificateChain.crt` 的完整内容（含 BEGIN/END 行） |
| `PRIVATE_KEY` | `privateKey.pem` 的完整内容（含 BEGIN/END 行） |
| `PRIVATE_KEY_PASSWORD` | 私钥密码；openssl 未加密就留空（填一个空格或空串） |

---

## 第 7 步：发布

### 首次发布（推荐先手动触发）

1. 打开仓库 **Actions** 页
2. 左侧选 **Publish Plugin** 工作流
3. 点 **Run workflow** → 确认运行

### 之后每次发新版

1. 把 `gradle.properties` 里的 `pluginVersion` 改成新版本号（Marketplace 不接受同版本重复上传）
2. 提交并推 tag：
   ```bash
   git tag v1.0.18
   git push origin v1.0.18
   ```
3. CI 会自动 `gradle publishPlugin` 上传

---

## 常见问题

**Q：CI 报「缺少签名凭证环境变量」？**
说明第 6 步的 4 个 secret 没配全（名字要完全一致，大小写敏感）。

**Q：CI 下载依赖很慢？**
首次跑要下载 IntelliJ SDK（约 1GB），是正常的，后续有缓存会快很多。

**Q：提示「插件已存在同版本」？**
`pluginVersion` 没改，Marketplace 不允许同版本重复上传，改成新版本号即可。

**Q：token 泄露了怎么办？**
去 Marketplace profile 页把旧 token 删掉重新生成，再更新 GitHub Secret。

**Q：能不能本地发布（不走 CI）？**
可以，但要本机能直连 plugins.jetbrains.com（当前环境代理对它不稳）。命令：
```bash
# 本地设环境变量后跑
gradle publishPlugin
```
不过推荐走 CI，网络干净且 token 不落地到本地。

---

## 实战记录（2026-11-25 首次上架）

- **pluginId**：`36457`
- **插件页**：https://plugins.jetbrains.com/plugin/36457-se7eni-novel-reader
- **首发版本**：1.0.20（网页手动上传，已提交审核，最多 2 个工作日）
- **vendor**：ideanovel（Non-trader）
- **插件名**：Se7eni Novel Reader

### 命名与描述的四条硬规则（踩坑记录）

Marketplace 的校验**一次只报一个错**，定名前务必先自查：

1. **名称只能是纯 ASCII**：只允许字母、数字、空格和 `.,+-/:()#&[]|`，中文会被拒。
2. **名称不能含商标词**：`IDEA`、`IntelliJ`、`JetBrains` 等受保护词不能出现在名称里。
3. **名称必须全局唯一**：`Novel Reader` 已被占用（"摸鱼"概念也被 `zero-moyu` 抢先）。
   定名前先查重：
   `https://plugins.jetbrains.com/api/searchPlugins?search=<关键词>`
4. **description 必须以拉丁字符开头且 ≥ 40 字符**：中文开头的 `<p>` 不行，
   第一个 `<p>` 里要放英文段落。

### 首次上传必须手动

`gradle publishPlugin` 在插件条目不存在时会报：

> `Cannot find plugin. Note that you need to upload the plugin to the repository at least once manually`

即使用 Upload API（`POST /api/updates/upload` + `xmlId`）也一样，因为条目不存在。
所以**第一次必须走网页 Upload Plugin 页面**（选文件 + 填 License/Tags/Source URL）。
之后 CI 的 `publishPlugin` 才能正常工作。

> Tags 是**必填**项，且列表是技术向分类（没有"阅读/娱乐"），
> 选「工具集成」（Tool Integration）或「用户界面」最贴切。

### 后续发布（CI）

条目已存在，之后每次发版：

1. bump `gradle.properties` 的 `pluginVersion`（**不能重复上传同版本**）
2. 推送 tag 或手动触发 `Publish Plugin` workflow
3. CI 自动编译（双 JDK：Gradle 跑 21、编译 toolchain 用 25）+ 签名 + 上传

⚠️ 如果 stable 版本还在审核中，发新版可能排队；想先验证流水线可改用 beta 频道。

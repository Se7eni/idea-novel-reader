plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.github.ideanovel"
version = providers.gradleProperty("pluginVersion").get()

java {
    // IDEA 2026.2 运行在 JBR 25 上，平台 SDK 的 class 文件也是 JDK 25 编译的。
    // 用 21 编译会报 "bad class file"（javac 读不了更高版本的字节码），所以这里必须用 25。
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 优先使用本机已安装的 IDEA 作为 SDK，省去下载数 GB 依赖的时间
        val localIdea = providers.gradleProperty("ideaLocalPath").orNull
        if (localIdea != null && localIdea.isNotBlank() && file(localIdea).exists()) {
            local(file(localIdea))
        } else {
            // 没配本机路径就从 CDN 下载对应 IDE（较慢，约 1GB 起）
            intellijIdeaUltimate(providers.gradleProperty("platformVersion").get())
        }

        // 插件只在 platform 上依赖，不强制依赖 Java 插件，兼容所有 JetBrains IDE
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    // 关闭可搜索选项索引，显著加快构建速度
    buildSearchableOptions = false

    // 插件签名：上架 Marketplace 必须签名。三个值都从环境变量读，
    // 本地 buildPlugin 不签名也能跑；发布（publishPlugin）时 CI 通过 Secret 注入。
    // 证书生成方式见 docs/PUBLISH.md
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // 发布到 JetBrains Marketplace。token 默认读 PUBLISH_TOKEN 环境变量，
    // GitHub Actions 里通过 Secret 注入，绝不明文写进仓库。
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf("default")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// 发布前强制检查签名凭证，避免「没配签名却跑 publishPlugin」被静默跳过
tasks {
    named("publishPlugin") {
        dependsOn("signPlugin")
        doFirst {
            // 私钥可以不加密，所以 PRIVATE_KEY_PASSWORD 不强制要求
            val missing = listOf("CERTIFICATE_CHAIN", "PRIVATE_KEY")
                .filter { System.getenv(it).isNullOrBlank() }
            if (missing.isNotEmpty()) {
                throw GradleException("缺少签名凭证环境变量：${missing.joinToString()}，见 docs/PUBLISH.md")
            }
        }
    }
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}

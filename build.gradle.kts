plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.github.ideanovel"
version = providers.gradleProperty("pluginVersion").get()

java {
    // IDEA 2026.2 运行在 JBR 25 上，这里用 21 编译以保证字节码兼容性
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
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

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}

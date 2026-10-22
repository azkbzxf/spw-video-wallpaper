import com.xuncorp.spw.workshop.gradle.PluginPermission

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.compose") version "2.4.20"
    kotlin("kapt") version "2.4.20"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev23"
}

kotlin {
    jvmToolchain(25)
    sourceSets.main {
        // 与 FFmpeg 版共用同一份 UI / hook / 配置代码；
        // 本模块只额外提供「用 SPW Media Runtime 解码」的 VideoEngine
        kotlin.srcDirs("../plugin/src/main/kotlin", "src/main/kotlin")
    }
}

dependencies {
    // API 与 Kotlin 标准库由宿主提供
    compileOnly("com.github.Moriafly:spw-workshop-api:0.1.0-dev23")
    compileOnly(kotlin("stdlib"))
    kapt("com.github.Moriafly:spw-workshop-api:0.1.0-dev23")

    // Compose：宿主持有运行时
    compileOnly("org.jetbrains.compose.desktop:desktop-jvm-windows-x64:1.12.1")
    compileOnly("org.jetbrains.compose.foundation:foundation:1.12.1")
    compileOnly("org.jetbrains.compose.ui:ui:1.12.1")

    // 只带 SDK（约 250 KB），FFmpeg / libmpv 由 SPW Media Runtime 提供
    implementation("io.github.gaboron.spwmedia:spw-media-sdk:0.2.0")
}

// 产物名带 -lite，和自带 FFmpeg 的完整版区分开（插件内部的版本号保持一致）
tasks.named("plugin") {
    doLast {
        val dir = layout.buildDirectory.dir("libs").get().asFile
        val src = dir.resolve("plugin-com.videowallpaper.spw-1.1.0.spmod")
        val dst = dir.resolve("plugin-com.videowallpaper.spw-1.1.0-lite.spmod")
        if (src.isFile) {
            if (dst.exists()) dst.delete()
            src.renameTo(dst)
        }
    }
}

spmod {
    PluginClass = "com.videowallpaper.MainPlugin"
    PluginId = "com.videowallpaper.spw"
    PluginVersion = "1.1.0"
    PluginProvider = "video-wallpaper contributors"
    PluginName = "视频壁纸 Lite"
    PluginDescription = "视频壁纸的精简版：体积小，需要先安装并启用 SPW Media Runtime"
    PluginOpenSourceUrl = ""
    PluginHasConfig = false
    PluginPermissions = listOf(
        PluginPermission.CLASS_TRANSFORM
    )
}

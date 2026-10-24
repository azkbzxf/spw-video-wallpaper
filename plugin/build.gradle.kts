import com.xuncorp.spw.workshop.gradle.PluginPermission

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.compose") version "2.4.20"
    kotlin("kapt") version "2.4.20"
    // Workshop 打包插件：生成 .spmod（SPW 1.19 / dev23）
    id("com.xuncorp.spw.workshop") version "0.1.0-dev23"
}

kotlin {
    jvmToolchain(25)
    sourceSets.main {
        // 与 lite 版共用同一份 UI / hook / 配置代码；本模块额外提供 FFmpeg 解码后端
        kotlin.srcDirs("src/main/kotlin", "src/ffmpeg/kotlin")
    }
}

dependencies {
    // API 与 Kotlin 标准库由宿主提供
    compileOnly("com.github.Moriafly:spw-workshop-api:0.1.0-dev23")
    compileOnly(kotlin("stdlib"))
    kapt("com.github.Moriafly:spw-workshop-api:0.1.0-dev23")

    // Compose：宿主持有运行时，compileOnly 对齐宿主 1.19（Compose Multiplatform 1.12.x）
    compileOnly("org.jetbrains.compose.desktop:desktop-jvm-windows-x64:1.12.1")
    compileOnly("org.jetbrains.compose.foundation:foundation:1.12.1")
    compileOnly("org.jetbrains.compose.ui:ui:1.12.1")

    // 视频解码：JavaCPP FFmpeg（打包进 spmod 的 lib/，由插件 classloader 加载）
    implementation("org.bytedeco:ffmpeg:7.1.1-1.5.12")
    runtimeOnly("org.bytedeco:ffmpeg:7.1.1-1.5.12:windows-x86_64")
    implementation("org.bytedeco:javacpp:1.5.12")
    runtimeOnly("org.bytedeco:javacpp:1.5.12:windows-x86_64")
}

spmod {
    PluginClass = "com.videowallpaper.MainPlugin"
    PluginId = "com.videowallpaper.spw"
    PluginVersion = "1.0.2"
    PluginProvider = "video-wallpaper contributors"
    PluginName = "视频壁纸"
    PluginDescription = "让壁纸支持导入视频并实时循环播放"
    PluginOpenSourceUrl = ""
    PluginHasConfig = false
    // CLASS_TRANSFORM：Hook 壁纸渲染组件与壁纸设置面板（最高危权限）
    PluginPermissions = listOf(
        PluginPermission.CLASS_TRANSFORM
    )
}

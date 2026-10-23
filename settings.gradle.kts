pluginManagement {
    repositories {
        maven("https://jitpack.io")
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            // JitPack 多模块发布物的 groupId 与插件标记坐标不同，必须保留此映射
            if (requested.id.id == "com.xuncorp.spw.workshop") {
                useModule(
                    "com.github.Moriafly.spw-workshop-api:spw-workshop-gradle-plugin:${requested.version}"
                )
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "spw-video-wallpaper"

include("plugin")
// 精简版：不带 FFmpeg，改用 SPW Media Runtime 解码
include("plugin-lite")

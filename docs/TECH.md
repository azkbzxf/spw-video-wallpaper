# 技术细节

面向想改代码或适配新版本宿主的人。普通使用请看 [README](../README.md)。

- 目标宿主：**SPW 1.19.x**（Workshop API `0.1.0-dev23`），已在 1.19.0 与 1.19.2 上实测
- 权限：`class-transform`
- 插件 ID：`com.videowallpaper.spw`

---

## 两个注入点

宿主是 Compose Multiplatform 应用，类名经过 R8 混淆，只能按「被调用的 composable + 调用点所在类」定位，
并要求调用点在目标类里**唯一**，否则宿主会抛 `UI call missing or ambiguous`。

> **混淆名每个版本都可能变。** 1.19.0 → 1.19.2 就把 `Wallpaper` 从 `ϰ`(U+03F0) 改成了 `ύ`(U+03CD)、
> 调用点从 `Պ`(U+054A) 改成了 `ѐ`(U+0450)。所以 `Hooks.kt` 里每个注入点都是一组 `UiPoint` 候选，
> 注册时逐个试，第一个成功的生效；宿主再换名字时只需往列表里补一条。

**① 壁纸渲染层**（`Wallpaper.desktop.kt:26`）

```kotlin
// 1.19.2: androidx.compose.ui.ѐ (U+0450)     1.19.0: androidx.compose.ui.Պ (U+054A)
AnimatedVisibility(visible = wallpaperFilePath.isNotEmpty(), fadeIn(1000), fadeOut(1000)) {
    // 1.19.2: androidx.compose.ui.ύ (U+03CD)   1.19.0: androidx.compose.ui.ϰ (U+03F0)
    Wallpaper(Modifier)      // 两版都在类内唯一调用（两版偏移都是 offset 57）
}
```

在 `Wallpaper(...)` 之后追加一层 `fillMaxSize` 的绘制层。
`AnimatedVisibility` 的多个子节点互相重叠、后绘制在上，所以这层正好**盖住图片壁纸、位于所有界面内容之下**。

**② 壁纸设置面板**（`WallpaperFilePanel.desktop.kt:73`）

```kotlin
// com.xuncorp.voxzen.ui.screen.appearance.ރ (U+0783) —— 1.19.0 / 1.19.2 相同
Column {
    ...选择壁纸图片 / 壁纸材质 / 删除壁纸 / 说明文字
    ࠤ(U+0824).Ϳ(String, ࠨ(U+0828), Modifier, ...)   // 说明文字，同签名全类唯一
}
```

在这条说明文字之后追加，内容于是成为面板 `Column` 的最后一个子节点，即底部的「视频壁纸」卡片。

> 方法名 `Ϳ` 是 **U+037F**（希腊问号），不是 U+047F。写错时宿主会报
> `UI call missing or ambiguous … (0 calls)` —— 候选方法列表为空，而不是「签名不符」。

### 适配新宿主版本的做法

1. 从宿主安装目录的 `app/ffmpeg-x64.dll`（polyglot jar，`zipfile` 可读）里找包含
   `Wallpaper.desktop.kt` / `WallpaperFilePanel.desktop.kt` 字符串的 class，那就是新类名；
2. 在所有 class 里搜同时含该类名和 `(Landroidx/compose/ui/Modifier;Landroidx/compose/runtime/Composer;II)V`
   的那些，挑出「对它只有一处调用」的类作为调用点；
3. 把新的 `UiPoint` 加到 `Hooks.kt` 候选列表**最前面**，重新打包。

## 解码管线

```
FFmpeg (JavaCPP presets 7.1.1)
   ├─ avformat_open_input / av_find_best_stream     打开容器、选视频流
   ├─ avcodec_send_packet / avcodec_receive_frame   解出 AVFrame
   └─ sws_scale                                     缩放到 ≤1600px 宽、转 BGRA
        ↓  BGRA 字节
Skia Bitmap.installPixels → Image.makeFromBitmap → ImageBitmap
        ↓
Compose Canvas.drawImage（Crop） + 暗化遮罩
```

一条后台解码线程按 PTS 推进：

- 计算 `target = 起始时刻 + pts / speed`，提前就 `sleep`、落后超过 260ms 就**丢帧**，保证时间轴不漂移；
- 读到 EOF 就 `av_seek_frame(0)` + `avcodec_flush_buffers` 回到开头（当前片段循环）；
- 暂停时线程停在当帧空转，恢复时把暂停时长补偿进时间轴；
- 换片时旧解码器关闭、新解码器打开，期间画面保留上一帧，不会闪黑。

渲染侧只在 **draw 阶段**读帧状态（`Canvas` 的 onDraw lambda），每帧只触发重绘、不触发重组。

## 换片是怎么触发的

`WindowRestoreWatcher` 每 350ms 轮询一次宿主主窗口（`Window.getWindows()` + `Frame.extendedState`）：

- 没见过窗口真正显示时（启动阶段 `isVisible=false`）只记录基线，**不算最小化**，避免开机就误换一次；
- 记录到 `ICONIFIED`（或托盘隐藏 `isVisible=false`）之后又变回正常 → 判定为「从最小化还原」；
- 延迟 200ms 再通知引擎（避开窗口还原动画），引擎收到后置 `skipFlag`，
  解码线程在 12ms 内响应并换到下一个随机片段。

用轮询而不是 `WindowStateListener`，是因为宿主主窗口在插件加载之后才创建/替换，监听器容易挂到旧窗口上。

## 为什么包这么大

插件里打包了 FFmpeg 的 Windows x64 运行库（`lib/ffmpeg-*-windows-x86_64.jar`，解压后约 66 MB，
压缩进 spmod 后约 26 MB）。这样换台机器不需要额外装 FFmpeg，也不依赖宿主自带的版本。

---

## 构建

```bash
cd spw-video-wallpaper
build.bat            # Windows：用项目内 GRADLE_USER_HOME 打包
# 或手动
GRADLE_USER_HOME=$(pwd)/.gradle-home gradle --no-daemon :plugin:plugin
```

产物：`plugin/build/libs/plugin-com.videowallpaper.spw-1.0.1.spmod`

本机踩过的三个坑（已写进 `gradle.properties` / `build.bat`）：

1. `~/.gradle/init.d` 里的脚本会把仓库重定向到阿里云镜像，JitPack 依赖搜不到 →
   必须用项目内独立的 `GRADLE_USER_HOME`。
2. Gradle 必须跑在 **JDK 21**（`org.gradle.java.home`），但字节码目标是 25（宿主 JVM）。
3. `kotlin.stdlib.default.dependency=false` 必须保留，否则 stdlib 会打进 `lib/`，与宿主提供的冲突。

```
plugin/src/main/kotlin/com/videowallpaper/
├── MainPlugin.kt              # PF4J 入口：init → 注册 hook（失败转后台重试）→ 恢复播放 → 写 last-start.txt
├── HostBridge.kt              # 反射读写宿主 AppConfig.wallpaperFilePath
├── HostTheme.kt               # 明暗主题判定（反射读宿主调色板）
├── WindowRestoreWatcher.kt    # 盯主窗口「最小化 → 还原」，触发换片
├── VwLog.kt                   # 日志环 + properties 配置
├── hook/
│   ├── Hooks.kt               # 两个 afterComposable 注入点
│   └── HookInstaller.kt       # 注册失败的后台退避重试
├── ui/
│   ├── VideoWallpaperLayer.kt # 壁纸渲染层（Crop 绘制 + 暗化）
│   └── VideoWallpaperSection.kt # 设置面板卡片
└── video/
    ├── VideoDecoder.kt        # FFmpeg → BGRA
    ├── VideoLibrary.kt        # 文件夹扫描（4 层 / 600 个上限）
    └── VideoEngine.kt         # 解码线程、PTS 节奏、循环、换片
```

`.verify/` 是真机验证用的小工具（部署、抢前台、截图、窗口相对点击、最小化/还原），不是交付内容。

---

## 排障（技术向）

插件目录下的 `last-start.txt`：

| 现象 | 含义 |
|---|---|
| `wallpaperLayerMounted=false` | 渲染层没挂上：宿主版本不符，或 `class-transform` 未授权 |
| `panelMounted=false` | 设置面板没挂上：同上（两处独立注册，可能只挂上一个） |
| `engineState=Error` + `engineError=...` | 解码失败：文件损坏 / 编码不被支持 / 路径失效 |
| 日志里 `UI call missing or ambiguous … (0 calls)` | 调用点没匹配上：`inClass` 不对，或方法名码点写错 |
| 日志里 `Hooks require a starting or started plugin` | 注册时机问题，等后台重试（1.2s / 3.5s / 8s） |
| 日志里没有 `窗口从最小化恢复` | 窗口监听没抓到：确认是**最小化**而不是切到后台；托盘隐藏也算一种 |

宿主的 `Wallpaper` 组件只在壁纸路径非空时才会被组合，所以**没有设置任何壁纸时，渲染层也不会存在**——
这是正常现象，先用面板选一个视频或文件夹即可。

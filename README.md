# 视频壁纸 · Salt Player for Windows 插件

给 [Salt Player for Windows](https://github.com/Moriafly/SaltPlayerSource)（下称 SPW）的壁纸功能加上**视频壁纸**：
在「外观 → 壁纸」里选一段 MP4 / WebM / MKV，它就会铺满窗口、静音、循环播放。

也可以直接选一个**文件夹**：每次你把窗口从最小化还原回来，它就从文件夹里换一个视频。
播放过程中不会自动切，不会打断你正在看的东西。

- 目标宿主：**SPW 1.19.0**（Workshop API `0.1.0-dev23`）
- 产物：`plugin-com.videowallpaper.spw-1.0.0.spmod`（约 28 MB，含 FFmpeg 运行库）
- 权限：`class-transform`（必须授予，否则无法注入壁纸层与设置面板）

---

## 特性

| | |
|---|---|
| 两种来源 | **单文件**：一直循环这一个；**文件夹**：建一个视频池，按需换片 |
| 换片时机 | 文件夹模式下**只在窗口从最小化还原时**随机换一个（也有「换一个」按钮手动换） |
| 实时播放 | 按视频原始帧时间戳（PTS）推进，30/60fps 片源都按原速播放，不靠定时器硬凑 |
| 循环 | 当前片段播完自动回到第一帧，无黑场 |
| 铺满 | 等比例裁剪（`ContentScale.Crop`），窗口缩放不变形、不拉伸 |
| 静音 | 只取视频轨，不解码音频，不与播放器的声音打架 |
| 可控 | 面板内可暂停 / 继续 / 换一个 / 移除；暂停状态重启后保留 |
| 轻干扰 | 默认叠一层 18% 暗化，保证专辑封面与文字仍然清晰（可调） |

支持 FFmpeg 能解的容器与编码：MP4 / M4V / MOV / MKV / WebM / AVI / FLV / WMV / TS / MPEG / 3GP / OGV …
（H.264、H.265/HEVC、VP9、AV1 等常见编码；具体取决于 FFmpeg 7.1 内置解码器）

---

## 截图

设置入口（外观 → 壁纸，面板底部的「视频壁纸」卡片）：

![设置面板](docs/settings-panel.png)

---

## 安装

1. 从 [Releases](../../releases) 下载 `plugin-com.videowallpaper.spw-1.0.0.spmod`。
2. 打开 SPW → 设置 → **插件 / Workshop** → 「从文件安装」，选择该文件。
3. 在插件列表里给「视频壁纸」**授予 `class-transform` 权限**（权限逐项确认；不授予只能加载、无法注入 UI）。
4. 重启 SPW。

## 使用

设置 → **外观** → **壁纸**，面板最下方会多出「视频壁纸」卡片。

**单文件**

1. 点 **选择视频**，挑一个视频文件 → 立即生效，一直循环这段。
2. **暂停 / 继续**：停在当前帧，再点继续。
3. **移除**：清空壁纸路径，恢复成没有壁纸的样子。

**文件夹（随机换片）**

1. 点 **选择文件夹**，选一个装着视频的目录（会递归扫 4 层，最多取 600 个文件）。
2. 插件随机挑一个开始播放，之后**一直循环这一个**。
3. 把窗口最小化，再打开 —— 会随机换成池子里的另一个。
   （不想等最小化，也可以点面板上的 **换一个** 立刻换。）
4. 卡片上会显示当前片段在池子里的位置，例如 `播放中：xxx.mp4 · 3/12`。

> 选中的视频**不会被复制**，插件只是把路径记下来。
> 移动、重命名或删除原文件后，壁纸会失效，需要重新选择。

---

## 高级配置

插件目录下的 `video-wallpaper.properties` 可以直接手改（改完重启 SPW 生效，UTF-8 编码）：

```properties
sourceMode=folder                              # file = 单文件循环，folder = 文件夹随机
folderPath=C\:/Users/me/Videos/wallpapers      # 文件夹模式的目录
videoPath=C\:/Users/me/Videos/wallpaper.mp4    # 单文件模式的视频（路径里的冒号要转义）
enabled=true                                   # false = 临时停掉注入
paused=false                                   # 启动时是否停在第一帧
speed=1.0                                      # 播放倍率 0.1 ~ 3.0
dim=0.18                                       # 暗化遮罩 0 ~ 0.8
```

插件目录（Windows 商店版）：

```
%APPDATA%\Salt Player for Windows\workshop\plugins\plugin-com.videowallpaper.spw-1.0.0\
```

同目录下还有两份运行期证据：

- `last-start.txt` —— 启动结论（hook 是否挂上、来源模式、引擎状态、错误信息）
- `video-wallpaper.log` —— 追加式实时日志（换片、循环次数、异常都会落盘）

---

## 工作原理

### 两个注入点

宿主是 Compose Multiplatform 应用，1.19.0 的类名经过 R8 混淆，只能按「被调用的 composable + 调用点所在类」定位，
并要求调用点在目标类里**唯一**，否则宿主会抛 `UI call missing or ambiguous`。

**① 壁纸渲染层**（`Wallpaper.desktop.kt:26`）

```kotlin
// androidx.compose.ui.Պ (U+054A = MainWindowContentKt)
AnimatedVisibility(visible = wallpaperFilePath.isNotEmpty(), fadeIn(1000), fadeOut(1000)) {
    Wallpaper(Modifier)      // androidx.compose.ui.ϰ (U+03F0) —— 全类唯一调用点
}
```

在 `Wallpaper(...)` 之后追加一层 `fillMaxSize` 的绘制层。
`AnimatedVisibility` 的多个子节点互相重叠、后绘制在上，所以这层正好**盖住图片壁纸、位于所有界面内容之下**。

**② 壁纸设置面板**（`WallpaperFilePanel.desktop.kt:73`）

```kotlin
// com.xuncorp.voxzen.ui.screen.appearance.ރ (U+0783)
Column {
    ...选择壁纸图片 / 壁纸材质 / 删除壁纸 / 说明文字
    ࠤ(U+0824).Ϳ(String, ࠨ(U+0828), Modifier, ...)   // 说明文字，同签名全类唯一
}
```

在这条说明文字之后追加，内容于是成为面板 `Column` 的最后一个子节点，即底部的「视频壁纸」卡片。

### 解码管线

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

### 换片是怎么触发的

`WindowRestoreWatcher` 每 350ms 轮询一次宿主主窗口（`Window.getWindows()` + `Frame.extendedState`）：

- 没见过窗口真正显示时（启动阶段 `isVisible=false`）只记录基线，**不算最小化**，避免开机就误换一次；
- 记录到 `ICONIFIED`（或托盘隐藏 `isVisible=false`）之后又变回正常 → 判定为「从最小化还原」；
- 延迟 200ms 再通知引擎（避开窗口还原动画），引擎收到后置 `skipFlag`，解码线程在 12ms 内响应并换到下一个随机片段。

用轮询而不是 `WindowStateListener`，是因为宿主主窗口在插件加载之后才创建/替换，监听器容易挂到旧窗口上。

### 为什么包这么大

插件里打包了 FFmpeg 的 Windows x64 运行库（`lib/ffmpeg-*-windows-x86_64.jar`，解压后约 66 MB，
压缩进 spmod 后约 26 MB）。这样换台机器不需要额外装 FFmpeg，也不依赖宿主自带的版本。

---

## 已知限制

- **只精确适配 1.19.0**：注入点依赖混淆后的类名与码点，宿主换版本后 hook 会注册失败并静默降级
  （只在日志里记录，不影响宿主运行），需要重新反编译对齐。
- **无声音**：视频壁纸不分层播放音频，避免和播放器抢音频设备。
- **不复制文件**：不会把视频拷进应用数据目录，移动原文件后需要重新选择。
- **换片只认「最小化还原」**：切到别的应用再切回来不算（窗口没有真的最小化），这是刻意的——
  否则每次切换窗口都换片会很吵。需要立刻换请点「换一个」。
- **不与「壁纸材质」模糊联动**：宿主的 haze 模糊作用在图片壁纸那一层，视频层是独立叠加的，
  所以开启「壁纸材质」不会让视频变模糊（暗化遮罩仍然生效）。
- 首次打开某个视频有约 100–200ms 的容器解析延迟，换片瞬间画面会停在上一帧直到新片段出第一帧。

---

## 排障

先看插件目录的 `last-start.txt`：

| 现象 | 含义 |
|---|---|
| `wallpaperLayerMounted=false` | 渲染层没挂上：宿主版本不符，或 `class-transform` 未授权 |
| `panelMounted=false` | 设置面板没挂上：同上（两处独立注册，可能只挂上一个） |
| `engineState=Error` + `engineError=...` | 解码失败：文件损坏 / 编码不被支持 / 路径失效 |
| 日志里 `UI call missing or ambiguous … (0 calls)` | 调用点没匹配上：`inClass` 不对，**或方法名码点写错**（`Ϳ` 是 U+037F，不是 U+047F） |
| 日志里 `Hooks require a starting or started plugin` | 注册时机问题，等后台重试（1.2s / 3.5s / 8s） |
| 日志里没有 `窗口从最小化恢复` | 窗口监听没抓到：确认是**最小化**而不是切到后台；托盘隐藏也算一种 |

宿主的 `Wallpaper` 组件只在壁纸路径非空时才会被组合，所以**没有设置任何壁纸时，渲染层也不会存在**——
这是正常现象，先用面板选一个视频或文件夹即可。

---

## 构建

```bash
cd spw-video-wallpaper
build.bat            # Windows：用项目内 GRADLE_USER_HOME 打包
# 或手动
GRADLE_USER_HOME=$(pwd)/.gradle-home gradle --no-daemon :plugin:plugin
```

产物：`plugin/build/libs/plugin-com.videowallpaper.spw-1.0.0.spmod`

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

## 兼容性

| SPW | Workshop API | 状态 |
|---|---|---|
| 1.19.0 | 0.1.0-dev23 | ✅ 已验证 |
| 其他版本 | — | ❓ 未验证，hook 大概率注册失败（静默降级） |

---

## License

MIT

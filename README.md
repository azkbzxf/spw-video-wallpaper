# 视频壁纸

把视频当壁纸用。铺满 Salt Player 的窗口，静音循环播放。

![设置面板](docs/settings-panel.png)

支持 **Salt Player for Windows 1.19.x**（已在 1.19.0 与 1.19.2 上跑通），Windows 64 位。

---

## 两个版本，装一个就行

功能完全一样，区别只在「视频解码器从哪来」：

| | **完整版**（推荐） | **Lite 版** |
|---|---|---|
| 下载包 | `-ffmpeg.spmod` · **28 MB** | `-lite.spmod` · **928 KB** |
| 解码器 | 插件自带 FFmpeg 7.1.1 | 借用 **SPW Media Runtime** 的 FFmpeg / libmpv |
| 额外依赖 | 无 | **必须先装 SPW Media Runtime** |
| 适用宿主 | 所有版本 | 见下方「Lite 版的前提」 |

两个版本插件 ID 相同，**不要同时安装**。

### Lite 版的前提（重要）

1. 需要先装并启用 [**SPW Media Runtime**](https://github.com/GaBoron/SPW-Media-Runtime) **0.2.0 或更高**
   （它自带 FFmpeg + libmpv，Lite 版就是借它的）。
2. **仅适用于 Steam 版 / 便携版宿主**。
   Microsoft Store 版把程序装在 `C:\Program Files\WindowsApps\…`，该目录下的 `jvm.dll`
   只允许包内进程加载，Runtime 的播放后端（独立子进程）会被系统拒绝，
   表现为壁纸页显示 `BACKEND_EXITED`。商店版用户请装完整版。

---

## 能做什么

- **视频当壁纸**：选一段 MP4 / MKV / WebM，直接铺满窗口，不拉伸不变形
- **两种用法**：
  - 选一个视频 —— 一直循环这一段
  - 选一个文件夹 —— 每次你**把窗口最小化再打开**，换里面随机的一个
- **不吵**：播放过程中不会自动切视频，也不会出声
- **随时能关**：面板里可以暂停、换一个、移除

---

## 安装

### 完整版

1. 到 [Releases](https://github.com/azkbzxf/spw-video-wallpaper/releases) 下载 `spw-video-wallpaper-1.0.2-ffmpeg.spmod`
2. 打开 Salt Player → 设置 → **插件 / Workshop** → 从文件安装
3. 在插件列表里给「视频壁纸」**允许 `class-transform` 权限**
   （不允许的话插件能装上，但界面上不会出现任何东西）
4. 重启 Salt Player

### Lite 版

1. 先装 **SPW Media Runtime 0.2.0+**（从它的 [Releases](https://github.com/GaBoron/SPW-Media-Runtime/releases) 下载，同样「从文件安装」），
   并在插件列表里**启用**它
2. 再装 `spw-video-wallpaper-1.0.2-lite.spmod`
3. 两个插件都要给 `class-transform` 权限
4. 重启 Salt Player

> 顺序很重要：Runtime 必须先装好并启用。装反了也不致命 —— 壁纸页会提示
> 「未检测到 SPW Media Runtime」，装好 Runtime 后重启一次即可。

---

## 使用

打开 **设置 → 外观 → 壁纸**，面板最下面会多出一块「视频壁纸」。

### 用一个视频

点 **选择视频**，挑一个文件。立刻生效，之后一直循环这一段。

### 用一整个文件夹

点 **选择文件夹**，选一个装着视频的文件夹（会连子文件夹一起找，最多 4 层）。

插件会随机挑一个开始播。之后：

- **不会自动换片**，你正在看的这段会一直循环；
- 想换的时候，把窗口**最小化，再打开**，就会随机换成另一个；
- 不想等最小化，也可以直接点面板上的 **换一个**。

卡片上会显示当前是第几个，比如 `播放中：xxx.mp4 · 3/12`。

> 插件只是记住文件路径，**不会复制视频**。
> 如果你移动或删除了原文件，壁纸会失效，重新选一次就好。

---

## 想调一下

插件目录里有个 `video-wallpaper.properties`，用记事本打开改，改完重启 Salt Player：

```properties
dim=0.18      # 画面暗化的程度，0 = 不暗，0.8 = 很暗
speed=1.0     # 播放速度，1.0 = 原速
paused=true   # 想让壁纸停在第一帧就改成 true
```

文件位置（Microsoft Store 版）：

```
%APPDATA%\Salt Player for Windows\workshop\plugins\plugin-com.videowallpaper.spw-1.0.2\
```

---

## 常见问题

**壁纸页里没有「视频壁纸」这一块？**
八成是权限没给。去 设置 → 插件 / Workshop，给「视频壁纸」勾上 `class-transform`，然后重启。

**（Lite 版）提示「未检测到 SPW Media Runtime」？**
Lite 版自己不带解码器，要先装 [SPW Media Runtime](https://github.com/GaBoron/SPW-Media-Runtime)
并在插件列表里启用它，然后重启一次 Salt Player。

**（Lite 版）壁纸页显示 `BACKEND_EXITED`？**
说明 Runtime 的播放后端起不来。最常见的原因是宿主是 **Microsoft Store 版**（装在 WindowsApps 里，
`jvm.dll` 不让外部子进程加载）。换完整版即可。

**选了视频，窗口是黑的？**
先确认文件能用别的播放器打开。少数特殊编码解不了，换一个 MP4 试试。
详细原因会写在插件目录的 `video-wallpaper.log` 里。

**最小化了也打开过，但没换片？**
要真的**最小化**（点窗口右上角的最小化按钮），只是切到别的窗口再切回来不算。
另外文件夹里至少要有两个视频。

**想换回图片壁纸？**
面板里点 **移除**，或者用上面的「选择壁纸图片」重新选一张图。

**和「壁纸材质」的模糊效果一起用？**
模糊只作用在图片壁纸上，视频不会变模糊（暗化效果仍然有用）。

---

## 兼容性

| Salt Player 版本 | 完整版 | Lite 版 |
|---|---|---|
| 1.19.0（Microsoft Store 版） | ✅ 实测 | ❌ 不可用（见上文 `jvm.dll` 限制） |
| 1.19.2（Steam 版） | ✅ 实测 | ⚠️ 理论可用，欢迎反馈 |

宿主每个版本都会重新混淆类名，所以插件里内置了几组候选注入点，启动时自动挑能用的那组。
以后宿主再更新、插件突然不生效了，也不会弄坏播放器 —— 只是壁纸区域不显示东西，
插件目录的 `video-wallpaper.log` 里会写清楚「所有候选都不匹配」。

### Lite 版的第三方依赖

Lite 版**不包含任何解码器**，运行时通过
[SPW Media Runtime](https://github.com/GaBoron/SPW-Media-Runtime)（MIT / Apache-2.0）提供的
libmpv 与 FFmpeg 解码。版本要求 **0.2.0 或更高**，SDK 坐标：

```
io.github.gaboron.spwmedia:spw-media-sdk:0.2.0
```

Runtime 没装、没启用，或者版本太低时，Lite 版会在壁纸页直接提示，不会影响宿主本身。

---

## 关于

MIT License。实现细节、构建方法、适配新版本宿主需要改哪里，都写在 [docs/TECH.md](docs/TECH.md)。

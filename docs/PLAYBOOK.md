# 开发与发布手册

> 给接手这个仓库的人（或 AI 助手）看的操作手册。目标是**开一个新对话，读完这一份就能继续干活**，
> 不用重新反编译、不用重新踩一遍环境坑。
>
> 最后更新：2026-10-24 · 对应版本 1.0.2

---

## 0. 这个项目是什么

Salt Player for Windows（下称 SPW）的**视频壁纸插件**：在「设置 → 外观 → 壁纸」面板里加一张卡片，
让用户选视频或文件夹当窗口壁纸。

- 仓库：https://github.com/azkbzxf/spw-video-wallpaper
- 插件 ID：`com.videowallpaper.spw`
- 权限：`class-transform`（必须，否则 hook 注册不上）
- 目标宿主：SPW **1.19.x**（Workshop API `0.1.0-dev23`），已验证 1.19.0 / 1.19.2

两个发行版，同一份代码，只有解码后端不同：

| | 完整版 | Lite 版 |
|---|---|---|
| 文件 | `spw-video-wallpaper-<ver>-ffmpeg.spmod` | `spw-video-wallpaper-<ver>-lite.spmod` |
| 体积 | ~28 MB（自带 JavaCPP FFmpeg 7.1.1） | ~929 KB（只带 SDK） |
| 依赖 | 无 | SPW Media Runtime ≥ 0.2.0 |
| 限制 | 无 | 仅 Steam / 便携版宿主；商店版会 `BACKEND_EXITED` |

---

## 1. 环境（本机踩过的坑，别重踩）

| 项目 | 结论 |
|---|---|
| Gradle | `C:\Users\24037\tools\gradle-9.8.1`，**必须跑在 JDK 21**（`org.gradle.java.home`），字节码目标是 25 |
| `GRADLE_USER_HOME` | **必须**用项目内独立目录 `$(pwd)/.gradle-home`。`~/.gradle/init.d` 里有脚本把仓库重定向到阿里云镜像，JitPack 依赖会搜不到 |
| `kotlin.stdlib.default.dependency` | 必须是 `false`，否则 stdlib 会被打进 `lib/` 与宿主冲突 |
| Kotlin 版本 | 2.4.20（组件 `jacoco` 等也需一致） |
| 宿主安装位置（商店版） | `C:\Program Files\WindowsApps\Sakawish.SaltPlayerforWindows_1.19.0.0_x64__q65q631pyh094\`，**exe 不能直接执行**，只能 `explorer.exe "shell:AppsFolder\...!App"` 激活（延迟 15~40s） |
| 宿主安装位置（Steam 版） | `D:\app\steam\steamapps\common\Salt Player for Windows\`，exe 可直接跑（但可能需要 Steam 客户端在线） |
| 插件数据目录 | `%APPDATA%\Salt Player for Windows\workshop\plugins\<目录名>\` |
| 宿主日志 | `%APPDATA%\Salt Player for Windows\logs.txt` |

---

## 2. 目录结构

```
spw-video-wallpaper/
├── plugin/
│   ├── src/main/kotlin/com/videowallpaper/        共享代码（两个版本都用）
│   │   ├── MainPlugin.kt          PF4J 入口
│   │   ├── VwLog.kt               日志 + VwConfig（properties 持久化）
│   │   ├── HostBridge.kt          反射读写宿主 AppConfig.wallpaperFilePath
│   │   ├── HostTheme.kt           明暗主题判定
│   │   ├── WindowRestoreWatcher.kt 最小化还原监听（换片触发）
│   │   ├── hook/Hooks.kt          ★ 两个注入点（候选列表）
│   │   ├── hook/HookInstaller.kt  注册失败的后台退避重试
│   │   ├── ui/VideoWallpaperLayer.kt   渲染层
│   │   ├── ui/VideoWallpaperSection.kt 设置卡片
│   │   └── video/{WallpaperStatus,VideoLibrary}.kt  共享类型 + 文件夹扫描
│   └── src/ffmpeg/kotlin/com/videowallpaper/video/  完整版后端
│       ├── VideoDecoder.kt        JavaCPP FFmpeg → BGRA
│       └── VideoEngine.kt         解码线程 + PTS 节奏
├── plugin-lite/src/main/kotlin/com/videowallpaper/video/VideoEngine.kt   Lite 后端（Media Runtime）
├── docs/{TECH.md,PLAYBOOK.md}
└── .verify/                       本地验证脚本（.gitignore，不进仓库）
```

`plugin` 的 sourceSets = `src/main/kotlin` + `src/ffmpeg/kotlin`；
`plugin-lite` 的 sourceSets = `../plugin/src/main/kotlin` + `src/main/kotlin`。
两边提供同名 `VideoEngine`，对外 API 一致，所以共享代码一行都不用改。

---

## 3. 构建

```bash
cd D:/输出目录/workbuddy/spw-video-wallpaper
GRADLE_USER_HOME=$(pwd)/.gradle-home \
  "C:/Users/24037/tools/gradle-9.8.1/bin/gradle.bat" --no-daemon \
  :plugin:plugin :plugin-lite:plugin -q
```

产物：

- `plugin/build/libs/plugin-com.videowallpaper.spw-<ver>.spmod`
- `plugin-lite/build/libs/plugin-com.videowallpaper.spw-<ver>-lite.spmod`
  （重命名在 `plugin-lite/build.gradle.kts` 的 `tasks.named("plugin").doLast` 里，**改版本号时这两处都要改**）

> 注意：`plugin/build/libs/` 里会堆积旧版本的 spmod，发布前记得只挑当前版本的那个。

---

## 4. 部署到本机（不依赖 .verify 脚本）

`.verify/` 不入库，新环境按下面手动做。**部署前必须先关宿主**（jar 被占用会删不掉）。

```python
# 解包 spmod 到插件目录并授权 + 启用
import json, os, shutil, zipfile
spmod = r"plugin/build/libs/plugin-com.videowallpaper.spw-1.0.2.spmod"
plugins = os.path.expandvars(r"%APPDATA%\Salt Player for Windows\workshop\plugins")
dirname = "plugin-com.videowallpaper.spw-1.0.2"

# 1) 清掉其它版本的视频壁纸目录（同 ID 两份会打架）
for d in os.listdir(plugins):
    if d.startswith("plugin-com.videowallpaper.spw-"):
        shutil.rmtree(os.path.join(plugins, d), ignore_errors=True)

# 2) 解包
target = os.path.join(plugins, dirname)
z = zipfile.ZipFile(spmod)
for n in z.namelist():
    if n.endswith("/"): continue
    dst = os.path.join(target, n.replace("/", os.sep))
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    open(dst, "wb").write(z.read(n))

# 3) 启用
p = os.path.join(plugins, "enabled.txt")
ids = [l.strip() for l in open(p, encoding="utf-8") if l.strip()] if os.path.isfile(p) else []
if "com.videowallpaper.spw" not in ids:
    ids.append("com.videowallpaper.spw")
    open(p, "w", encoding="utf-8").write("\n".join(ids) + "\n")

# 4) 授权 class-transform
p = os.path.join(plugins, "permissions.json")
data = json.load(open(p, encoding="utf-8")) if os.path.isfile(p) else {}
data["com.videowallpaper.spw"] = {"reviewed": ["class-transform"], "granted": ["class-transform"]}
json.dump(data, open(p, "w", encoding="utf-8"), ensure_ascii=False)
```

部署完要**重启宿主**才生效。

---

## 5. 验证：读日志，别截图

**不要自己启动宿主截图验证。** 商店版根本起不来（exe 受 WindowsApps 限制），
Steam 版又要 Steam 在线，反复折腾很不划算。改成：

1. 部署、重启宿主；
2. 直接读这两个文件（不需要任何 UI 操作）：

| 文件 | 看什么 |
|---|---|
| `...\video-wallpaper.log` | `壁纸渲染层挂载成功：ύ(U+03CD)@ѐ(U+0450)（宿主 1.19.2）` 这类行 |
| `...\last-start.txt` | `wallpaperLayerMounted` / `panelMounted` / `engineState` / `engineError` |

3. 需要看画面/交互的，把操作步骤写成 3~5 步交给用户，让他点完反馈。

---

## 6. 宿主升级后怎么适配（核心流程）

**每次宿主发版，R8 都会重新混淆，类名会变。** 已知变化：

| | 1.19.0 | 1.19.2 |
|---|---|---|
| `Wallpaper` 类 | `androidx.compose.ui.ϰ` U+03F0 | `androidx.compose.ui.ύ` U+03CD |
| 调用点所在类 | `androidx.compose.ui.Պ` U+054A | `androidx.compose.ui.ѐ` U+0450 |
| 方法名 | `Ϳ` U+037F | 同 |
| 壁纸面板 `ރ` U+0783 / 说明文字 `ࠤ` U+0824 | — | **没变** |

### 6.1 找新类名（脚本可直接跑）

宿主的 `app/ffmpeg-x64.dll` 其实是个 polyglot jar，`python zipfile` 能直接读：

```python
import zipfile
JAR = r"D:\app\steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
z = zipfile.ZipFile(JAR)

# ① 找源码文件名对应的 class（R8 保留了 SourceFile 属性）
for n in z.namelist():
    if not n.endswith(".class"): continue
    d = z.read(n)
    for tag in (b"Wallpaper.desktop.kt", b"WallpaperFilePanel.desktop.kt"):
        if tag in d:
            print(tag.decode(), "->", n)
```

```python
# ② 找「对 Wallpaper(modifier) 只有一处调用」的类
cls  = "androidx/compose/ui/ύ".encode()                      # ← 换成上一步找到的类名
desc = b"(Landroidx/compose/ui/Modifier;Landroidx/compose/runtime/Composer;II)V"
for n in z.namelist():
    if not n.endswith(".class"): continue
    d = z.read(n)
    if cls in d and desc in d:
        print("caller:", n)
```

正常会打印两个：目标类自己（自引用）和真正的调用点。

### 6.2 确认调用点唯一 + 结构没变

```bash
# 提取 class 后反汇编（javap 必须带 stdout.encoding，否则混淆字符会变成 ?）
"C:/Program Files/Java/jdk-21.0.10/bin/javap.exe" -J-Dstdout.encoding=UTF-8 -p -c 提取的class > out.txt
grep -c "androidx/compose/ui/ύ" out.txt     # 应该是 1
grep -n "AnimatedVisibility\|getWallpaperFilePath" out.txt | head
```

结构应该是：`AnimatedVisibility(visible = wallpaperFilePath.isNotEmpty(), …)` 的 content lambda 里
调用 `Wallpaper(Modifier)`，且该调用在整类里只有一处。

### 6.3 把新名字加进候选列表

编辑 `plugin/src/main/kotlin/com/videowallpaper/hook/Hooks.kt`，往 `WALLPAPER_POINTS` **最前面**插一条：

```kotlin
UiPoint(
    "androidx.compose.ui.\u03CD",      // 目标 composable 类
    "androidx.compose.ui.\u0450",      // 调用点所在类
    listOf("androidx.compose.ui.Modifier"),
    "1.19.2",                          // 日志里显示用的标签
)
```

**用 `\uXXXX` 转义写码点**，不要直接贴混淆字符（肉眼分不清 U+03CD 和 U+03C0 之类）。
旧的条目保留，这样一次覆盖多个宿主版本。

### 6.4 验证

重新构建、部署、重启宿主，然后看日志里命中的是哪一组：

```
壁纸渲染层挂载成功：ύ(U+03CD)@ѐ(U+0450)（宿主 1.19.2）
```

如果出现 `所有候选都不匹配`，说明名字又变了，回到 6.1。

> **踩过的坑**：方法名 `Ϳ` 是 **U+037F**（希腊问号），不是 U+047F。
> 写错时报的是 `UI call missing or ambiguous … (0 calls)` —— 候选列表为空，不是"签名不符"。
> 看到 `(0 calls)` 先用 `ord()` 核对名字码点，别急着怀疑 `inClass`。

---

## 7. 发布到 GitHub

```bash
# 1) 改版本号：plugin/build.gradle.kts、plugin-lite/build.gradle.kts（含 doLast 里的两个字符串）
# 2) 构建（见 §3）
# 3) 改名成发布用的对称命名
cp plugin/build/libs/plugin-com.videowallpaper.spw-1.0.2.spmod      dist/spw-video-wallpaper-1.0.2-ffmpeg.spmod
cp plugin-lite/build/libs/plugin-com.videowallpaper.spw-1.0.2-lite.spmod dist/spw-video-wallpaper-1.0.2-lite.spmod
# 4) 发版
gh release create v1.0.2 dist/spw-video-wallpaper-1.0.2-ffmpeg.spmod dist/spw-video-wallpaper-1.0.2-lite.spmod \
  --title "视频壁纸 1.0.2" --notes-file 发行说明.md
```

约定：

- **tag 与插件内部版本号一致**（`v1.0.2` ↔ `PluginVersion = "1.0.2"`）。
- 附件名固定 `spw-video-wallpaper-<ver>-ffmpeg.spmod` / `-lite.spmod`，两个版本成对发布。
- 撤回某版：`gh release delete vX.Y.Z --yes --cleanup-tag`；
  代码用 `git revert`（保留历史），**不要 force push**。
- 提交信息用 `git commit -F <仓库外写的文件>`，别用 `git add -A` 配临时文件（会一起提交进去，踩过）。

---

## 8. 故障速查

| 现象 | 原因 | 处理 |
|---|---|---|
| 壁纸页没有「视频壁纸」卡片 | 没给 `class-transform`，或注入点不匹配 | 先看 `last-start.txt` 的 `panelMounted`；再看日志有没有 `(0 calls)` |
| `UI call missing or ambiguous … (0 calls)` | 类名/码点写错，或宿主改版 | 按 §6 重新找名字 |
| `engineState=Error` + `BACKEND_EXITED`（Lite） | Runtime 播放后端起不来 | 商店版宿主无解；Steam 版检查 Runtime 是否装了并启用 |
| `未检测到 SPW Media Runtime`（Lite） | 没装/没启用 Runtime | 装 0.2.0+ 并启用，重启 |
| 选了视频黑屏 | 编码不支持 / 文件损坏 | 看日志 `片段播放失败`；换 MP4 试 |
| 最小化还原没换片 | 只是切窗口，没真最小化；或池里只有一个视频 | 说明清楚，这是设计行为 |
| 启动弹「已就绪」提示 | `welcomed` 没写进去 | 检查 properties 里 `welcomed=true` |
| 插件目录出现两份 `plugin-com.videowallpaper.spw-*` | 装了不同版本 | 删掉旧的，同 ID 两份会打架 |

---

## 9. 一个完整改动示例（1.0.2 的弹窗修复）

**需求**：每次启动都弹「视频壁纸已就绪」，改成只在首次安装时弹。

1. `VwConfig` 加字段 + 持久化：
   ```kotlin
   @Volatile var welcomed: Boolean = false; private set
   // init():  welcomed = props.getProperty("welcomed","false").toBoolean() || videoPath.isNotBlank() || folderPath.isNotBlank()
   // persist(): props.setProperty("welcomed", welcomed.toString())
   fun markWelcomed() { if (welcomed) return; welcomed = true; persist() }
   ```
   最后那半句 `|| videoPath.isNotBlank() || folderPath.isNotBlank()` 是给老用户升级用的：
   配置里已经有东西，说明不是首次安装，别补弹。

2. `MainPlugin.start()` 分支：
   ```kotlin
   if (wallpaperOk && panelOk) {
       if (!VwConfig.welcomed) {
           WorkshopApi.ui.toast("视频壁纸已就绪：外观 → 壁纸 → 视频壁纸", WorkshopApi.Ui.ToastType.Success)
           VwConfig.markWelcomed()
       }
   } else {
       WorkshopApi.ui.toast("视频壁纸：注入未完成…", WorkshopApi.Ui.ToastType.Warning)   // 失败每次都弹
   }
   ```

3. 升版本号 → 构建 → 部署 → 让用户重启一次确认。

要点：**异常提示不要静音**，只静音"一切正常"的那种。

---

## 10. 版本记录

| 版本 | 内容 |
|---|---|
| 1.0.0 | 首发：视频壁纸、文件夹池、最小化还原换片 |
| 1.0.1 | 注入点改成候选列表，兼容 1.19.2；补 Lite 版 |
| 1.0.2 | 「已就绪」提示只在首次安装时弹 |
| ~~1.1.0~~ | 曾发布后撤回（Lite 依赖的 Runtime 在商店版宿主上无法加载 `jvm.dll`） |

---

## 11. 协作约定

- 验证优先读日志（`last-start.txt` / `video-wallpaper.log` / 宿主 `logs.txt`），
  不要为看效果反复启动宿主截图。
- 需要人肉验证时，给出**编号的 3~5 步操作**，让对方点完反馈，而不是"你试试看"。
- 涉及宿主的改动（Hook、反射读内部字段）都属于**未公开 API**，出问题必须静默降级，
  绝不能影响播放器本身的播放功能。
- 仓库是公开的：提交信息、日志、注释里不要放本机路径以外的隐私内容。

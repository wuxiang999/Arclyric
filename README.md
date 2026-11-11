# 弧光歌词 · ARCLYRIC

把歌词戴在腕上。小米手环（Vela OS）音乐控制与同步歌词快应用。

## 功能

- 手机端播放状态同步：歌名、歌手、专辑、进度、播放/暂停。
- 同步歌词：播放器内显示当前句与下一句，全屏页 5 行歌词窗、当前句高亮、点句跳转、偏移微调。
- **在线歌词自动匹配**：本地词库没有的歌，手机端自动去 QQ 音乐（中文曲优先）和 LRCLIB 搜索带时间轴的 LRC，命中后缓存到本地，用户不需要手动导入。
- **播放器页**：左上角小字歌名/歌手（颜色区分）、常驻「加入歌词本」图标、音量增减、进度条整条可拖动、暂停/上一首/下一首。
- 歌词本：手机推送的歌词会存入手环本地，窗口式上下滑动浏览、跟随播放自动滚动、点句跳转。
- 演示模式：没有手机伴侣应用时也能完整浏览交互。
- 连接状态、诊断、重连；断开或退出后恢复上次的歌曲与进度（暂停态，可继续播放）。
- 大字号排版：正文最小 10dp、标题 15dp，全部加粗，按 336×480 方屏（逻辑高 274dp）排版。
- 深色 OLED、青绿主色、dp 尺寸、安全区适配。

## 目录

```
src/
  manifest.json          应用配置与路由
  app.ux                 应用入口，只加载轻量日志模块
  common/
    images/icon128.png   应用图标 128×128
    scripts/             协议、LRC、传输、状态、词库、存储
  pages/
    boot/                主页（播放器 / 歌词本 / 设置）
    player/              播放器
    lyrics/              全屏同步歌词
    book/                歌词本
    reader/              歌词本阅读器
    settings/            设置
android/                 手机伴侣工程（XMS Wearable / MediaSession）
sign/                    RPK 签名（debug / release，与 APK 同证书）
dist/                    最终产物：APK / RPK / 校验和
tools/                   图标与品牌图生成脚本、RPK 构建脚本（build-rpk.sh）
assets/                  512/96 图标与品牌预览
docs/DESIGN.md           设计系统、最佳实践与来源
docs/HAPTIC-AUDIO.md     马达发声（振动外放）调研备忘：社区方案、环间音乐技术拆解、待确认问题
```

## 运行

### 手环端

**PC / Mac（AIoT-IDE）**

1. 用 AIoT-IDE 打开本项目根目录，执行 `npm install`。
2. `npm start` 启动模拟器；`npm run build` 构建；`npm run release` 出签名 RPK。
3. RPK 通过小米运动健康 / 小米穿戴的第三方应用安装入口安装。

**Termux / aarch64 Android（无 AIoT-IDE）**

```bash
bash tools/build-rpk.sh debug     # 调试签名，输出 dist/Arclyric-1.0.2-debug.rpk
bash tools/build-rpk.sh release   # 正式签名，输出 dist/Arclyric-1.0.2-release.rpk
```

脚本首次运行会把 `aiot-toolkit@1.2.1-alpha.0` 装到 `.tmp/toolchain/`（约 8 分钟，之后复用），
并自动处理三件事：`--no-bin-links`（Android 共享存储不支持符号链接）、
`PUPPETEER_SKIP_DOWNLOAD=1`（无 aarch64 Chromium 包）、
构建后还原被编译器清空的 `dist/` 其它产物并重算 `SHA256SUMS.txt`。

为什么不用官方 2.x：`aiot-toolkit@2.x` 依赖 `@rspack/binding-android-arm64`，该原生包未发布，
在 aarch64 上必然加载失败；`1.2.1-alpha.0` 是最后一条纯 JS（webpack 5）工具链，
产物格式、签名算法与 2.x 完全一致（同为 `RPK Sig Block 42`）。

### 手机端

```bash
bash android/build.sh
```

脚本会自动把 APK 复制到 `dist/` 并生成校验和。Termux 内会自动用 `$(which aapt2)` 覆盖 SDK 自带的 x86-64 版本。

Android Studio 中直接 Sync + Build 即可。签名使用 `android/arclyric.keystore`，密码与 alias 均为 `arclyric`，与 `sign/` 中的 RPK 证书一致。

## 构建产物

所有最终产物必须存放在本项目目录内（推荐 `dist/`），禁止写到项目目录之外。

- `dist/Arclyric-1.0.2-debug.apk` / `dist/Arclyric-1.0.2-release.apk`
- `dist/Arclyric-1.0.2-debug.rpk` / `dist/Arclyric-1.0.2-release.rpk`（已签名）
- `dist/SHA256SUMS.txt`
- 构建中间产物：`android/app/build/`、`.tmp/`

安装顺序：先装 APK 并授予通知使用权（App 打开即自动开始同步），再把 RPK 装到手环。

证书 SHA-256：`7F:67:2B:7C:B5:03:5A:19:0E:7C:70:9D:C2:05:73:49:87:AF:1A:3B:08:77:0C:6D:16:80:51:CE:4C:B8:56:6D`

### 构建注意（踩过的坑）

- **不要创建 `src/config-watch.json`**。它会让编译器额外产出 `manifest-watch.json`，而该文件缺少 `packageInfo` / `minAPILevel`，实测在本机会导致「桌面显示白色方块 + 点击闪退」。删掉它（构建时那条 WARN 可以忽略）问题消失。
- `manifest.json` 里 **不要手写 `minAPILevel`**。编译器会按工程自动注入（默认 `1`）。手写成 `3` 会让固件直接返回 `InstallFailed`，表现为 AstroBox 报 `third-party app install failed: InstallFailed`。
- `minPlatformVersion` 保持 `1000`，写 `1200` 同样会被固件拒绝安装。
- 图标使用 **128×128** PNG（`/common/images/icon128.png`）。192×192 在真机桌面上未验证通过。
- `app.ux` 只 import 轻量模块（`debug.js`），**不要**在应用启动时 import `player-state.js` 那条依赖链；播放器状态交给 `pages/player` 自己初始化。
- Vela CSS 不支持复合选择器（`.a.b`）、`@media`、`@keyframes`、`animation` 简写、4 值 `padding`/`margin` 与 `border` 简写。本工程已全部改为单类修饰符、4 个方向长写与 `border-width/style/color`；进度条内联 `style="width: {{percent}}%"` 改为 11 档 `fill-w0`…`fill-w100`。
- `aiot build` 会清空整个 `dist/`，务必用 `tools/build-rpk.sh debug|release` 出包。
- 手环接受自签名证书，debug/release 共用 `sign/` 下的同一张证书即可，APK 与 RPK 必须同证书才能通信。

## 更新日志

### 1.0.2（versionCode 3）

- 修复切歌不同步：手机每 2 秒兜底重发状态，切歌后再补发两次；手环在播放到结尾或 20 秒未收到状态时主动请求同步。
- 修复发包风暴：手环 hello 20 秒冷却 + 断开需 ≥1.5 秒才算重连，手机 hello 8 秒限流且不再重置词库请求节流，同一首歌发送中跳过重复请求。
- 播放页：新增音量控制（−/＋ 10 档）、进度条整条可拖动（松手才 seek），删除 ±10 秒。
- 播放页：歌名 / 歌手移到左上角小字并区分颜色，歌名右侧常驻「加入歌词本」图标。
- 歌名 / 歌手支持用歌词文件名（`歌名 - 歌手.lrc`）兜底。
- 歌词本：改为窗口式上下滑动（列表 6 行 / 阅读 10 行），阅读页跟随播放句自动滚动，手动滑动暂停跟随。
- 歌词页：打开即自动请求，已获取过不再重复请求；8 秒后才显示未匹配与重试。
- 手机端：歌词库批量管理（多选删除 / 推送，推送排队发送）、日志可上滑翻看。
- 设置页：「播放」Tab 改 2×2 网格，歌词偏移条整条在屏内可操作。
- 连接断开 / 退出后恢复上次歌曲与进度（暂停态，可继续播放）。

### 1.0.1（versionCode 2）

- 修复暂停后进度跳到结尾、歌词本条目打不开、歌词两行被裁切。
- 新增圆形加载动画、真实分包进度、手机端词库镜像与网易云歌单导入。

## 通信

手环端使用 `@system.interconnect`，消息为 UTF-8 JSON（协议 v1）：

| type | 方向 | payload |
| --- | --- | --- |
| `hello` | 双向 | `{ client, name }` |
| `state` | 手机 → 手环 | `{ title, artist, album, duration, position, playing, source }` |
| `lyrics` | 手机 → 手环 | `{ title, artist, lrc, persist }` |
| `lyrics.chunk` | 手机 → 手环 | `{ id, total, index, text, title, artist, persist }` |
| `command` | 手环 → 手机 | `{ action, value }`，action: `toggle/play/pause/next/prev/seek/sync/lyrics` |
| `debug` | 手环 → 手机 | `{ level, tag, msg, at }`，level: `boot/info/warn/error/crash/alive` |

说明：

- 小米手环 8/9/10 不支持 `system.bluetooth.ble`，因此不走手环直连 BLE，而走「手机伴侣 App ↔ 小米穿戴 ↔ interconnect」通道。
- 手机端与快应用必须 **同包名、同签名**：`com.vela.lyricplayer`。
- 手环无扬声器，音频由手机播放，手环负责控制与歌词显示。

## 设计

设计系统、交互原则、性能约束与官方来源见 `docs/DESIGN.md`。

- 基准屏幕：小米手环 9/10 胶囊屏 192×490。
- 尺寸使用 `dp`，布局使用 `flex` / `%`，避免不同屏幕比例失真。
- 圆屏通过 `@media (shape: circle)` 增加安全边距。
- 手环 8 Pro / 9 Pro（336×480）与圆屏手表需要单独调整排版密度。

## 品牌

- 名称：弧光歌词 / ARCLYRIC
- Slogan：把歌词戴在腕上
- 主色：`#6EF3C5`，辅色：`#4FD8FF`，提示：`#FFC46B`
- 图标与品牌图可用 `python3 tools/make-icon.py`、`python3 tools/make-brand.py` 重新生成。

## 手机端能力

- 通知使用权读取 MediaSession：歌名、歌手、专辑、时长、进度、播放状态。
- XMS Wearable SDK：设备发现、权限申请、消息监听与 JSON 收发。
- 前台服务每秒同步状态；切歌时匹配本地 `.lrc` 并分包发送。
- 手环指令控制手机播放：播放/暂停、上一首/下一首、跳转、音量。
- 歌词库：手环词库镜像（查看 / 删除 / 补推）+ 手机缓存（导入 / 批量管理 / 批量推送）。
- 批量管理：歌词库支持多选删除、多选推送（服务内排队一首一首发，避免分包互踩）。
- 日志面板可上滑翻看历史（滚动时不强制拉回底部），支持一键复制/分享。
- SAF 导入 `.lrc`，支持 UTF-8 / GBK。
- 日志面板与连接诊断；支持一键复制/分享日志。
- 打开应用即自动启动同步服务，并注册 `BOOT_COMPLETED` 开机自恢复。

## 手环端运行日志回传

手环无法导出运行时日志，因此手环端内置了 `src/common/scripts/debug.js` 探针：

- `app.ux` 在**模块顶层**就调用 `debug.boot()`，脚本一被加载就发出 `BOOT` 标记。
- 启动全过程分段埋点：`store.load` → `library.seed` → `transport` → `content` → `初始化完成`。
- 每个页面的 `onInit` / `onShow` 都有标记与 `try/catch`，异常会带上 `message` 与 `stack` 前 240 字符。
- 异常同时写入 `storage` 的 `arclyric_crash`，下次启动时作为「上次异常」补发。
- 未连接时消息进队列（最多 160 条），连接建立后自动补发；另有 6 次心跳证明脚本还活着。

手机端 `PlayerService` 收到 `debug` 类型后直接写入日志面板，可用「复制日志 / 分享日志」导出。

## 已知限制

- 手机端需要小米运动健康已连接手环；首次使用需授予通知使用权。
- 手环无扬声器，音频始终由手机播放。
- 手环 8 Pro / 9 Pro（336×480）与圆屏手表需要单独调整排版密度。

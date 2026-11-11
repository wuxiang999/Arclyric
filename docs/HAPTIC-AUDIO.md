# 马达发声（振动外放）调研备忘

> 状态：**调研完成，等作者答复，代码未动。**
> 记录日期：2026-11-11 · 项目版本 1.0.4

需求：用手环的线性马达当扬声器，把音乐「放」出来。

---

## 1. 结论先行

1. **快应用 API 做不到。** 9 / 9 Pro / 10 上 `@system.vibrator` 只有 `vibrate({mode:'long'|'short'})`，没有时长、没有强度、没有 stop，也没有波形输出。
2. **社区做到了，但不是靠快应用 API。** 做法是：RPK 只做客户端，真正发声由「配套表盘」里装载的 native 服务完成，两者通过 `internal://files/` 下的文件邮箱通信。
3. **9 Pro 有现成适配。** 环间音乐的 `9p` 分支标注「手环 9 Pro / 固件 3.1.175」，原生 build `0x91750003`。硬件和系统都够用。
4. **卡点在分发，不在技术。** 原生服务与配套表盘不在开源仓库里，由作者单独控制。我们能否复用，取决于它对跨包调用的校验策略（见第 6 节）。

---

## 2. 官方 API 能力（2026-11-11 核对）

来源：<https://iot.mi.com/vela/quickapp/zh/features/system/vibrator.html>

| 接口 | 手环 9 | 手环 9 Pro | 手环 10 | 唯一支持设备 |
| --- | --- | --- | --- | --- |
| `vibrate({mode})` | ✅ | ✅ | ✅ | 全系 |
| `start({duration, interval, count})` | ❌ | ❌ | ❌ | Xiaomi Watch S5 |
| `stop(id)` | ❌ | ❌ | ❌ | Xiaomi Watch S5 |
| `getSystemDefaultMode()` | ❌ | ❌ | ❌ | Xiaomi Watch S5 |

整个手环产品线（含 8 Pro、9、9 Pro、10、Redmi Watch 4、H1）都不支持后三个接口。

**这条信息会随固件和文档变化，做决策前重新核对一次。**

---

## 3. 硬件层面

| 项 | 9 | 9 Pro |
| --- | --- | --- |
| 马达 | Z 轴线性马达 | Z 轴线性马达（与 9 同款） |
| 震动模式 | 20 种（系统级） | 20 种 |
| 启停速度 | 比转子马达快 50% | 同 |
| 噪音 | 降低 10% | 实测「明显下降，不再有明显嗡嗡声」 |

两点对发声不利：

- LRA 是窄带谐振器件，共振点约 150–235Hz，只在一个窄频段有效率。越偏离共振点，声压掉得越快。
- 9 Pro 专门做了降噪调校。**这既是抑制杂音，也是抑制发声能力**——同款马达在 9 Pro 上的外放音量，预期比标准版 9 更低。

社区按「能听出旋律」的标准来评价，不是「能当音箱」。

---

## 4. 社区实现清单

| 项目 | 作者 / 来源 | 机型 | 形态 | 许可 |
| --- | --- | --- | --- | --- |
| **环间音乐** | Leon (Tseleung)，<https://github.com/leezelyn/music-player> | 9 Pro / 10 / 10 Pro | RPK + 配套表盘 + Android 传歌 | GPL-3.0-only |
| 小标音乐（马达版） | 王珏珏，bandbbs 资源 9132 | 标准版 9（M2345B1，固件 3.1.32） | APK + RPK + FACE | MIT（自写部分） |
| 环间音乐（社区讨论帖） | bandbbs threads/28905 | 仅 10 Pro | 同上 | — |
| 蓝牙音频扩展模块 | Searchstars / AstroBox / Canopus | 仅 10 Pro，固件 3.101.036 | 连蓝牙耳机，**非马达发声** | 闭源，9.9 元 |

注意区分两条完全不同的路线：

- **马达发声**：环间音乐、小标音乐。音质有限，是整活向。
- **蓝牙耳机播放**：AstroBox + Canopus + Lyra。真音乐，但只有 10 Pro 支持，9 Pro 在求适配名单首位，开发方未承诺。

小标音乐作者自述：30 秒音频可发送、20 秒可播完、30 秒长片段随机卡死，「音质和音量有限」。

---

## 5. 环间音乐技术拆解

仓库已克隆分析（1.5 万行，三套 RPK）。以下为核实过的事实。

### 5.1 架构

```
手机 APK ──@system.interconnect (haptic.music.v1)──> RPK
                                                     │  写文件
                                                     ▼
                              internal://files/haptic_native_request.txt
                                                     │  HBR1 头
                                                     ▼
                                          配套表盘装载的 native 服务
                                                     │
                                                     ▼
                                              直接驱动 Z 轴 LRA
```

RPK 的 `manifest.json` 只声明四个能力：`system.file`、`system.app`、`system.interconnect`、`system.brightness`。

**没有 `system.audio`，也没有 `system.vibrator`。** 它一点音频硬件都没碰。

### 5.2 文件邮箱

`bridge.js` 的 op 集合就是完整指令集：

```
ping / load / stage / start / pause / resume / stop / player / status / clear
```

| 文件 | 用途 |
| --- | --- |
| `haptic_native_session.txt` | 会话与租约（`HBR1\n<session>\n<expires>\nEND\n`） |
| `haptic_native_request.txt` | 请求：session / seq / op / expires / bytes / crc32 |
| `haptic_native_heartbeat.json` | 服务心跳：`abi`、`native_revision`、`session`、`tick`、`player.build` |
| `haptic_native_result.json` | 终态回执，需 `terminal === true` |

发布方式：先写 `.tmp` 再 `file.move` 原子替换，避免读到半截文件。

回执校验：`abi === 1`、`native_revision === 1`、session / seq / op 全匹配。

### 5.3 音频规格

无文件头 **12000 Hz、单声道、signed 8-bit PCM（s8）**。

- 单曲 1–3,600,000 字节，即最长 300 秒
- `peak` 1–127，样本绝对值不得超声明 peak，禁止 -128
- 带宽 6 kHz、动态范围约 48 dB，再经 LRA 机械响应，听感是「能听出旋律的嗡嗡声」

传输：`catalog → begin → chunk → commit → saved + receipt`，每块等 ACK，整包 CRC32。

`transport` 可协商 `chunkBytes`(256–16384)、`intervalMs`(0–1000)、`payloadEncoding: base64`。

### 5.4 机型差异

| | 10 | 10 Pro | 9 Pro |
| --- | --- | --- | --- |
| RPK 版本 | 0.2.43 | 0.2.41 | 0.2.41 |
| 固件 | 3.1.28 | 3.101.043 | 3.1.175 |
| 原生 build | `0x31280019` | `0x10430012` | `0x91750003` |
| 配套表盘 | — | D16 | D16 / N1 |
| 流式 stage | — | 支持 | **不支持**，强制 `sample.streamFile` |
| 断点续传 | — | `resumeRevision: 1` | 不支持 |

9 Pro 的 `prepareSample` 里写死了：

```js
if (!sample.streamFile) throw new Error('9 Pro 原生服务不支持文件流')
```

也就是说 **9 Pro 只能播已落盘的整文件**。

### 5.5 播放进度（对做歌词同步很关键）

`player.js` 里：

```js
p.elapsedText = time(progress ? progress.sent / 12000 : 0)
p.durationText = time(progress ? progress.total / 12000 : ...)
p.progressHint = s.active ? '原生发送进度' : '已发送 / 总时长'
```

心跳的 `sent` 是**已交给驱动的样本数**，`sent / 12000` 就是秒数。UI 上标的是「原生发送进度」而不是「播放位置」——两者之间可能有缓冲差，需要向作者确认。

如果这个差值稳定，逐句歌词高亮就能直接用。

---

## 6. 关键未知点（已整理成提问清单，等作者回复）

### 一票否决项

1. D16 / N1 表盘的包名，与 `com.tse.musicplayer` 是否同包？
2. 原生服务读 `internal://files/music_<id>.bin`，靠同包共享目录还是系统级跨包权限？
3. 换一个包名的 RPK 能否驱动？除 `abi` / `native_revision` / `build` / `session` 外，是否还校验包名或签名？
4. 「表盘初始化」具体做什么，是否每次重启都必须手动打开一次？

> 若答案是「同包才认」→ 此路不通。我们的包名是 `com.vela.lyricplayer`，不可能改成 `com.tse.musicplayer`（会与应用冲突），而 Vela 的 `internal://files/` 是包私有目录。

### 能力与规格

5. 9 Pro 固件 3.1.175 之外还有哪些版本可用？OTA 后 build 号会不会变？
6. 9 Pro 实测最长稳定播放时长？30 秒卡死是 9 还是 9 Pro？
7. 12000Hz / s8 是硬性要求还是可协商？卡在哪一层？
8. 输出通路是 PWM 直驱还是 DAC/I2S？有无低通与增益控制？
9. `volumePercent` 在原生层如何落地？长时间满音量有无过热或损坏保护？
10. 心跳 `sent` 与实际发声位置有无缓冲差？tick 频率多少？

### 分发与许可

11. 表盘能否单独授权分发？我们只写同一套文件协议、不复制代码，作者是否接受？
12. `native_revision` / build 号有稳定性承诺吗？
13. 仅通过文件邮箱调用 native 服务（不引用代码），是否触发 GPL-3.0 的传染性？

---

## 7. 决策树

```
作者答复「跨包可驱动」
  └─ 写最小验证 RPK：ping → load → start，播 8 秒样本
       ├─ 通道通  → 评估音质/延迟 → 决定是否做「震动播放」入口
       └─ 通道断  → 放弃
作者答复「同包才认」 / 不回复
  └─ 只剩两条
       ├─ 自己写 native（需 Vela 原生工具链 + 表盘装载器，无公开文档）→ 不建议
       └─ 放弃手环发声，音频交回手机播放 → 推荐
```

---

## 8. 顺带确认的边界

- **手环没有扬声器**，也不支持蓝牙音频输出（9 Pro），只能靠马达机械振动发声。
- 官方没有开放蓝牙主动连接接口，10 Pro 的蓝牙耳机播放靠 Canopus 内核模块拿到系统级权限，9 Pro 未适配。
- 想真正「听得见的老歌」，只有 10 Pro + AstroBox 那条路，代价是 9.9 元 + 固件锁版本 + 操作卡顿。

---

## 9. 参考

- 官方 vibrator 文档：<https://iot.mi.com/vela/quickapp/zh/features/system/vibrator.html>
- 环间音乐源码：<https://github.com/leezelyn/music-player>
- 环间音乐讨论帖：<https://www.bandbbs.cn/threads/28905/>
- 小标音乐（马达版）：<https://www.bandbbs.cn/resources/9132/>
- 小标音乐讨论帖：<https://www.bandbbs.cn/threads/30361/>
- 手环 9 拆解（马达规格）：<https://www.52audio.com/archives/206708.html>
- 手环 9 Pro 拆解：<https://www.52audio.com/archives/218324.html>

---

## 10. 后续动作

- [ ] 等作者回复第 1–4 题（一票否决项）
- [ ] 拿到答复后按第 7 节决策树走
- [ ] 若做，先出最小验证 RPK，不碰现有 1.0.x 功能
- [ ] 无论结果，本项目 LICENSE 待定：若借鉴 GPL-3.0 代码，需同样以 GPL 发布

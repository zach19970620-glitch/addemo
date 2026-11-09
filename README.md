# AI 智能广告机 Demo（V3.1）

YUKI 语音导购交互的 Android Kiosk Demo。

## 进度

| 阶段 | 状态 |
| --- | --- |
| P0/P1 播控 + 状态机 | 已完成 |
| P2 视觉（主脸/停留/happy/离开） | 已完成 |
| P3 语音路线 A | 已完成（海报态开麦 → 火山 ASR → 本地 tag） |
| P3 语音路线 B | 已完成（Device API 意图上报 / 服务端计划） |
| 虚拟人视频 | 年龄性别打招呼 + 好/差心情片 → 海报 |

## 路线 A（本地意图）

```text
UnicoPoster / DiningPoster（micEnabled=true）
  → VoiceEngine 开麦
  → 火山双向流式 ASR（local.properties 密钥）
  → 定稿文本 → IntentTagMatcher → 播产品/切餐饮
```

- 密钥：`local.properties` 的 `volc.apiKey`（或 `volc.appKey` + `volc.accessKey`）。
- AEC（`audioprocessor.aar`）默认关闭（`features.aec_enabled=false`）。

## 路线 B（生产意图上报）

按 `doc/智慧终端生产版API对接文档.md`：

```text
activate → asr-credentials(ready) → create voice-task
  → 动态 WSS（apiKey / resourceId / websocketUrl / providerRequestId）
  → 协议 final → asr-final（仅文本，不上报音频）
  → 长轮询 interaction-result → ActionExecutor
```

白名单动作：`SHOW_IMAGE`(auto)、`PLAY_VIDEO`（广告机可自动播）、`START_ORDER`（仅日志）。

媒体解析（catalog `test-20260728001`）：`payload.assetId` → 本地 `doc/assets` 同步到的
`assets/demo/server_assets/{contentHash}.*`（或 `assets/demo/assets/`、外置 `ad-demo/server_assets|assets/`）→ 没有再走签名 URL。图+视频按 sequence 串行；视频播完回当前海报。

**不再维护本地男士/女士/儿童/老年餐**；菜品与菜单内容完全由服务端 `interactionPlan` + catalog 驱动。

未匹配时默认不回退本地菜品 tag（`report.local_fallback=false`）。

### 开启方式

1. `assets/demo/config.json`：

```json
"report": {
  "enabled": true,
  "baseUrl": "https://api.ulandusa.com",
  "serialNumber": "你的预导入序列号",
  "auto_play_video": true,
  "local_fallback": false
}
```

2. 或在 `demo/local.properties`：

```properties
device.apiBase=https://api.ulandusa.com
device.serialNumber=你的预导入序列号
```

`config.json` 的 `serialNumber` / `baseUrl` 优先；为空时用 BuildConfig。

关闭：`report.enabled=false`，仍走路线 A。

## 环境

| 项 | 要求 |
| --- | --- |
| 板子 | RK3588 arm64-v8a |
| 相机 | HK5M-H210 USB UVC |
| 麦克风 | 设备麦 / 外置麦 |
| 网络 | 路线 A：`openspeech.bytedance.com`；路线 B：另加 `api.ulandusa.com` |
| NDK | 27.0.12077973 |

## 配置密钥（路线 A）

编辑 `demo/local.properties`：

```properties
volc.apiKey=你的_X-Api-Key
volc.resourceId=volc.seedasr.sauc.duration
# 可选
# volc.wsUrl=wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async
```

修改后需重新 Sync / 编译。

## 运行

```bash
cd demo
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

授权 **相机 + 麦克风**。

## 播控规则

- **IDLE**：可被进场打断  
- **打招呼 / 心情 / 产品 / 菜品片**：必须播完再切；播片中忽略新的表情检测  
- **人离开**：非 IDLE 片等 `MediaEnded` 再回 IDLE；海报态立刻回 IDLE  

| 检测 | 播放 |
| --- | --- |
| 停留 ≥ `enter_ms` | 按年龄×性别播 `greet_young/older_*` |
| 打招呼结束 · happy | `mood_good.mp4` → UNICO |
| 打招呼结束 · sad/disgust/angry/fear | `mood_bad.mp4` → UNICO |
| 打招呼结束 · 中性/未知 | 直接 UNICO 开麦 |

虚拟人片源来自 `doc/虚拟人交互视频/`（已去掉旧的 `1.5秒` / `10秒` / `笑容检测`）：

| 源文件 | assets |
| --- | --- |
| 打招呼_年轻女性/男性、年长女性/男性 | `greet_young/older_*.mp4` |
| 检测到心情好 / 心情差 | `mood_good.mp4` / `mood_bad.mp4` |

## 演示

1. 站定约 5s → 播对应打招呼片（年长/年轻 × 男/女）  
2. 表情 happy → 心情好片；负面表情 → 心情差片；中性 → 跳过  
3. 进 UNICO 海报开麦  
4. 说「麻婆豆腐」/「红烧牛肉面」/「有什么吃的」等 → 服务端匹配 → 播放 catalog 对应图/视频（来自 `doc/assets`）  
5. 走开 → Idle

无麦时仍可用底部 Debug 按钮（「心情好/差」可强制分支）。

## 服务端媒体包

本地缓存目录：`doc/assets/`（contentHash 文件名），打包进 APK 的 `assets/demo/server_assets/`（及 `assets/demo/assets/` 别名）。  
清单：`doc/smart-terminal-assets-test-20260728001-r11.json` → `assets/demo/server_assets_catalog.json`。

## 目录要点

```text
com.haokai.addemo/
├── vision/     VisionEngine
├── voice/      VoiceEngine + IntentTagMatcher
├── device/     DeviceApiClient / VoiceIntentService / ActionExecutor
├── speech/     VolcAsrClient / AudioPcmCapture / AecAudioProcessor
├── session/    SessionStateMachine
└── media/      MediaDirector
app/libs/audioprocessor.aar   # 可选 AEC
```

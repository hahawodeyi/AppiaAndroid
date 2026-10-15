# AppiaAndroid

appiaMobile（React Native）的原生 Android 重写。当前版本 **1.1.0**（M10 里程碑）。

## M10 状态与自测指引

- **M10 范围**：语音通话——VoiceSessionController 会话状态机（VoicePhase 十相位/时序竞态五件套守卫）、oncall 网关九端点（独立 OkHttp + 40002 token 失效重试）、声网 RTC 引擎（入频道/静音/扬声器/音量指示/500ms 音频路由延迟）、来电呈现三面（应用内横幅/Telecom 自管来电/锁屏系统界面——主动呼出不上报 Telecom）、群聊语音（选人页/宫格/加入横幅/发起者邀请重入）、第二路来电三型弹窗（同 org/join/跨 org）、跨组织来电（VoiceCrossOrgNotifier/切主体接听）、最小化气泡（应用内拖拽 + FGS 通知后台保活）、30s/65s 超时计时（recordId 锚定）、进程死亡恢复、登出/切组织汇入、互斥（会议↔语音）、房间入口（附件菜单语音项/MemberProfile 通话按钮）、oncall 消息卡片。
- **测试状态**：语音域新增 377 单测（354 语音目录 + 23 目录外 import 语音），全量 2754 单测 + lint 全绿；门禁×3（含 --rerun-tasks 强制全量，RealtimeSessionManagerTest 既有负载 flake 延续——隔离跑 100% 绿）；Maestro 启动/登录链冒烟绿（1.1.0）；模拟器实启零崩溃。
- **自测指引**：真实凭证验收请按 [`docs/superpowers/plans/2026-09-11-M1-user-acceptance.md`](docs/superpowers/plans/2026-09-11-M1-user-acceptance.md) 执行——M1 第二~五节、M2 第六~七节、M3 第八~九节、M4 第十~十一节、M5 第十二~十三节、M6 第十四~十五节、M7 第十六~十七节、M8 第十八~十九节、M9 第二十~二十一节、**M10 第二十二~二十三节（单聊/群聊/被叫 Telecom/第二路/跨组织/最小化/超时；外部依赖前置：两个可互通测试账号/shengwang 网关可达/Telecom phone account 授权一次/Agora AppId 沿用硬编码）**。

```bash
./gradlew :app:assembleDebug          # 构建 debug APK（cn.appia.im.debug）
./gradlew :app:assembleRelease        # release APK
./gradlew :app:testDebugUnitTest :app:lintDebug   # 全量门禁
maestro test maestro/launch_android.yaml           # 启动冒烟（需模拟器/真机）
maestro test maestro/login_reach_login.yaml        # 企业码失败链路冒烟
```

**安装说明（M8 起）**：APK 含 Agora 原生 SDK，`abiFilters` 已裁定 **arm64-v8a 单 ABI**（终审 I-1）——实测 93.1MB（universal 4-ABI 为 257.6MB）。真机与 Apple Silicon 模拟器直接安装；x86_64/Intel 模拟器报 `INSTALL_FAILED_NO_MATCHING_ABIS` 时，移除 `app/build.gradle.kts` defaultConfig 内 `ndk { abiFilters }` 行重建（splits 归 M11）。详见自测协议 §19-14。

M10 已知差异（录音/摘要缓 backlog/系统级浮窗缓/主动呼出不上报 Telecom/busy 无信令/onUserOffline no-op/500ms 音频延迟待真机裁定等 12 项）见自测协议第二十三节；M1-M9 遗留差异见第四/七/九/十一/十三/十五/十七/十九/二十一节。杂项 backlog：`docs/backlog-m6.md`、`docs/backlog-m8.md`、`docs/backlog-m10.md`。


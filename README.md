# AppiaAndroid

appiaMobile（React Native）的原生 Android 重写。当前版本 **1.0.0**（M9 里程碑）。

## M9 状态与自测指引

- **M9 范围**：邮件——mail client 基座（独立 OkHttp + MailCookieJar 会话级 cookie/RSA-OAEP 凭证加密/X-Auth-Token+X-User-Id 双头）、账户绑定（mail/api/accounts 全链 + 姓名内部优先解析）、收件箱（分页拉取/下拉刷新/未读态）、邮件详情（归一化 id 透传对齐 RN 剥前缀时机）、撰写（5 模式：新邮件/全部回复/包含本人回复/转发/修改后重发）、推送角标（坑 7 INBOX 归一化/mail.updated 被动解析增量未读/坑 8 未读启发式 100 条天花板/预览 INBOX 最新一条/多组织分桶 mailPreviewByOrg）、会话流驱动刷新（RN useMailInit 移植：登录/切组织/登出同链）、角标行接线（StateFlow 注入避免 VM 重建）。
- **测试状态**：2374 单测 + lint 全绿；门禁×3（含 --rerun-tasks 强制全量，RealtimeSessionManagerTest/MeetingRoomScreenSmokeTest 既有负载 flake 延续——隔离跑均 100% 绿）；Maestro 启动/登录链冒烟绿；模拟器实启零崩溃。
- **自测指引**：真实凭证验收请按 [`docs/superpowers/plans/2026-09-11-M1-user-acceptance.md`](docs/superpowers/plans/2026-09-11-M1-user-acceptance.md) 执行——M1 第二~五节、M2 第六~七节、M3 第八~九节、M4 第十~十一节、M5 第十二~十三节、M6 第十四~十五节、M7 第十六~十七节、M8 第十八~十九节、**M9 第二十~二十一节（绑定/收件箱/详情/撰写/推送角标；外部依赖前置：mail 网关可用/真实邮箱一对/RN 对照）**。

```bash
./gradlew :app:assembleDebug          # 构建 debug APK（cn.appia.im.debug）
./gradlew :app:assembleRelease        # release APK
./gradlew :app:testDebugUnitTest :app:lintDebug   # 全量门禁
maestro test maestro/launch_android.yaml           # 启动冒烟（需模拟器/真机）
maestro test maestro/login_reach_login.yaml        # 企业码失败链路冒烟
```

**安装说明（M8 起）**：APK 含 Agora 原生 SDK，`abiFilters` 已裁定 **arm64-v8a 单 ABI**（终审 I-1）——实测 93.1MB（universal 4-ABI 为 257.6MB）。真机与 Apple Silicon 模拟器直接安装；x86_64/Intel 模拟器报 `INSTALL_FAILED_NO_MATCHING_ABIS` 时，移除 `app/build.gradle.kts` defaultConfig 内 `ndk { abiFilters }` 行重建（splits 归 M11）。详见自测协议 §19-14。

M9 已知差异（无账户管理页 RN 同/HTML 无 sanitize-禁 JS 等价/未读 100 天花板/附件 JSON 内联内存/真收发依赖用户域验收等 10 项）见自测协议第二十一节；M1-M8 遗留差异见第四/七/九/十一/十三/十五/十七/十九节。杂项 backlog：`docs/backlog-m6.md`。

# AppiaAndroid

appiaMobile（React Native）的原生 Android 重写。当前版本 **0.8.0**（M7 里程碑）。

## M7 状态与自测指引

- **M7 范围**：待办+工作台+AI——待办域（REST 双屏共用卡片/抽屉卡/房间头入口/红点/长按设完成改提醒/附件跳转；双计数源 RN 同构）、工作台（worktable_config 裸 fetch 宫格/搜索/三支路由/考勤定位先询）、InAppWeb 拦截族七项补齐（泛微三件套防循环 reload/石墨拼参/WPS 入口改写/返回决策链全分支/postMessage 桥/会议外链拦截/needVPN 探活）、AI Agent（OkHttp 手写 POST SSE——FINISH 停读/串行驱动槽位机制/双兜底防闪烁/停止持久化/bot.saveAIMessage 回流/ai_response 复用 M3 markdown 链/myAgents 虚拟行/@bot 触发/AgentEditor 管理动作四 POST——M4 挂账收口）。
- **测试状态**：1996 单测 + lint 全绿；门禁×3（含 --rerun-tasks 强制全量）；Maestro 启动/登录链冒烟绿；模拟器实启零崩溃。
- **自测指引**：真实凭证验收请按 [`docs/superpowers/plans/2026-09-11-M1-user-acceptance.md`](docs/superpowers/plans/2026-09-11-M1-user-acceptance.md) 执行——M1 第二~五节、M2 第六~七节、M3 第八~九节、M4 第十~十一节、M5 第十二~十三节、M6 第十四~十五节、**M7 第十六~十七节（待办/工作台/InAppWeb 拦截族/AI 流式；外部依赖前置：服务端 bot 可用/worktable_config 有数据/泛微等内网环境）**。

```bash
./gradlew :app:assembleDebug          # 构建 debug APK（cn.appia.im.debug）
./gradlew :app:assembleRelease        # release APK
./gradlew :app:testDebugUnitTest :app:lintDebug   # 全量门禁
maestro test maestro/launch_android.yaml           # 启动冒烟（需模拟器/真机）
maestro test maestro/login_reach_login.yaml        # 企业码失败链路冒烟
```

M7 已知差异（staffService 划 M10/FastModel 桩/双计数源 RN 同构/抽屉卡简化/urlType 未入等 12 项）见自测协议第十七节；M1-M6 遗留差异见第四/七/九/十一/十三/十五节。杂项 backlog：`docs/backlog-m6.md`。

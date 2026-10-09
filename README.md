# AppiaAndroid

appiaMobile（React Native）的原生 Android 重写。当前版本 **0.7.0**（M6 里程碑）。

## M6 状态与自测指引

- **M6 范围**：推送与自更新——阿里云推送 SDK 基建（双初始化 10000/PUSH_20110 均成功/channel_01 IMPORTANCE_HIGH 预建）、token 注册接线（CloudPushService.getDeviceId 换源）、POST_NOTIFICATIONS 运行时申请（故意分歧）+ 电池优化引导、payload 四层兜底解析（双重转义/厂商退化形态/oncall 桩）+ host 校验、深链进房三 drain 点（90s TTL 队列/热重建取关/通知清理）、自更新检查（非 RC 端点/semver coerce 补零对齐 RN）、自更新弹窗（强制返回拦截/会话内静默/登出复位）+ 应用内 APK 安装（FileProvider/缓存命中）、versionCode 下限守卫（> 28187853）。
- **测试状态**：1731 单测 + lint 全绿；门禁×3（含 --rerun-tasks 强制全量）；Maestro 启动/登录链冒烟绿；模拟器实启验证（推送 init 失败不影响 App 可用 + meta-data 整型 appkey CCE 实测抓出并修复）。
- **自测指引**：真实凭证验收请按 [`docs/superpowers/plans/2026-09-11-M1-user-acceptance.md`](docs/superpowers/plans/2026-09-11-M1-user-acceptance.md) 执行——M1 见第二~五节、M2 第六~七节、M3 第八~九节、M4 第十~十一节、M5 第十二~十三节、**M6 第十四~十五节（推送点击进房/token 注册/通知权限/电池引导/自更新弹窗与应用内安装；外部依赖前置：EMAS 控制台/厂商真机/GMS 设备/可配版本服务端）**。

```bash
./gradlew :app:assembleDebug          # 构建 debug APK（cn.appia.im.debug）
./gradlew :app:assembleRelease        # release APK（google-services 仅 release 应用；versionCode 下限守卫）
./gradlew :app:testDebugUnitTest :app:lintDebug   # 全量门禁
maestro test maestro/launch_android.yaml           # 启动冒烟（需模拟器/真机）
maestro test maestro/login_reach_login.yaml        # 企业码失败链路冒烟
```

M6 已知差异（POST_NOTIFICATIONS 申请为故意分歧/FCM debug 缺失/语音 oncall 桩 M10/appia:// 死配置不移植/电池引导收窄等 12 项）见自测协议第十五节；M1-M5 遗留差异见第四/七/九/十一/十三节。M5 遗留杂项 backlog 见 `docs/backlog-m6.md`。

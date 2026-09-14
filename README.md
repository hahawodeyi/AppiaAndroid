# AppiaAndroid

appiaMobile（React Native）的原生 Android 重写。当前版本 **0.2.0**（M1 里程碑）。

## M1 状态与自测指引

- **M1 范围**：登录/多组织链路——企业码验证 → 服务器列表 → SMS 登录（滑块/验证码）→ 会话持久化 → 占位主屏 → 组织切换 → 登出/会话失效恢复。主屏房间列表 UI 属 M2。
- **测试状态**：324 单测 + lint 全绿；Maestro 冒烟（`maestro/`）与模拟器走查通过。
- **自测指引**：真实凭证验收请按 [`docs/superpowers/plans/2026-09-11-M1-user-acceptance.md`](docs/superpowers/plans/2026-09-11-M1-user-acceptance.md) 执行（安装步骤、逐项自测清单、与 RN 版对照点、问题反馈格式、已知差异清单）。

```bash
./gradlew :app:assembleDebug          # 构建 debug APK（cn.appia.im.debug）
./gradlew :app:testDebugUnitTest :app:lintDebug   # 全量门禁
maestro test maestro/launch_android.yaml           # 启动冒烟（需模拟器/真机）
maestro test maestro/login_reach_login.yaml        # 企业码失败链路冒烟
```

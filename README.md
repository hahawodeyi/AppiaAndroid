# AppiaAndroid

appiaMobile（React Native）的原生 Android 重写。当前版本 **1.2.0**（M11 收尾里程碑——重写工程闭合）。

## M11 状态与验收指引

- **M11 范围**：收尾验收——全量功能 checklist 十域盘点（334 行 = ✅249/❌10/🔶75，`docs/m11-feature-checklist.md`）；RN 冻结基线后 69 提交 delta 盘点（`docs/m11-rn-delta.md`）；main-chain 缺口修复 3 项（G9 合并转发 appiaBaseUrl 透传 ebc1171 / G10 手动检查更新接线 fcf7ab9 / G13 首启隐私同意门禁 f1e6e4e）；发布工程（RN 全套图标接线/release 构建验证/versionCode 下限守卫 28187853/abiFilters arm64 单 ABI 终态）；i18n 中英双语核对（1143/1143 键对称 100%）；性能走查（冷启动中位 ~3.6s 模拟器）。
- **测试状态**：门禁×3（`--rerun-tasks` 强制全量，最终计数见下）；Maestro 启动/登录链冒烟双绿（1.2.0 断言，含首启隐私同意 optional 步骤）；模拟器冷启动×2 零 FATAL；桌面蓝渐变 adaptive 图标确认。
- **验收指引**：真实凭证验收请按 [`docs/superpowers/plans/2026-09-11-M1-user-acceptance.md`](docs/superpowers/plans/2026-09-11-M1-user-acceptance.md) 执行——M1 第二~五节 … M10 第二十二~二十三节；**M11 收尾验收见第二十四节（全量盘点/缺口处置/外部依赖前置汇总/性能实测/i18n/release 验证）与第二十五节（发布状态：版本/签名/abiFilters 终态/已知差异总索引）**；逐行功能证据 `docs/m11-feature-checklist.md`；记档不修事项 `docs/backlog-m11.md`；真签名发布步骤见协议二十四节第 6 项（release.properties 四键 → assembleRelease）。

```bash
./gradlew :app:assembleDebug          # 构建 debug APK（cn.appia.im.debug）
./gradlew :app:assembleRelease        # release APK（无 keystore 时 debug 签名）
./gradlew :app:testDebugUnitTest :app:lintDebug   # 全量门禁
maestro test maestro/launch_android.yaml           # 启动冒烟（需模拟器/真机）
maestro test maestro/login_reach_login.yaml        # 企业码失败链路冒烟
```

**安装说明（M8 起，M11 定稿）**：APK 含 Agora 原生 SDK，`abiFilters` 已裁定 **arm64-v8a 单 ABI**（现状即终态）——实测 93.1MB（universal 4-ABI 为 257.6MB）。真机与 Apple Silicon 模拟器直接安装；x86_64/Intel 模拟器报 `INSTALL_FAILED_NO_MATCHING_ABIS` 时，临时移除 `app/build.gradle.kts` defaultConfig 内 `ndk { abiFilters }` 行重建（不做 AAB/splits）。详见自测协议 §25-3。

M11 已知差异总索引（M1-M10 十节差异数与代表项）见自测协议第二十五节；记档不修事项 `docs/backlog-m11.md`（+ 域内 backlog：`docs/backlog-m6.md`、`docs/backlog-m8.md`、`docs/backlog-m10.md`）。首次启动有隐私同意屏（对齐 RN 生产版）——Maestro 流已加 optional 同意步骤。


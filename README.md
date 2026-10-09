# AppiaAndroid

appiaMobile（React Native）的原生 Android 重写。当前版本 **0.6.0**（M5 里程碑）。

## M5 状态与自测指引

- **M5 范围**：搜索与设置——settings.public 同步（122 键注册表解锁全部门控）、全局角色运行时刷新、presence 在线状态全链（绿点/文字状态/bot 剔除）、useRealName 接线、全局搜索（4 tab/spotlight 三段/文件分页/高亮）、消息跳转高亮（跨房导航+chunk 占位+15s 超时）、房间内搜索（群 6/单聊 4 tab/加密房本地 LIKE）、tSearch bump 收口、设置页族（五分区/通知三键/状态编辑/只读 ProfileScreen+MyCard 迁正/清除缓存）、InAppWeb 壳（needAuth 白名单/凭证注入/Meteor localStorage）、i18n 运行时语言切换（per-app locale 三态）。
- **测试状态**：1594 单测 + lint 全绿；Maestro 冒烟（`maestro/`）通过；模拟器实启验证（Pixel_7）。
- **自测指引**：真实凭证验收请按 [`docs/superpowers/plans/2026-09-11-M1-user-acceptance.md`](docs/superpowers/plans/2026-09-11-M1-user-acceptance.md) 执行——含 M1 登录链路（第二~五节）、M2 会话列表/聊天（第六~七节）、M3 富文本消息（第八~九节）、M4 群组/通讯录（第十~十一节）与 **M5 搜索/presence/设置（第十二~十三节：双端对照清单、已知差异清单）**。

```bash
./gradlew :app:assembleDebug          # 构建 debug APK（cn.appia.im.debug）
./gradlew :app:testDebugUnitTest :app:lintDebug   # 全量门禁
maestro test maestro/launch_android.yaml           # 启动冒烟（需模拟器/真机）
maestro test maestro/login_reach_login.yaml        # 企业码失败链路冒烟
maestro test maestro/roomlist_swipe.yaml           # 列表滑动冒烟（需已登录会话）
```

M5 已知差异（头像上传 RN 未做对齐只读/字体仅持久化/检查更新跳浏览器/links 启发式/加密房字面 LIKE 等）见自测协议第十三节；M1-M4 遗留差异见第四/七/九/十一节。

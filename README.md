# AppiaAndroid

appiaMobile（React Native）的原生 Android 重写。当前版本 **0.3.0**（M2 里程碑）。

## M2 状态与自测指引

- **M2 范围**：会话列表与房间聊天——列表三段分组/排序/搜索、未读徽标、左右滑快捷动作、连接横幅、下拉刷新、房间路由、消息渲染（系统消息/mention/草稿）、进房即读+停留标读、文本收发（echo 去重/状态徽标）、历史翻页、滚到底。
- **测试状态**：620 单测 + lint 全绿（M2 收尾门禁 3 次全过，flake 0/3）；Maestro 冒烟（`maestro/`）与模拟器真实会话走查通过。
- **自测指引**：真实凭证验收请按 [`docs/superpowers/plans/2026-09-11-M1-user-acceptance.md`](docs/superpowers/plans/2026-09-11-M1-user-acceptance.md) 执行——含 M1 登录链路（第二~五节）与 M2 会话列表/聊天（第六~七节：双端对照清单、问题反馈格式、已知差异清单）。

```bash
./gradlew :app:assembleDebug          # 构建 debug APK（cn.appia.im.debug）
./gradlew :app:testDebugUnitTest :app:lintDebug   # 全量门禁
maestro test maestro/launch_android.yaml           # 启动冒烟（需模拟器/真机）
maestro test maestro/login_reach_login.yaml        # 企业码失败链路冒烟
```

M2 已知差异（已读回执、深链、附件/撤回等 M3/M5/M6 收口项）见自测协议第七节。

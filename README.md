# AppiaAndroid

appiaMobile（React Native）的原生 Android 重写。当前版本 **0.4.0**（M3 里程碑）。

## M3 状态与自测指引

- **M3 范围**：消息完整功能——富文本编辑（WebView 10tap 编辑器 + 工具栏：加粗/斜体/删除线/高亮/字色/列表/@）与渲染（md AST 全节点：标题/列表/代码块/引用/表格/链接/BIG_EMOJI/mention 色；KaTeX 降级原式+点击全屏渲染）、附件收发查看（图片缩放/视频音频 Media3/PDF 与 office 预览/上传进度环）、转发（单条+合并卡片）、表情回应（Android 领先，RN 未实现）、已读回执明细、编辑（edited 标记）/撤回（分组+重新编辑）/批量撤回、@提及补全、长按菜单全集。
- **测试状态**：1062 单测 + lint 全绿；Maestro 冒烟（`maestro/`）通过。
- **自测指引**：真实凭证验收请按 [`docs/superpowers/plans/2026-09-11-M1-user-acceptance.md`](docs/superpowers/plans/2026-09-11-M1-user-acceptance.md) 执行——含 M1 登录链路（第二~五节）、M2 会话列表/聊天（第六~七节）与 M3 富文本消息（第八~九节：双端对照清单、问题反馈格式、已知差异清单）。

```bash
./gradlew :app:assembleDebug          # 构建 debug APK（cn.appia.im.debug）
./gradlew :app:testDebugUnitTest :app:lintDebug   # 全量门禁
maestro test maestro/launch_android.yaml           # 启动冒烟（需模拟器/真机）
maestro test maestro/login_reach_login.yaml        # 企业码失败链路冒烟
maestro test maestro/roomlist_swipe.yaml           # 列表滑动冒烟（需已登录会话）
```

M3 已知差异（KaTeX 降级、表情回应 Android 领先、ACTIONS/COLLAPSIBLE_QUOTE 卡片 M7 等）见自测协议第九节；M1/M2 遗留差异（深链、搜索等 M4-M6 收口项）见第四/七节。

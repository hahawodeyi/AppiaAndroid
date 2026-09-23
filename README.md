# AppiaAndroid

appiaMobile（React Native）的原生 Android 重写。当前版本 **0.5.0**（M4 里程碑）。

## M4 状态与自测指引

- **M4 范围**：群组与联系人——房间信息页（权限门/成员预览/静音置顶/分类 usage/退出）、成员管理（角色操作/移除/部门分组/发 DM）、公告编辑与房名修改、选人器（建群/加人/部门树多选/agents）、通讯录双树（hrm 数据源 + 部门下钻）、成员名片/我的二维码/发起 DM、房间访问丢失收口（被踢/自退/别处退出三态检测 + 栈清理导航）。
- **测试状态**：1360 单测 + lint 全绿；Maestro 冒烟（`maestro/`）通过。
- **自测指引**：真实凭证验收请按 [`docs/superpowers/plans/2026-09-11-M1-user-acceptance.md`](docs/superpowers/plans/2026-09-11-M1-user-acceptance.md) 执行——含 M1 登录链路（第二~五节）、M2 会话列表/聊天（第六~七节）、M3 富文本消息（第八~九节）与 **M4 群组/通讯录（第十~十一节：双端对照清单、已知差异清单）**。

```bash
./gradlew :app:assembleDebug          # 构建 debug APK（cn.appia.im.debug）
./gradlew :app:testDebugUnitTest :app:lintDebug   # 全量门禁
maestro test maestro/launch_android.yaml           # 启动冒烟（需模拟器/真机）
maestro test maestro/login_reach_login.yaml        # 企业码失败链路冒烟
maestro test maestro/roomlist_swipe.yaml           # 列表滑动冒烟（需已登录会话）
```

M4 已知差异（agents 仅选择/头像首字占位/创建智能体未落/OKR 占位/语音禁用 M10 等）见自测协议第十一节；M1-M3 遗留差异见第四/七/九节。

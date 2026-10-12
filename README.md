# AppiaAndroid

appiaMobile（React Native）的原生 Android 重写。当前版本 **0.9.0**（M8 里程碑）。

## M8 状态与自测指引

- **M8 范围**：会议——预订 CRUD 六接口（meetingRoomBook/RecordUpdate/editPeriodicSingle/cancelPeriodicSingle/meetingRoomList/成员）、payload 构建校验（坑 11 手动会议号必填对）、会议面板双 tab（upcoming/past 分组+纪要）、纪要详情纯本地渲染、预订表单（周期 0-6/会议室/与会人 dialog/腾讯代建 createTxMeeting）、入口接线（⋮ 菜单/RoomInfo 行/附件门控/AntMeeting 移交/openLink 分支）、GREENFIELD Agora 原生 RTC（credentials 接口 X-Auth-Token+X-User-Id 双头/引擎封装接口化/会议状态机 phase 语义/会议房间 UI 宫格控制条/坑 4 会中返回=离开确认/重入走控制器拒绝/引擎 @Singleton 跨重建）、浮窗（前台服务 SPECIAL_USE/canDrawOverlays 降级链坑 7/TYPE_APPLICATION_OVERLAY 参数 verbatim）+ 应用内气泡（拖拽累积/位置持久化）、横屏三触发源（共享驱动/按钮直调/最小化强制竖屏）+ manifest configChanges、互斥收口（会议侧 blocksVoiceStart/M10 缝）。
- **测试状态**：2211 单测 + lint 全绿；门禁×3（含 --rerun-tasks 强制全量）；Maestro 启动/登录链冒烟绿；模拟器实启零崩溃。
- **自测指引**：真实凭证验收请按 [`docs/superpowers/plans/2026-09-11-M1-user-acceptance.md`](docs/superpowers/plans/2026-09-11-M1-user-acceptance.md) 执行——M1 第二~五节、M2 第六~七节、M3 第八~九节、M4 第十~十一节、M5 第十二~十三节、M6 第十四~十五节、M7 第十六~十七节、**M8 第十八~十九节（预订 CRUD/面板/入会 RTC/浮窗横屏互斥；外部依赖前置：Agora AppId/RTC 凭据抓包一次性/二设备/worktable 配置）**。

```bash
./gradlew :app:assembleDebug          # 构建 debug APK（cn.appia.im.debug）
./gradlew :app:assembleRelease        # release APK
./gradlew :app:testDebugUnitTest :app:lintDebug   # 全量门禁
maestro test maestro/launch_android.yaml           # 启动冒烟（需模拟器/真机）
maestro test maestro/login_reach_login.yaml        # 企业码失败链路冒烟
```

M8 已知差异（原生 RTC 替换 H5 不互通/凭据字段反推待抓包/无 token 生产模式待服务端/视频帧未上屏/被踢 onError 近似/成员表无名字映射/时间轴占用简化等 13 项）见自测协议第十九节；M1-M7 遗留差异见第四/七/九/十一/十三/十五/十七节。杂项 backlog：`docs/backlog-m6.md`。

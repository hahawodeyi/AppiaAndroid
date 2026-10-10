# M6 杂项 Backlog（终审 M-5 无主项收编）

> 记录性质：本页只收编 M1–M5 各任务终审遗留的杂项缺陷（无归属、非阻塞、不联动验收）。
> 每项一行 + 建议归属（落地时随对应任务/里程碑顺手修复，**本页不直接排期**）。
>
> **M7 处置**（2026-10-10 M7-T1 收编核对，总纲 §4.7）：逐项标注归属；#10 已消化（DONE），
> #9 链路已接（sink 待角标数据面），#1–#8 留后续域任务顺手修复，本页不删行。

| # | 事项 | 建议归属 | M7 处置 |
|---|------|----------|---------|
| 1 | T5-①：chat.search 返回 `success:false` 时全局搜索消息段渲染为空态（无重试/错误态）——RN 同缺陷但本地回退只覆盖 spotlight 段 | feature/search（GlobalSearchScreen 消息段空态 + GlobalSearchViewModel 错误分支） | 留 M8+（feature/search 域任务顺手） |
| 2 | T5-③：spotlight 联系人 username 为空时点击走 `RoomRoute(rid="")` 空房（resolveDirectChatRid 回退未拦空 username） | MainActivity GlobalSearch onOpenContact 链 / core/chat resolveDirectChatRid 入参守卫 | 留 M8+（feature/search 域任务顺手） |
| 3 | T5-②：`_id` 空串的文件行 key 碰撞去重 O(n²)（fileRowKey 兜底 `file-$index` 与 messageId:index 组合在大列表跨页重复比较） | feature/search（GlobalSearchViewModel fileRowKey / Screen LazyColumn key） | 留 M8+（feature/search 域任务顺手） |
| 4 | T8-M-1：tSearch bump 用整行 REPLACE 写回（快照外字段被并发 DDP 覆盖时 clobber）——应改列更新或读-改-写原子化 | domain/chat ChatBumper（tSearch bump 写路径） | 留 M8+（domain/chat 域任务顺手） |
| 5 | T9-M-9：ServerInfoApi.kt:46,107 catch(Exception) 吞 CancellationException——协程取消被当业务失败 | core/network/api ServerInfoApi（catch 分支 re-throw CE） | 留 M8+（core/network 域任务顺手） |
| 6 | T2：RoleRefresher inflight 去重分支改名（现名与语义不符的死分支，易误读） | domain/session RoleRefresher（纯改名） | 留 M8+（domain/session 域任务顺手） |
| 7 | T6-M1：jump 模式下发消息后未 scroll-to-latest（RN 跳转模式发送即回底，Android 缺该联动） | feature/chat RoomScreen（发送成功 → 列表回底） | 留 M8+（feature/chat 域任务顺手） |
| 8 | T6-M5：loadEarlier 加载更早消息无 spinner（isLoadingEarlier 态未接 UI） | feature/chat RoomScreen / RoomMessagesViewModel isLoadingEarlier 接线 | 留 M8+（feature/chat 域任务顺手） |
| 9 | M6 终审搭车：推送到达时跨组织未读刷新（RN pushService onNotification，多组织列表角标）——receiver 现仅日志 | M7（推送到达链随手接；AppiaAliyunPushReceiver.onNotification） | **M7-T1 链路已接**（`chore(m7): 前置收尾`）：决策+到达挂点+sink 缝（core/push/CrossOrgUnreadRefresh，RN crossOrgUnreadRefresh.ts 语义移植）；AA 无 tab indicator / unread.list 数据面，sink 注册（`CrossOrgUnreadRefresh.action`）归多组织未读角标特性（M10+） |
| 10 | M6 终审搭车：GlobalSearchRoute initialQuery seed-once guard（LaunchedEffect(Unit) 每次重组重播种会覆盖用户清空后的输入；T1 评审承诺 T5 携带未交付） | feature/search GlobalSearchScreen / MainActivity GlobalSearchRoute 装配（ remembered seed 标志） | **DONE（M7-T1，`chore(m7): 前置收尾` + 评审 I-1 修复）**：深链词入 GlobalSearchViewModel 构造器（init 消费一次，词同步入 state；factory 仅首建执行，旋转不重播种、清空后旋转不复活）；GlobalSearchScreen 输入框初值读 VM state.query |

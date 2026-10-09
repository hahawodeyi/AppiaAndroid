# M6 杂项 Backlog（终审 M-5 无主项收编）

> 记录性质：本页只收编 M1–M5 各任务终审遗留的杂项缺陷（无归属、非阻塞、不联动验收）。
> 每项一行 + 建议归属（落地时随对应任务/里程碑顺手修复，**本页不直接排期**）。

| # | 事项 | 建议归属 |
|---|------|----------|
| 1 | T5-①：chat.search 返回 `success:false` 时全局搜索消息段渲染为空态（无重试/错误态）——RN 同缺陷但本地回退只覆盖 spotlight 段 | feature/search（GlobalSearchScreen 消息段空态 + GlobalSearchViewModel 错误分支） |
| 2 | T5-③：spotlight 联系人 username 为空时点击走 `RoomRoute(rid="")` 空房（resolveDirectChatRid 回退未拦空 username） | MainActivity GlobalSearch onOpenContact 链 / core/chat resolveDirectChatRid 入参守卫 |
| 3 | T5-②：`_id` 空串的文件行 key 碰撞去重 O(n²)（fileRowKey 兜底 `file-$index` 与 messageId:index 组合在大列表跨页重复比较） | feature/search（GlobalSearchViewModel fileRowKey / Screen LazyColumn key） |
| 4 | T8-M-1：tSearch bump 用整行 REPLACE 写回（快照外字段被并发 DDP 覆盖时 clobber）——应改列更新或读-改-写原子化 | domain/chat ChatBumper（tSearch bump 写路径） |
| 5 | T9-M-9：ServerInfoApi.kt:46,107 catch(Exception) 吞 CancellationException——协程取消被当业务失败 | core/network/api ServerInfoApi（catch 分支 re-throw CE） |
| 6 | T2：RoleRefresher inflight 去重分支改名（现名与语义不符的死分支，易误读） | domain/session RoleRefresher（纯改名） |
| 7 | T6-M1：jump 模式下发消息后未 scroll-to-latest（RN 跳转模式发送即回底，Android 缺该联动） | feature/chat RoomScreen（发送成功 → 列表回底） |
| 8 | T6-M5：loadEarlier 加载更早消息无 spinner（isLoadingEarlier 态未接 UI） | feature/chat RoomScreen / RoomMessagesViewModel isLoadingEarlier 接线 |
| 9 | M6 终审搭车：推送到达时跨组织未读刷新（RN pushService onNotification，多组织列表角标）——receiver 现仅日志 | M7（推送到达链随手接；AppiaAliyunPushReceiver.onNotification） |
| 10 | M6 终审搭车：GlobalSearchRoute initialQuery seed-once guard（LaunchedEffect(Unit) 每次重组重播种会覆盖用户清空后的输入；T1 评审承诺 T5 携带未交付） | feature/search GlobalSearchScreen / MainActivity GlobalSearchRoute 装配（ remembered seed 标志） |

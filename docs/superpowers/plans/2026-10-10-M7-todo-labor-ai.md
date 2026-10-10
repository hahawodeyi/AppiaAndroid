# M7 待办+工作台+AI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 复刻 appiaMobile 的待办域（列表/房间待办/双计数源/设完成改提醒）、Labor 工作台（server-driven 宫格 + InAppWeb 拦截族七项补齐）、AI Agent（SSE 流式 + 槽位机制 + agents 管理动作），并落地总纲 §4.7 前置项。

**Architecture:** 三域均完整实现（调研报告，无 stub）。待办=纯 REST 无本地表（消息级标记走 `messages.appia_todo` JSON + DDP 回流；**双计数源有意不同步勿统一**）；工作台=裸 fetch 配置 + InAppWeb 七项缺口（泛微三件套是"头+cookie+reload+XHR劫持"一体）；AI=**POST SSE**（OkHttp 流式 body 手写，小写 header）+ 客户端组件 state 流式文本 + `bot.saveAIMessage` 回写 + DDP 同 id 回流替换槽位（**FINISH 后必须停读**；错误路径也必须 finalize 防串行驱动卡死）。

**Tech Stack:** 既有栈；SSE = OkHttp 流式 body 手写（无新依赖，坑 4 的 RN _pollAgain 缺陷 AA 天然无）；markdown 复用 M3 链。

**Spec:** `docs/superpowers/plans/REWRITE_MASTER_PLAN.md`（§4.7 M6 遗留在 T1 落实）
**行为事实来源:** `/Users/bitmain/Projects/rebuild-mobile/appiaMobile`（RN）；调研报告 `2026-10-10-M7-research.md`（坑清单 12 条 + wire §5）

## Global Constraints

- **坑 1**：待办双计数源（抽屉=REST total / 红点=DDP todoCount）有意不同步——勿用单一源"修"齐
- **坑 2**：设待办发 `status:1`、回流显示 `status:0`=进行中、完成发 `-1`——发送值≠显示值是服务端约定照抄勿修正
- **坑 4**：SSE 收到 FINISH/DONE **立即停止读取**（RN 服务器 DONE 后可能不关流）
- **坑 5/6**：流式文本只活组件 state；持久化=客户端 POST bot.saveAIMessage + DDP 同 id 回流替换槽位；双兜底（真实消息到达 clear / 15s 超时强清）缺一卡死；**错误路径必须 finalize**；槽位防闪烁（同 id 已在列表不注入）；runAiTurn 先 setProcessing 再 await clear（顺序反了覆盖槽位）
- **坑 7**：prompt 剔除 @bot 自身文本；附件有专用模板串（image-url/file-proxy）
- **坑 8**：worktable_config 裸 fetch 无 auth 头（勿套 AuthInterceptor）；相对 url/icon 补 server 前缀
- **坑 9**：泛微 Android 三件套（头 Weavertoken + cookie+sessionStorage 防循环 reload + XHR/fetch 劫持）——AA 用 onPageFinished/doUpdateVisitedHistory 重现，防循环 key `hasReloaded_<url>` 必须保留
- **坑 10**：返回决策链全分支（chat-gpt/BACK_CLOSE 段/根页比较/antagent 两 URL）漏则困 H5
- **坑 12**：待办完成双写（POST 成功 + 清本地消息 appiaTodo）——只 POST 房间内样式不消失
- staffService 客服域与 AntMeeting 预热明确划出（M10），留路由参数占位；FastModelMessage 引文（bot.docs/citation）随 staffService 划出，留类型桩
- 纯函数优先 TDD（触发判定/prompt 组装/排序/归一化/版本比较类全 TDD）；文案 i18n（todo_*/agents_* 键已备）；提交中文
- 用户验收分工同前

---

### Task 1: M7 前置收尾（总纲 §4.7 代码项）

**Files:** 推送 onNotification 分流、GlobalSearchViewModel、backlog-m6.md
- [ ] ① **跨组织未读刷新**（backlog #9）：推送到达（onNotification 分流一支，非本组织 host）→ 跨组织未读计数刷新（对照 RN onNotification 行为）——receiver/router 挂点 + 刷新动作
- [ ] ② **GlobalSearch initialQuery seed-once guard**（backlog #10）：VM entry 级存活旋转——路由参数重播种只取一次（deep-link 带搜索词前必修）
- [ ] ③ **backlog-m6.md 收编核对**：逐项标注归属（M7 本计划处理项勾走/留 M8+/运维域），删除已消化行
- [ ] TDD ≥4 → Commit: `chore(m7): 前置收尾（总纲 §4.7）`

---

### Task 2: 待办 API + 数据层

**Files:**
- Create: `core/network/api/TodosApi.kt`（W1 四接口 verbatim：GET appia/todos?offset&count[&rid] / POST set-message-todo {messageId,status:1,tips:'',type:'d'} / POST update-message-todo-status {id,status:-1} / POST update-message-todo）
- Modify: `ChatMerger`/SubscriptionMerger（核对 todoCount/highTodoCount/defaultTodoCount/isRoomToDo 映射——ChatEntity 有列，确认 DDP 增量真进表）、`ChatListSorter`（待办分区四分排序对齐 RN sortRoomListChats:67-79：高优+草稿 > 高优 > 普通+草稿 > 普通）、`ChatListSectioner`（`TODO("roomList_sectionTodo")` 段头落地）
- Test: TodosApi wire ≥8（四接口逐字段/rid 可选参）；排序四分 ≥6；Merger 字段核对测试
- [ ] TDD → Commit: `feat(todo): 待办 API 与会话计数层`

---

### Task 3: 待办 UI（双屏 + 入口 + 动作）

**Files:**
- Create: `feature/todo/TodoListRepository.kt`（REST 拉取 + react-query 等价缓存：mutation 后 invalidate + 手动下拉刷新，无 DDP 订阅——坑 1）、`feature/todo/ui/TodoListScreen.kt`（全量）、`feature/todo/ui/RoomTodoScreen.kt`（按 rid；onGotoSession 同房保留参跳 jumpToMessageId；footer/空态「全部待办」钮）、`feature/todo/ui/TodoCard.kt`（共用：高优 tag/标题 stripMarkdownLite/附件图与文件行/房间名 getTodoRoomDisplayName——myAgents 房显 Agent/时间 isOvertime 红/完成+去处理操作行）、`feature/todo/TodoActions.kt`（完成双写：POST + clearMessageAppiaTodo 清本地消息——坑 12；改提醒过去时间拒提）
- Modify: 抽屉 MineMenu 待办卡（REST total 计数 + 首条预览卡）、RoomScreen 房间头待办入口（todoCount>0 显示）、会话行头像红点组件（>99 99+）、长按菜单「设待办」（toggleTodoMessage status:1）、导航图
- Test: Repository 缓存失效语义 ≥4、TodoActions 双写/拒提 ≥6、排序已 T2、附件跳转参数 ≥4 + Compose 冒烟
- [ ] 附件跳转复用 MediaViewerRoute/DocPreviewRoute（鉴权参对照 openTodoAttachment）
- [ ] Commit: `feat(todo): 待办双屏与入口动作（双计数源/完成双写）`

---

### Task 4: 工作台 LaborScreen

**Files:**
- Create: `core/network/api/WorktableApi.kt`（裸 GET worktable_config?platform=app——**无 auth 头独立 OkHttp/Retrofit 实例**——坑 8）、`feature/labor/LaborRepository.kt`（normalizeWorktableGroups 移植：status<=0 过滤/相对 url+icon 补 server 前缀/空组丢弃/guest 过滤 E-Learning）、`feature/labor/ui/LaborScreen.kt`（宫格分组 + 搜索过滤 + 点击路由三支：type3 建私信 DM / 消息待办 type10 → TodoList / 其余 InAppWeb）
- Modify: 抽屉「工作台」入口、导航图
- Test: 归一化（过滤/前缀/访客）≥10、路由三支 ≥6
- [ ] 考勤打卡位置权限先询（laborItemNeedsLocationPermission 命中 → ensureFineLocation → 拒绝 Alert 不打开）
- [ ] Commit: `feat(labor): 工作台宫格（server-driven/三支路由/位置权限）`

---

### Task 5: InAppWeb 拦截族补齐（七项）

**Files:**
- Modify: `feature/web/InAppWebScreen.kt` + `core/web/`（新文件按需）——§2.5 七项：
- [ ] ① **泛微三件套**：Weavertoken 预取（proxy/hrm/resource/token 进程内 promise 去重失败静默）+ 请求头 + cookie 注入+sessionStorage 防循环 reload（onPageFinished/doUpdateVisitedHistory 重现，key `hasReloaded_<url>`）+ XHR/fetch 劫持注入脚本（buildInjectedScripts:88-120 移植）+ 跨页 transition 检测（onNavigationStateChange 等价：非泛微域→泛微域注入+reload）
- [ ] ② 石墨 appendShimoQueryIfNeeded ③ WPS 入口改写（WPS_ORG_DOC_HOSTS + docs.appia.vip fallback 'all'→docs.bitmain.vip）
- [ ] ④ 会议外链拦截（腾讯会议 http(s)→外链拒载；wemeet:// scheme 拒载；AntMeeting 归 M10 留桩）
- [ ] ⑤ postMessage 桥（SetTitle 改标题 / onResetPasswordSuccess→logout+goBack / navigationStateChange）
- [ ] ⑥ 返回决策链全分支（resolveInAppWebBackAction 完整移植：chat-gpt source pop / BACK_CLOSE_URL_SEGMENTS / 根页 origin+hash 去 query 比较 / ANT_AGENT_FORCE_POP_URLS 两 URL）——替换 M5 简化 BackHandler
- [ ] ⑦ needVPN 探活（HEAD/GET 10s 超时→「需 VPN」错误页）+ RECRUITMENT 隐藏顶栏注入 + WebView props（mixedContentMode/sharedCookies/multipleWindows false/geo）
- Test: URL 改写链（石墨/WPS/needAuth 组合）≥12、返回决策全分支 ≥10、泛微 token 去重 ≥4、VPN 判定 ≥4
- [ ] Commit: `feat(web): InAppWeb 拦截族补齐（泛微三件套/石墨/WPS/返回链/postMessage）`

---

### Task 6: AI SSE 客户端 + 触发判定（TDD 重头）

**Files:**
- Create: `core/network/sse/AiStreamClient.kt`（OkHttp 流式 body 手写 POST SSE——坑 4：FINISH/DONE 立即停读；小写 x-auth-token/x-user-id；body {prompt,stream:true,rid,agentUserId?}；chunk JSON {type,text,content,error} 解析 text??content??raw 次序）
- Create: `core/ai/BotEndpoints.kt`（botConfig 移植：staffService.bot→saveToStaffServiceAgent/agent.bot+personal.bot→sendToAI/Agent_Bot_List 其它→saveToClawAgent/否则 null）、`core/ai/AiTurnResolver.kt`（resolveAiTurn 纯函数：parseBotMentions 保序去重/prompt 剔除 @bot/shouldTriggerAi 判定）、`core/ai/AiPromptBuilder.kt`（extractAIPrompt：文本+图片 `\n\n请分析图片内容：<image-url>{url}</image-url>`+文件 file-proxy 模板——Site_Url 设置）
- Test: SSE 解析（六 type/非 JSON raw/ FINISH 停读/错误路径）≥10、BotEndpoints 映射 ≥8、Resolver（mentions 保序/剔除/触发条件）≥10、PromptBuilder 模板 ≥6
- [ ] Commit: `feat(ai): SSE 客户端与触发判定（FINISH 停读/串行前置）`

---

### Task 7: AI 槽位机制 + 串行驱动 + 持久化回流

**Files:**
- Create: `domain/ai/AiRoomStateMachine.kt`（aiStore 等价：StateFlow<Map<rid,AiRoomState>>；runAiTurn 串行——先 setProcessing 再 await clear（**顺序反了覆盖槽位**——坑 6）+ 60s 兜底超时）、`core/network/api/AiBotApi.kt`（bot.saveAIMessage/stopToStaffServiceAgent——bot.docs 引文随 staffService 划出留桩）
- Modify: RoomMessagesViewModel/RoomScreen（agentLoadingMsg 槽位注入：isProcessing 且真实消息未到→最新端注入 u=bot/msg=prompt/msgData={relatedUserMessageId,botReplyIndex}；**同 id 已在列表不注入**；真实消息到达 clear；15s 兜底强清；停止按钮 stopAiProcessing——abort+finalize 停止后有文本仍持久化；发送路径挂 maybeTriggerAiAfterSend）
- Test: 状态机（串行/超时/停止/错误 finalize/防闪烁不变量）≥12
- [ ] Commit: `feat(ai): 槽位机制与串行驱动（双兜底/防闪烁/停止持久化）`

---

### Task 8: AI 渲染 + myAgents 房 + agents 管理

**Files:**
- Create: `feature/chat/ui/AiResponseMessage.kt`（msgType==='ai_response' 渲染：M3 markdown 链复用+复制按钮；流式中纯文本完成后才切 markdown）、`feature/agents/AgentEditorScreen.kt`（RN AgentEditorScreen 移植：创建/编辑/禁用/恢复——ClawAgentsApi 补 create/update/delete/restore 四 POST + parseClawAgentQuickAddInput/isDuplicateClawAgentIdError/base64 快捷添加）
- Modify: 会话列表 myAgents 虚拟行（useAgentChannelListRow 等价：MMKV 缓存 rid+im.create 自聊 DM+fromAgent 自动标题 Agent）、@*.bot mention 触发接线（T6 Resolver 进发送路径）、fastModelMsg 类型桩（渲染"暂不支持"占位或最小文本——staffService 域 M10）
- Test: AgentEditor 判定（重复 id/快捷添加解析）≥8、myAgents 行逻辑 ≥4 + Compose 冒烟
- [ ] i18n：agents_* 43 键已备核对
- [ ] Commit: `feat(ai): ai_response 渲染与 agents 管理动作`

---

### Task 9: M7 收尾

- [ ] 门禁×3（flake 率，含 RealtimeSessionManagerTest 具名复核延续）+ Maestro（launch/login 链 + todo/labor 冒烟如可离线模拟——边界如实）+ 模拟器走查
- [ ] AI 端到端边界如实：真流式需服务端 bot 可用（用户域验收依赖，协议十六节列明）
- [ ] 自测协议 M7 增补（第十六/十七节）：待办（抽屉卡/双计数源差异说明/设完成改提醒/附件跳转）/工作台（宫格/搜索/三支路由/考勤定位）/InAppWeb（泛微/石墨/WPS/返回链/会议拦截）/AI（myAgents/@bot 流式/停止/持久化回流/agents 管理）——已知差异（staffService 划 M10/FastModel 桩/双计数源 RN 同构等）
- [ ] versionName 0.8.0；README
- [ ] Commit + push: `chore(m7): 收尾与自测协议增补`

---

## M7 完成定义

1. 9 任务全部 SDD 闭环
2. 全量单测+lint 绿；门禁×3；模拟器走查
3. `.debug` APK + 协议 M7 增补交付
4. AI 真流式（服务端 bot）与工作台真实配置由用户验收后 M7 关闭

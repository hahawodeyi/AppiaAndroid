# M3 消息完整功能 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 复刻并补齐消息完整功能：附件上传/查看（图片/视频/文档）、Markdown/HTML 富文本渲染（AST 全节点）、ProseMirror 富文本编辑器（方案 c：WebView 复用 10tap web 构建——spike 已判 GO）、转发（单条+合并）、表情回应（绿地，服务端已支持）、已读回执、@提及自动补全、消息长按菜单（编辑/撤回/复制/多选/回复引用）。

**Architecture:** 在 M2 的 SendOrchestrator/RoomStreamManager/消息落库之上：渲染层（md AST 直读 messages.md 列 → 自研节点分发渲染，不用 WebView）；编辑层（assets 装载 684KB vite-singlefile 构建 + addJavascriptInterface 桥 + messageId RPC）；媒体层（rooms.upload multipart + Media3/PdfRenderer 查看 + AttachmentUrlFormatter 鉴权 URL）。

**Tech Stack:** 既有栈 + Media3/ExoPlayer（视频/音频）、PdfRenderer（文档）、Coil（图片）、WebView（编辑器+KaTeX 降级查看）。

**Spec:** `docs/superpowers/plans/REWRITE_MASTER_PLAN.md`（§4.3 M3 前置 7 项在 Task 1 落实）
**行为事实来源:** `/Users/bitmain/Projects/rebuild-mobile/appiaMobile`（RN）+ `legacy/appiaim-ios`（仅表情回应参照）；调研报告 2026-09-17（编辑器 spike GO 判据 6 条在内）

## Global Constraints

- **基线事实修正（终审调研确认）**：① 表情回应 RN 版**未实现**（0 字节占位）——M3 做 Android 领先功能（服务端 `chat.react` 已支持，RN 被动持久化不冲突），已知差异入册；② RN 无图片客户端压缩（picker quality 0.8 之外零处理）——Android 跟随现状，不新增压缩；③ 已读回执 = 服务端单布尔 `message.unread`（无三态勾）
- **编辑器 spike 决策点**：Task 2 按 6 条验收判据执行；任一不过 → 停下报告（降级方案 b 评估），**不自行降级**
- 渲染红线：md 列 JSON 直读反序列化（勿重 parse msg）；`plainTextFromMd(md) !== msg` 才回退 `parseMsgToMd`
- **status/attachments 写纪律延续**：status 仅 Orchestrator；**上传成功路径禁写本地 attachments 形状**（会覆盖服务端 attachments 致渲染回退本地——RN SendOrchestrator:384-391 长注释）
- tempId↔serverId 迁移语义复用 M2 SendOrchestrator（rooms.upload/multiAttachments 同样可能回服务端 _id）
- 纯函数优先（转换器/预览/映射全 TDD）；文案走 i18n；提交中文；每任务测试绿+提交
- 用户验收分工同前：MockWebServer 全链 + 模拟器走查到请求边界；真实媒体收发由用户 side-by-side
- M3 已知差异预期入册：KaTeX 首版降级原式文本（可点击单条 WebView 渲染，低频场景）；其余见 Task 14

---

### Task 1: M3 前置收尾（总纲 §4.3 七项）

**Files:**
- Modify: `feature/chat/ui/MessageRow.kt`（mention 显示名解析）、`core/messaging/`（LastMessagePreview previewTableLabel、success:false 通道）、`core/realtime/RoomStreamManager.kt`（load_chunk 分隔排除、onSessionTornDown opMutex + pending.remove 前移）、`maestro/`（滑动用例增量）
- Test: 对应扩展

**Interfaces:**
- Produces（§4.3 逐项）:
  1. MENTION 显示名解析：mentions 数组命中显示 name（无 @），未命中 @username——**只影响消息正文渲染**（M2 列表预览已是近似，列表不动）
  2. previewTableLabel 参数进 LastMessagePreview（M3 表格预览用，本任务先落参数默认不传）
  3. load_chunk 行不参与日期分隔推导（渲染空行）
  4. `onSessionTornDown` 收进 opMutex + `pending.remove(rid)` 前移至 handler 同步段（对齐 RN 首个 await 前）
  5. `success:false` 通道：sendOne/loadRoomHistory 判定链补 `success===false` 短路（对照 RN retryPolicy 顺序）
  6. Maestro 滑动用例增量（列表滑壳 testTag 断言）
  7. `markStatus` 改 `UPDATE messages SET status=? WHERE _id=?`（根除 get-then-update 与全行 upsert 的丢 SENT 窗口——M2 终审 Minor-6）
- [ ] 每项 TDD/验证 → 全量绿 → Commit: `chore(m3): 前置收尾七项（总纲 §4.3）`

---

### Task 2: 编辑器资产集成 spike（go/no-go 决策点）

**Files:**
- Create: `app/src/main/assets/editor/index.html`（拷自 RN `external/10tap-editor/src/simpleWebEditor/build/index.html`，只读源）、`feature/chat/editor/EditorWebView.kt`、`feature/chat/editor/TenTapBridge.kt`（最小版：postMessage 接口 + MessageEvent 注入 + EditorReady/stateUpdate）
- Modify: 临时调试入口（debug-only Activity 或 RoomScreen 开关）
- Test: 桥消息解析单测 + 模拟器手动验证记录

**Interfaces:**
- Produces: spike 结论报告（6 条判据逐条）：① assets 加载+桥接后 EditorReady/输入/stateUpdate；② setContent→getJSON round-trip 无损（列表/mention/emoji）；③ beforeinput @ 触发到达 + insertMention/insertEmoji/deleteRange 回写；④ **中文/emoji IME composing 不丢字、光标正确**；⑤ 键盘/焦点/高度联动；⑥ 目标设备 WebView 支持 `<script type="module">`（不支持→vite iife 重构建，报告注明）
- **NO-GO 即停**：报告触发降级评估（方案 b 范围见调研报告 §13），controller 决策，不自行切方案
- [ ] 走 6 条判据 → spike 报告（含模拟器 WebView 版本、卡点）→ Commit: `feat(editor): 10tap web 构建资产与 Android 桥 spike`

---

### Task 3: TipTap↔md AST 双向转换器（不依赖 spike，方案 b 也需要）

**Files:**
- Create: `core/messaging/TipTapJsonConverter.kt`、`core/messaging/MessageParserTypes.kt`（Root/块级/行内节点类型，含 Appia TABLE/KATEX 形状）
- Test: `TipTapJsonConverterTest.kt`（移植 RN editorJson.test.ts / mdToTipTap.test.ts 用例）

**Interfaces:**
- Produces:
  - `convertTipTapJsonToMessageParserRoot(tipTap: JsonObject): Root`：块级 flatMap；URL_REGEX 拆 LINK；默认色 `#1D2129`/`14px` 不产生 BOLD 包装；mention 后跟空格 PLAIN_TEXT；heading 压纯文本；**BIG_EMOJI**（唯一块且全 EMOJI、1≤n≤3）
  - `mdToTipTap(root: JsonObject, mentions: JsonArray?): JsonObject`（反向：EMOJI→customEmoji 节点、MENTION_USER×mentions→mention 节点、列表 round-trip、CODE/HEADING/QUOTE/TASKS 降级纯文本段落）
  - `extractPlainTextFromTipTapJson`（mention `${char}${id} `）
- [ ] TDD ≥20 用例（RN 测试移植 + round-trip 无损组）→ Commit: `feat(messaging): TipTap 与消息 AST 双向转换器`

---

### Task 4: md 渲染链——解析与块级节点

**Files:**
- Create: `core/messaging/MessageMdResolver.kt`（resolveMessageMd/parseMsgToMd/finalizeMd/GFM 表格 augment）、`feature/chat/ui/MessageBody.kt`（块级分发）、`feature/chat/ui/MarkdownNodes.kt`（Paragraph/Heading/Quote/Code/List/BigEmoji/HorizontalRule/Table 入口）
- Test: `MessageMdResolverTest.kt`、Compose 冒烟

**Interfaces:**
- Produces:
  - 解析链：`md` JSON 直读 → `plainTextFromMd(md) !== msg` stale 回退 `parseMsgToMd(msg)`（`* * *` 行→自造 HORIZONTAL_RULE）→ `augmentMdWithGfmTables`（管道表格→`PARAGRAPH{subType:'TABLE', data}`）
  - 块级渲染对照 RN renderMarkdownBlock.tsx:37-73：Paragraph（含 INLINE_KATEX 时 row 布局降级——KaTeX 首版原式等宽文本+可点击单条 WebView 渲染，已知差异）、Heading、Quote（permalink 空label LINK 剔除对照 Paragraph.tsx:18-33）、Code（AI 消息语言标签+复制按钮等价）、List（嵌套递归、marker `1) a) i) I)`）、BigEmoji（不放大仅布局特判）、HorizontalRule、Table 入口（TABLE_PREVIEW_MAX_HEIGHT 300 → 全屏 MarkdownTableScreen 等价）
  - 样式硬编码对照 RN markdown/styles.ts（inlineCode #f0f0f0、codeBlock #f5f5f5、quote 边框 3px #e0e0e0、link #1d74f5、hr #C9CDD4）
- [ ] TDD：解析链 ≥10 用例（stale 回退/GFM augment/HR 造块）；渲染冒烟
- [ ] Commit: `feat(chat): md 解析链与块级节点渲染`

---

### Task 5: md 渲染链——行内节点 + M3 前置渲染项

**Files:**
- Modify/Create: `feature/chat/ui/InlineNodes.kt`（AnnotatedString 分发）
- Test: `InlineNodesTest.kt`

**Interfaces:**
- Produces（对照 RN Inline.tsx:41-89 + Bold.tsx:39-60）:
  - PLAIN_TEXT/LINK（#1d74f5 下划线可点）/INLINE_CODE/EMOJI（customEmoji Image + shortnameToUnicode）/BIG_EMOJI 行内
  - BOLD/ITALIC/STRIKE（BOLD 带 color/size 不算 bold；**裁定：Bold 内子节点递归渲染——修复 RN Bold.tsx 不递归缺口**，偏离记录）；MENTION_USER/CHANNEL（§4.3.1 显示名解析：mentions 命中 name 无 @、未命中 @username、@all/@here 群色、自己 mentionMeColor）；KaTeX 行内降级同 Task 4
- [ ] TDD：嵌套组合（bold 内 mention/emoji——RN 缺口处断言 Android 正确渲染）/mention 三色/链接
- [ ] Commit: `feat(chat): 行内节点渲染（mention 显示名解析落地）`

---

### Task 6: 附件选择与上传链

**Files:**
- Create: `core/media/UploadApi.kt`（rooms.upload multipart / isMultiAttachment / multiAttachments / .replace）、`core/media/AttachmentUrlFormatter.kt`、`feature/chat/AttachmentSelector.kt`（PhotoPicker/Documents）、`feature/chat/PendingAttachments.kt`
- Modify: `SendOrchestrator`（enqueueFileMessage/sendOneFileJob：本地行 attachments JSON 列、进度发布、迁移复用、**成功路径禁写 attachments 形状**）
- Test: `UploadApiTest.kt`、`SendOrchestratorFileTest.kt`

**Interfaces:**
- Produces:
  - 选择：系统 PhotoPicker（图片/视频 mixed）+ Documents；拷贝至 `uploads/<ts>-<rand>-<name>`；上限 100；`prepareStatus` preparing→ready
  - 上传：`POST /api/v1/rooms.upload/{rid}` multipart——单文件带 `messageId(tempId)/ts/localPath/msg/md`；多文件逐个 `isMultiAttachment:true` 收集 fileId → `POST multiAttachments {rid, fileIds, msg, md}`；失败重试走 append（messageId + 失败项 fileIds）；**进度** keyed by tempId → StateFlow（徽标环形进度数据源）；serverId 迁移复用 M2 语义
  - `AttachmentUrlFormatter`：`?rc_uid=&rc_token=`、`/file-upload`→`/file-proxy`、去 `#`（对照 formatAttachmentUrl.ts:8-52）
- [ ] TDD：单/多文件 wire（multipart 字段逐个断言）/进度序列/迁移两序/失败重试 append/禁写 attachments
- [ ] Commit: `feat(chat): 附件选择与上传链（对照 rooms.upload/multiAttachments）`

---

### Task 7: 附件查看（图片/视频/文档/下载）

**Files:**
- Create: `feature/chat/ui/AttachmentViewer.kt`（图片 pinch 缩放+相册 Pager）、`VideoPlayer.kt`（Media3）、`DocPreview.kt`（PdfRenderer + office rooms.preview 轮询 code 2/200、30次×2s）、`core/media/MediaCache.kt`、下载（DownloadManager → `Download/appia/`）
- Test: URL formatter/缓存/office 轮询单测；模拟器走查

**Interfaces:**
- Produces:
  - 消息内附件渲染判定（服务端 attachments title_link/image_url vs 本地 LocalAttachmentShape `isLocalAttachmentShape` 分流——对照 RN）
  - 图片：点击 → 缩放预览（双击/捏合）；视频：Media3 PlayerView（黑块+▶ 占位对照 RN）；音频复用视频引擎；文档：白名单分流 → PDF 直开 / office 轮询转 PDF；下载通知
  - 缓存：DiskLruCache + in-flight 去重（替代 resolveCachedImage）
- [ ] 单测 + 模拟器走查（图片缩放/视频播放/PDF 打开）
- [ ] Commit: `feat(chat): 附件查看（图片缩放/视频 Media3/文档预览/下载）`

---

### Task 8: 表情回应（绿地，Android 领先功能）

**Files:**
- Create: `core/network/api/ReactionApi.kt`（`POST chat.react {emoji, messageId}`——toggle 语义在服务端）、`feature/chat/ui/ReactionBar.kt`、`ReactionPicker.kt`
- Test: `ReactionApiTest.kt`、reactions 解析/toggle 判定单测

**Interfaces:**
- Produces:
  - `reactions` JSON 解析：`{'<shortname>': {_id, emoji, usernames[], names?[]}}`；自己已回应判定 = `usernames.includes(username)`（**username 非 userId**）
  - UI：长按菜单入 picker + 行内反应条（点击 toggle）；**乐观更新**（本地 reactions 立即翻转，stream-room-messages 回推校正——T6 upsert 已被动持久化 reactions）
  - 已知差异入册：RN 无此功能（Android 领先，RN 端不可见）
- [ ] TDD：解析/toggle 判定/乐观更新与回推校正（MockWebSocket）
- [ ] Commit: `feat(chat): 表情回应（chat.react toggle + 乐观更新，Android 领先功能）`

---

### Task 9: 转发（单条 + 合并）

**Files:**
- Create: `core/network/api/ForwardApi.kt`、`feature/chat/forward/ForwardSelectScreen.kt`（3 tab：最近会话+双组织树，MAX 10）、`feature/chat/forward/ForwardMergeCard.kt`、`ForwardDetailScreen.kt`
- Test: `ForwardApiTest.kt`、forwardMergeMessage 解析单测

**Interfaces:**
- Produces:
  - 发送 wire：`POST chat.sendMessage` body `{forwardUsers?, forwardRooms?, forwardMessageIds, isForwardMessage: true, isForwardMerged}`——**不含 rid/msg，内容服务端组装**（对照 messages.ts:74-89）
  - 接收渲染：`msgType:'forwardMergeMessage'` + `msgData` JSON 解析（originRoom/messages）→ 卡片（标题「[xx,yy]的聊天记录」+ 前 2 条预览 + 查看聊天记录 → 详情页普通消息渲染）
  - 搜索：`spotlightv2`（REST 包 DDP call，参数对照 spotlight.ts:67-151）+ 300ms debounce
- [ ] TDD：wire 逐字段、msgData 解析、卡片数据组装、搜索参数
- [ ] Commit: `feat(chat): 转发单条与合并（forward 标志 + forwardMergeMessage 卡片）`

---

### Task 10: 已读回执 + 未读横幅

**Files:**
- Create: `feature/chat/ui/ReadReceiptScreen.kt`、`feature/chat/ui/UnreadBanner.kt`、`core/network/api/ReadReceiptsApi.kt`（getMessageReadReceipts/room.firsUnread——**拼写保留**）
- Test: API 单测

**Interfaces:**
- Produces:
  - 行内：仅自己消息渲染；`message.unread == false` → 蓝色已读图标；true → 可点图标（非 DM）进明细
  - 明细页：已读 tab = receipts（`{_id, userId, user:{username,name}}`）、未读 tab = `GET appia/room/members` 减已读者；只显头像+名字（ts 不渲染）
  - 未读横幅：`GET room.firsUnread` → `unreadCount >= 10` 显示（顶部横幅非列表分隔线）、点击跳转、滚过 firstUnread 消失（viewability）
- [ ] TDD：端点 wire/成员差集/阈值横幅
- [ ] Commit: `feat(chat): 已读回执明细与未读横幅（room.firsUnread 拼写保留）`

---

### Task 11: 消息长按菜单 + 回复引用 + 撤回

**Files:**
- Create: `feature/chat/ui/MessageActionsSheet.kt`、`feature/chat/MessageActionController.kt`、`core/network/api/RecallApi.kt`（message.recall / message.batch.recall / updateMessage DDP 等价）
- Test: `MessageActionControllerTest.kt`（canRecall 启发式全分支）、撤回快照/分组单测

**Interfaces:**
- Produces（对照 messageActions.tsx:129-245 全集）:
  - 动作判定：回复（恒有）/编辑（自己+允许+之后无他人消息+非会议）/复制/转发/多选/撤回（`canRecallMessage` 本地启发式 `{allowEditing:true, allowDeleting:true, editBlockMinutes:0, deleteBlockMinutes:0, noOtherUserMessagesAfter:true}` 硬编码对照 RN :420-430；只读房 `archived||ro` 拦截）/重发（ERROR 态）；待办/摘要留 M7 占位
  - **撤回语义**：先快照 `originalContent`（msg/md/attachments/files/mentions/tmid/tmsg/msgType）→ POST `message.recall` → DDP 回推 t='rollback-message'；连续同 rollbacker 分组渲染（「{{rollbacker}}撤回了{{count}}条」可展开）；自己撤回带快照 →「重新编辑」按钮（反序列化 → setContent 作为**新消息**发送，不进编辑模式）
  - **回复引用**：`composeQuotedMessageText` permalink+@mention 拼 msg 文本（M2 SendOrchestrator 已带 msg 通道）
  - **多选**：进入多选态（逐条/合并转发入口 → T9 ForwardSelect isMerged）+ 批量撤回
- [ ] TDD：判定全分支、快照序列化、分组渲染数据、引用文本构造
- [ ] Commit: `feat(chat): 长按菜单（回复/编辑/撤回快照/复制/转发/多选）`

---

### Task 12: 消息编辑 + 编辑器完整接线（spike 转正后）

**Files:**
- Modify: `feature/chat/editor/*`（spike 产线转正）、`feature/chat/ui/RoomScreen.kt`（输入区换 TenTapEditorBridge + ChatInputBarController）
- Create: `feature/chat/editor/ChatInputBarController.kt`（草稿 setContent isReady 门控+editorReadyCount、焦点单一调度 50ms debounce+generation、mention-trigger 链）、`feature/chat/ui/MentionSuggestionScreen.kt`（`GET appia/room/members/v2` 排序 + includes 过滤 + ALL_MEMBER 写死首项 + AI 房间 Agent_Bot_List 门控）、`core/messaging/UpdateMessageApi.kt`
- Test: 控制器单测 + Compose 链路

**Interfaces:**
- Produces:
  - 输入区：TenTapEditorBridge 完整化（异步 RPC getHTML/getJSON/getText + messageId 关联、注入配置构造器含 initialContent/editable/platform=android、bridge extendCSS、键盘 paddingBottom 联动）
  - @提及链：beforeinput mention-trigger（payload.payload 双层嵌套）→ 记 range → MentionSuggestion → DeviceEventEmitter 等价（共享 Flow）→ deleteRange + insertMention；工具栏 @ 直跳
  - 草稿：TipTap JSON 存 `draft_message`（纯文本 `draft_message_plain`）、isReady 门控回填、**effect 禁依赖 editor 引用**（M2 草稿覆盖 bug 的 RN 根因）
  - 编辑：`buildEditContent`（mdToTipTap + mentions 还原；无 md escapeHtml）→ setContent → 编辑横幅 ✕ 退出 → stash 草稿（`stashedEditingDraftRef` 等价）；发送**绕过 Orchestrator** 直 DDP `updateMessage {rid,_id,msg,md}`；附件编辑 `multiAttachments.replace`；"(edited)" 标记（editedBy 判定，md 追加行内 tag）
  - IME 预热坑（androidImePrime 等价：WebView requestFocus 拉不起 IME——FocusRequester/SoftwareKeyboardController 验证）
- [ ] 控制器/提及链/编辑回填单测 + Compose 链路 + 模拟器走查（含 spike 判据④ 复验）
- [ ] Commit: `feat(editor): 编辑器转正接线（提及链/草稿门控/编辑模式）`

---

### Task 13: 收口组装与回归

**Files:**
- Modify: RoomScreen/MessageRow 全量组装（渲染链+附件+回应+菜单+编辑入口互通）；`ChatListScreen` 预览的 md 分支升级（T2 近似 → 真实 AST 关键分支）
- Test: 全量回归 + 集成冒烟（发送富文本→渲染 round-trip）

**Interfaces:**
- Produces: 全功能 RoomScreen；列表预览 md 升级；**集成任务评审铁律执行——每条用户可见交互（发送富文本/发附件/回应/转发/撤回/重编辑/提及/已查明细）逐条给出可达性证据**
- [ ] 全量门禁 + 模拟器真网走查（M2 经验：真实会话数据）
- [ ] Commit: `feat(chat): M3 功能收口组装`

---

### Task 14: M3 收尾

- [ ] 门禁 3 次（flake 率）；Maestro 回归 + 边界评估
- [ ] 自测协议 M3 增补：富文本发送/渲染（代码块/表格/引用/列表/BIG_EMOJI/mention 色）、附件收发查看、转发单条合并、回应 toggle、已读回执、编辑/撤回/重新编辑、@提及——双端对照 + 已知差异（KaTeX 降级原式+点击查看；表情回应 Android 领先；图片无压缩跟随 RN；其余如实）
- [ ] versionName 0.4.0；README
- [ ] Commit + push: `chore(m3): 门禁冒烟与自测协议增补`

---

## 用户自测协议（M3 增补要点）

双端 side-by-side：富文本消息发送→双端渲染一致性（标题/列表/代码块/引用/表格/链接/BIG_EMOJI/mention 色）、图片/视频/文档收发与查看、上传进度、转发单条+合并、表情回应（RN 端不可见为预期）、已读回执明细、编辑（edited 标记）/撤回（分组+重新编辑）、@提及补全、长按菜单全集。已知差异见 Task 14。

## M3 完成定义

1. 14 任务全部 SDD 闭环（T2 spike go/no-go 决策留痕）
2. 全量单测 + lint 绿；Maestro 回归；模拟器真网走查含富文本与媒体
3. `.debug` APK + 自测协议 M3 增补交付
4. 用户完成双端验收后 M3 关闭

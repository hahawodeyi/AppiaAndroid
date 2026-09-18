package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.messaging.shortnameToUnicode
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.ReactionApi
import cn.appia.im.core.theme.LocalAppiaColors
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ── reactions JSON 解析（brief 绑定裁定 2；绿地功能，RN 无实现）──

/**
 * 服务端 reactions JSON 的解析模型（形状 `{'<shortname>': {_id, emoji, usernames[], names?[]}}`）。
 * - [shortname] = map key（如 `:tada:`），服务端 toggle 身份（legacy normalizeMessage `emoji: key` 同口径）
 * - [emoji] = 内层 emoji 字段（缺失回退 key；本 UI 以 shortname 为准，字段按 wire 形状保真携带）
 * - [usernames]/[names] = 回应人列表（缺失 → 空/null）
 */
internal data class Reaction(
    val shortname: String,
    val emoji: String,
    val usernames: List<String>,
    val names: List<String>? = null,
) {
    /**
     * 自己已回应判定：`usernames.includes(username)`——**username 非 userId**
     * （legacy containers/message/Reactions.tsx:51 `item === user.username` 同口径）。
     */
    fun isMine(username: String?): Boolean = username != null && username in usernames
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.strList(key: String): List<String>? =
    (this[key] as? JsonArray)?.mapNotNull { p -> (p as? JsonPrimitive)?.takeIf { it.isString }?.content }

/**
 * reactions JSON → [Reaction] 列表（map key 顺序保持）；空/坏 JSON/非对象 → 空表（brief 绑定裁定）。
 * 内层未知字段有意丢弃：渲染只需 shortname/emoji/usernames/names，翻转后的过渡态由
 * `stream-room-messages` 回推以服务端真值覆盖（T6 upsert 被动持久化）。
 */
internal fun parseReactions(json: String?): List<Reaction> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val obj = Json.parseToJsonElement(json) as? JsonObject ?: return emptyList()
        obj.map { (key, value) ->
            val r = value as? JsonObject
            Reaction(
                shortname = key,
                emoji = r?.str("emoji") ?: key,
                usernames = r?.strList("usernames") ?: emptyList(),
                names = r?.strList("names"),
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * 乐观 toggle 翻转（chat.react 同语义本地镜像）：[emoji]（shortname）已有回应时
 * 自己在列则摘除（摘后无人 → 整项删除；全空 → 返回 null 清列），否则追加；
 * 无回应则新建（形状对齐服务端：`_id = messageId+key`，legacy normalizeMessage 同构）。
 * 仅本地过渡态，服务端真值经回推覆盖。
 */
internal fun toggleReactionJson(
    json: String?,
    messageId: String,
    emoji: String,
    username: String,
): String? {
    val byKey = LinkedHashMap<String, Reaction>(parseReactions(json).associateBy { it.shortname })
    val existing = byKey[emoji]
    val next: Reaction? = when {
        existing == null -> Reaction(shortname = emoji, emoji = emoji, usernames = listOf(username))
        username in existing.usernames -> (existing.usernames - username)
            .takeIf { rest -> rest.isNotEmpty() }?.let { rest -> existing.copy(usernames = rest) }
        else -> existing.copy(usernames = existing.usernames + username)
    }
    if (next == null) byKey.remove(emoji) else byKey[emoji] = next
    if (byKey.isEmpty()) return null
    return buildJsonObject {
        byKey.values.forEach { r ->
            put(r.shortname, buildJsonObject {
                put("_id", "$messageId${r.shortname}")
                put("emoji", r.emoji)
                put("usernames", JsonArray(r.usernames.map { JsonPrimitive(it) }))
                r.names?.let { ns -> put("names", JsonArray(ns.map { n -> JsonPrimitive(n) })) }
            })
        }
    }.toString()
}

// ── 行内反应条 + 常用 emoji picker（brief 绑定裁定 3）──

/** 常用回应 shortname 集（12 个，含 brief 例举 👍👎😀❤️🚀🎉😭😄🤔；legacy 全量 emoji picker 的合理选集）。 */
internal val REACTION_PICKER_EMOJIS = listOf(
    ":thumbsup:", ":thumbsdown:", ":grinning:", ":smile:", ":joy:", ":heart:",
    ":rocket:", ":tada:", ":sob:", ":thinking_face:", ":clap:", ":fire:",
)

/**
 * 常用 emoji picker（legacy ReactionPicker 全量+搜索的绿地简化：固定 12 格；显示经
 * shortnameToUnicode 渲染、发送 shortname——与 chat.react wire 一致，无需反向映射）。
 */
@Composable
fun ReactionPicker(onSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(LocalAppiaColors.current.chatComponentBackground)
            .padding(8.dp)
            .testTag("qa-reaction-picker"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        REACTION_PICKER_EMOJIS.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { shortname ->
                    Text(
                        shortnameToUnicode(shortname),
                        fontSize = 22.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onSelected(shortname) }
                            .padding(4.dp)
                            .testTag("qa-reaction-pick-$shortname"),
                    )
                }
            }
        }
    }
}

/**
 * 行内反应条（legacy containers/message/Reactions.tsx 落法）：每个已有回应一枚按钮——
 * emoji + 计数，自己已回应高亮（bannerBackground 底 + tintColor 描边），点击 toggle；
 * 末位 ＋ 入口开常用 picker（legacy AddReaction 注释态的复活；T11 长按菜单为另一入口）。
 * 空/坏 reactions 渲染为空。长按菜单入口 T11 接（brief 绑定裁定 3）。
 */
@Composable
fun ReactionBar(
    reactionsJson: String?,
    currentUsername: String?,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppiaColors.current
    val reactions = remember(reactionsJson) { parseReactions(reactionsJson) }
    if (reactions.isEmpty()) return
    var pickerOpen by remember { mutableStateOf(false) }

    Row(
        modifier
            .padding(top = 4.dp)
            .testTag("qa-reaction-bar"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        reactions.forEach { reaction ->
            val mine = reaction.isMine(currentUsername)
            Row(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (mine) colors.bannerBackground else colors.backgroundColor)
                    .border(
                        1.dp,
                        if (mine) colors.tintColor else colors.borderColor,
                        RoundedCornerShape(4.dp),
                    )
                    .clickable { onToggle(reaction.shortname) }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("qa-reaction-chip-${reaction.shortname}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(shortnameToUnicode(reaction.shortname), fontSize = 14.sp) // miss 回退 ':code:' 文本（RN 同口径）
                Text(" ${reaction.usernames.size}", color = colors.tintColor, fontSize = 12.sp)
            }
        }
        Text(
            "＋",
            color = colors.tintColor,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { pickerOpen = true }
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .testTag("qa-reaction-add"),
        )
        DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
            ReactionPicker(onSelected = { emoji ->
                pickerOpen = false
                onToggle(emoji)
            })
        }
    }
}

// ── 乐观更新编排（ChatRowActions.kt 同款「动作类放 UI 文件」先例）──

/**
 * 表情回应 toggle（T8 乐观更新纪律，brief 绑定裁定 4）：
 * 1. 本地 reactions 列立即翻转（[toggleReactionJson]，MessageDao.updateReactions 单列写，
 *    不冲 DDP 回推全行 upsert 的其他列——M2「status 零接触」同级纪律）
 * 2. `POST chat.react`（[ReactionApi.react]，toggle 语义在服务端）
 * 3. POST 失败 → 回滚翻转前原值；成功 → 无本地写，等 `stream-room-messages` 回推
 *    （MessageUpsert upsert）覆盖为服务端真值
 *
 * **已知竞态（RN/legacy 同款，可接受）**：乐观窗口内 DDP 回推先到会以服务端真值覆盖乐观态
 * （他人视图即时为真）；本乐观写与回推交错最迟由 POST 后的下一次回推校正。
 *
 * 绿地差异标注（RN 无此功能，Android 领先、RN 端不可见）：本类与 [ReactionBar] 均无 RN 对照行号，
 * 行为参照 legacy appiaim-ios（restApi.ts:383-385 / Reactions.tsx:51 / RoomView onReactionPress）。
 */
class ReactionActions(
    private val sdk: RocketSdk,
    private val db: AppiaDatabase,
) {

    /**
     * @param messageId 消息行 `_id`
     * @param emoji shortname（如 `:tada:`；picker/反应条均为 shortname，wire 直传）
     * @param username 当前会话 **username**（非 userId；isMine 判定口径）——缺失 no-op 不发请求
     */
    suspend fun toggle(messageId: String, emoji: String, username: String?) {
        if (username.isNullOrBlank()) return
        val row = db.messageDao().getById(messageId) ?: return
        val previous = row.reactions
        db.messageDao().updateReactions(messageId, toggleReactionJson(previous, messageId, emoji, username))
        try {
            ReactionApi.react(sdk, emoji, messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            db.messageDao().updateReactions(messageId, previous) // 失败回滚（previous 为 null 即清列）
            throw e
        }
    }
}

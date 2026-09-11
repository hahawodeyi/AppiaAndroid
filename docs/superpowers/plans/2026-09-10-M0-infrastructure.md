# M0 基础设施 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立可构建、可安装、协议层单测通过的 Android 原生工程骨架，为 M1 起的全部功能里程碑打底。

**Architecture:** 单 `:app` module + 包分层（core/domain/feature）。协议层（REST/DDP）从 appiaMobile 的 TS 实现逐行移植并用 JUnit 覆盖；Room schema 对照 WatermelonDB v6；主题 tokens 与 i18n 资源从 RN 侧脚本化搬迁。

**Tech Stack:** Kotlin 2.x、Compose BOM、Hilt、Retrofit/OkHttp、Room、MMKV、kotlinx.serialization、Coil、JUnit5 + MockWebServer + OkHttp MockWebServer（WebSocket）。

**Spec:** `docs/superpowers/plans/REWRITE_MASTER_PLAN.md`（总纲：全局约束、架构分层、里程碑路线图）

## Global Constraints

- applicationId `cn.appia.im`；namespace `cn.appia.im`；minSdk 24、targetSdk/compileSdk 36
- 所有依赖版本在根 `gradle/libs.versions.toml` 统一管理（version catalog）
- 服务端 JSON 字段只增不减：`Json { ignoreUnknownKeys = true }` 全局单例
- 提交信息中文 `类型(范围): 描述`
- i18n key 保持 RN 原样（`{模块}_{含义}`），脚本搬迁，不手工重命名
- 代码中禁止硬编码中文文案（注释除外），pre-commit 检查
- 每个任务完成 = 测试通过 + 提交

---

### Task 1: Gradle 工程骨架 + git init

**Files:**
- Create: `settings.gradle.kts`、`build.gradle.kts`、`gradle/libs.versions.toml`、`gradle.properties`、`gradle/wrapper/*`、`app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`、`app/src/main/kotlin/cn/appia/im/AppiaApplication.kt`、`app/src/main/kotlin/cn/appia/im/MainActivity.kt`、`app/src/main/res/values/themes.xml`、`.gitignore`、`.pre-commit-check-chinese.sh`

**Interfaces:**
- Produces: 可构建的空壳 app；`cn.appia.im.AppiaApplication`（Hilt 入口 `@HiltAndroidApp`）、`cn.appia.im.MainActivity`（`@AndroidEntryPoint`，setContent 渲染占位 Text）；version catalog 别名供后续任务引用（`libs.hilt`、`libs.retrofit` 等）

- [ ] **Step 1: 生成 Gradle wrapper（8.x）与工程文件**

`gradle/libs.versions.toml` 核心版本：

```toml
[versions]
agp = "8.13.0"
kotlin = "2.2.20"
compose-bom = "2026.09.00"
hilt = "2.57"
retrofit = "3.0.0"
okhttp = "5.3.0"
room = "2.8.0"
mmkv = "2.2.1"
kotlinx-serialization = "1.9.0"
coil = "3.3.0"
coroutines = "1.10.2"
junit5 = "5.13.4"
mockwebserver = "5.3.0"
hilt-navigation = "1.3.0"
navigation-compose = "2.9.5"
activity-compose = "1.11.0"
lifecycle = "2.9.4"
core-ktx = "1.17.0"
datastore = "1.1.7"
```

`app/build.gradle.kts` 关键配置（Release 沿用 RN 的 release.properties 机制，本任务只建结构）：

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "cn.appia.im"
    defaultConfig {
        applicationId = "cn.appia.im"
        minSdk = 24
        targetSdk = 36
        compileSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    buildTypes {
        release {
            // 照搬 RN 机制：android/release.properties 提供 KEYSTORE/VERSION_*，文件不存在时仅警告（debug 开发不受阻）
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose) // ui, material3, foundation, navigation, hilt-navigation
    implementation(libs.bundles.network)  // retrofit, okhttp, logging-interceptor, kotlinx-serialization-converter
    implementation(libs.bundles.room)     // room-runtime, room-ktx, room-compiler(ksp)
    implementation(libs.mmkv)
    implementation(libs.coil.compose)
    testImplementation(libs.bundles.test)  // junit5, coroutines-test, mockwebserver, turbine
}
```

`MainActivity.kt`：

```kotlin
package cn.appia.im

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Text(text = "Appia") // 占位，M2 起替换为 RootNavHost
            }
        }
    }
}
```

`.gitignore`：`.gradle/`、`build/`、`local.properties`、`.idea/`、`*.iml`、`.DS_Store`、`android/release.local.properties`。

- [ ] **Step 2: 验证构建与安装**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

Run: `adb install app/build/outputs/apk/debug/app-debug.apk` + `adb shell am start -n cn.appia.im/.MainActivity`
Expected: 应用启动显示占位文本

- [ ] **Step 3: git init + 首次提交 + 连接 GitHub**

```bash
git init -b main
git add -A
git commit -m "chore(project): 初始化 Gradle 工程骨架（Kotlin + Compose + Hilt）"
git remote add origin git@github.com:hahawodeyi/AppiaAndroid.git
git push -u origin main
```

注：push 前确认 `git status` 无 `release.local.properties` 等敏感文件。

---

### Task 2: 主题 tokens 搬迁（colors.ts → Compose）

**Files:**
- Create: `app/src/main/kotlin/cn/appia/im/core/theme/AppiaColors.kt`、`app/src/main/kotlin/cn/appia/im/core/theme/Theme.kt`、`app/src/main/kotlin/cn/appia/im/core/theme/Dimens.kt`
- Test: `app/src/test/kotlin/cn/appia/im/core/theme/AppiaColorsTest.kt`

**Interfaces:**
- Produces: `AppiaTheme(isDark: Boolean, content: @Composable () -> Unit)`；`LocalAppiaColors`（`primary`、`backgroundColor`、`titleText`、`bodyText`、`dangerColor`、`separatorColor` 等全量字段，命名与 RN `colors.ts` 一致）；`AppiaDimens`（对照 RN `tokens.ts` 的间距/圆角）

- [ ] **Step 1: 写失败测试**

```kotlin
package cn.appia.im.core.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppiaColorsTest {
    @Test
    fun `light palette matches RN colors ts`() {
        assertEquals("#2878FF", AppiaColors.light.primary.hexValue())
        assertEquals("#ffffff", AppiaColors.light.backgroundColor.hexValue())
        assertEquals("#0d0e12", AppiaColors.light.titleText.hexValue())
        assertEquals("#f5455c", AppiaColors.light.dangerColor.hexValue())
        assertEquals("#2de0a5", AppiaColors.status.online.hexValue()) // STATUS_COLORS
    }

    @Test
    fun `dark palette has same field set as light`() {
        assertEquals(
            AppiaColors.light.javaClass.declaredFields.map { it.name }.toSet(),
            AppiaColors.dark.javaClass.declaredFields.map { it.name }.toSet(),
        )
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.appia.im.core.theme.AppiaColorsTest"`
Expected: FAIL（AppiaColors 未定义）

- [ ] **Step 3: 实现——从 RN colors.ts 逐字段搬迁**

读取 `/Users/bitmain/Projects/rebuild-mobile/appiaMobile/src/theme/colors.ts` 全文，把 `colors.light`、`colors.dark`、`STATUS_COLORS`、`SWITCH_TRACK_COLOR`、mentions 五组值逐个转录为 Kotlin object（Color 常量）。示例结构：

```kotlin
package cn.appia.im.core.theme

import androidx.compose.ui.graphics.Color

object AppiaColors {
    val light = Palette(
        primary = Color(0xFF2878FF),
        backgroundColor = Color(0xFFFFFFFF),
        // …其余字段全部从 colors.ts 转录，字段名保持 RN 命名
    )
    val dark = Palette(/* colors.dark 全量 */)
    val status = StatusColors(online = Color(0xFF2DE0A5), /* … */)
}
```

`Dimens.kt` 同法从 `src/theme/tokens.ts` 转录。

- [ ] **Step 4: 运行测试通过 + 提交**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.appia.im.core.theme.AppiaColorsTest"`
Expected: PASS

```bash
git add app/src/main/kotlin/cn/appia/im/core/theme/ app/src/test/kotlin/cn/appia/im/core/theme/
git commit -m "feat(theme): 搬迁 RN 主题 tokens（colors/tokens 对齐 appiaMobile）"
```

---

### Task 3: i18n 资源搬迁（zh/en.json → strings）

**Files:**
- Create: `scripts/migrate_i18n.py`、`app/src/main/kotlin/cn/appia/im/core/i18n/I18n.kt`、`app/src/main/res/values/strings.xml`（en）、`app/src/main/res/values-zh/strings.xml`
- Test: `app/src/test/kotlin/cn/appia/im/core/i18n/I18nTest.kt`

**Interfaces:**
- Consumes: `/Users/bitmain/Projects/rebuild-mobile/appiaMobile/src/i18n/zh.json` 与 `en.json`（29KB×2，约 800+ key）
- Produces: `fun t(key: String, vararg args: Any): String`（Compose 环境经 `CompositionLocal` 取 Context，单测直接传 Context）；资源名转义规则：key `roomInfo_moreMembers` → `roominfo_moremembers`（Android 资源名强制小写，`t()` 内做 `key.lowercase()` 转换——key 本身大小写不敏感即可安全）

- [ ] **Step 1: 编写迁移脚本并执行**

`scripts/migrate_i18n.py`：读入 JSON → 递归展平（若有嵌套，用 `_` 连接）→ 输出两个 `strings.xml`（`%s`/`%d` 占位符原样保留）→ 同步生成 `I18nKeys.kt` 常量列表供测试比对数量。

```bash
python3 scripts/migrate_i18n.py \
  --source /Users/bitmain/Projects/rebuild-mobile/appiaMobile/src/i18n \
  --out app/src/main/res
```

- [ ] **Step 2: 写失败测试**

```kotlin
package cn.appia.im.core.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class I18nTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `key count matches RN json`() {
        // I18nKeys.ALL 由迁移脚本生成；断言数量 > 800，防止脚本漏搬
        assertTrue(I18nKeys.ALL.size > 800)
    }

    @Test
    fun `lookup is case-insensitive on module prefix`() {
        assertEquals(context.t("roominfo_moremembers"), context.t("roomInfo_moreMembers"))
    }
}
```

注：`ApplicationProvider` 需要 `androidx.test:core` 依赖（Robolectric）。本任务在 `libs.versions.toml` 的 test bundle 中追加 `robolectric` 与 `androidx-test-core`。

- [ ] **Step 3: 实现 I18n.kt**

```kotlin
package cn.appia.im.core.i18n

import android.content.Context

fun Context.t(key: String, vararg args: Any): String {
    val resId = resources.getIdentifier(key.lowercase(), "string", packageName)
    if (resId == 0) return key // 缺失回退：显示 key 本身（与 RN t() 行为一致）
    return getString(resId, *args)
}
```

- [ ] **Step 4: 测试通过 + pre-commit 中文检查脚本接入**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.appia.im.core.i18n.I18nTest"`
Expected: PASS

`.pre-commit-check-chinese.sh`（对照 appiaMobile `.husky/check_chinese.rb` 行为：检查暂存区 `.kt` 文件的代码区（排除 `//` 与 `/* */` 注释）中无中文字符）：

```bash
#!/bin/bash
# 检查暂存的 .kt 文件代码区是否含中文（注释除外）
set -e
FILES=$(git diff --cached --name-only --diff-filter=ACM | grep '\.kt$' || true)
[ -z "$FILES" ] && exit 0
if grep -nP '[\x{4e00}-\x{9fff}]' $FILES | grep -vP '^\s*[^:]+:\d+:\s*(//|/\*|\*)' ; then
  echo "错误：代码中检测到硬编码中文，请使用 i18n 资源" >&2
  exit 1
fi
exit 0
```

```bash
chmod +x .pre-commit-check-chinese.sh
git add scripts/ app/src/main/res/values*/ app/src/main/kotlin/cn/appia/im/core/i18n/ app/src/test/kotlin/cn/appia/im/core/i18n/ .pre-commit-check-chinese.sh
git commit -m "feat(i18n): 脚本化搬迁 zh-en 文案资源并接入中文检查"
```

---

### Task 4: DDP 客户端移植（ddpClient.ts → Kotlin）

**Files:**
- Create: `app/src/main/kotlin/cn/appia/im/core/network/ddp/DdpClient.kt`、`.../ddp/DdpMessage.kt`、`.../ddp/DdpEventListener.kt`
- Test: `app/src/test/kotlin/cn/appia/im/core/network/ddp/DdpClientTest.kt`

**Interfaces:**
- Consumes: OkHttp WebSocket（`OkHttpClient.newWebSocket`）
- Produces:
  - `class DdpClient(options: DdpOptions, client: OkHttpClient)` — 字段 `userId: String?`，方法：
    - `suspend fun connect()` — WS 建连 + 发 `{msg:"connect", version:"1", support:["1","pre2","pre1"]}`，15s 超时
    - `suspend fun loginWithResume(token: String): DdpLoginResult` — `{msg:"method", method:"login", params:[{resume: token}]}`
    - `suspend fun callMethod(method: String, vararg params: JsonElement): JsonElement`
    - `suspend fun subscribe(topic: String, eventName: String, vararg args: JsonElement): DdpSubscription` — params=`[eventName, {useCollection:false, args}]`，等 `ready` ack（25s 超时）
    - `fun onStreamData(event: String, handler: (JsonElement) -> Unit): Disposable`
    - `fun disconnect()` — 主动断开（close code 4000），清 userId
  - `data class DdpOptions(host: String, reopenMs: Long = 5000, pingMs: Long = 20000, connectTimeoutMs: Long = 15000, responseTimeoutMs: Long = 15000)`
  - 事件分发规则（对齐 TS `handleMessage`）：按 `msg`、`collection`、`id` 三个键各发一路；收到 `{msg:"ping"}` 自动回 `pong`

- [ ] **Step 1: 写失败测试（MockWebServer WebSocket）**

测试文件骨架（覆盖：connect 握手、resume 登录、订阅 ready/nosub、ping-pong 自动应答、事件三路分发、断线重连、响应超时）：

```kotlin
package cn.appia.im.core.network.ddp

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DdpClientTest {
    private val server = MockWebServer()

    private fun withServer(block: (url: String) -> Unit) {
        server.start()
        server.enqueue(MockResponse().withWebSocketUpgrade(EchoWsListener()))
        block(server.url("/websocket").toString())
        server.shutdown()
    }

    @Test
    fun `connect sends DDP connect with version support`() = runTest {
        withServer { url ->
            val client = DdpClient(DdpOptions(host = url))
            client.connect()
            val sent = server.takeRequest() // WS upgrade 后首帧
            // 断言发送的 JSON：msg=connect, version="1", support 含 pre2
        }
    }

    @Test
    fun `auto replies pong to server ping`() = runTest { /* … */ }

    @Test
    fun `subscribe resolves on ready and emits stream events`() = runTest { /* … */ }

    @Test
    fun `nosub rejects subscribe`() = runTest { /* … */ }

    @Test
    fun `loginWithResume stores userId`() = runTest { /* … */ }
}
```

`EchoWsListener` 为测试辅助：记录客户端发来的帧、按脚本回帧（`connected`、`ping`、`ready`、`nosub`、stream 事件）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.appia.im.core.network.ddp.DdpClientTest"`
Expected: FAIL（DdpClient 未定义）

- [ ] **Step 3: 逐行移植实现**

对照 `appiaMobile/src/services/realtime/ddpClient.ts`（536 行）实现 Kotlin 版。关键移植点：

- `sentSeq` 自增 id（`ddp-${n}` / `sub-${n}`）；`connect`/`ping`/`pong` 三种消息不带 id
- 心跳：每 `pingMs`(20s) 发 `{msg:"ping"}`，等 `pong` 后再排下一轮；发送失败 → disconnect + tryReopen
- 断线：`onFailure`/`onClosed` → 清状态 → `reopenMs`(5s) 后重连
- `sendRaw` 的响应等待：按 expectedEvent（`connected`/`pong`/自增 id）注册一次性监听 + `responseTimeoutMs` 超时
- Emitter 用 `MutableSharedFlow` + 按 event key 的 `ConcurrentHashMap<String, Channel<JsonElement>>` 或等价回调注册表实现（保持 `on/off/once` 语义）

```kotlin
package cn.appia.im.core.network.ddp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*

class DdpClient(
    private val options: DdpOptions,
    private val client: OkHttpClient = OkHttpClient(),
) {
    var userId: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = mutableMapOf<String, MutableSet<(JsonElement) -> Unit>>()
    // … 完整实现对照 ddpClient.ts 逐函数移植
}
```

- [ ] **Step 4: 全部测试通过 + 提交**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.appia.im.core.network.ddp.DdpClientTest"`
Expected: PASS（7+ 用例）

```bash
git add app/src/main/kotlin/cn/appia/im/core/network/ddp/ app/src/test/kotlin/cn/appia/im/core/network/ddp/
git commit -m "feat(ddp): 移植 DDP WebSocket 客户端（对照 ddpClient.ts 逐行为对齐）"
```

---

### Task 5: REST 层（restClient → Retrofit + 401 登出）

**Files:**
- Create: `app/src/main/kotlin/cn/appia/im/core/network/rest/RetrofitFactory.kt`、`.../rest/AuthInterceptor.kt`、`.../rest/ApiError.kt`、`.../rest/RocketApi.kt`
- Test: `app/src/test/kotlin/cn/appia/im/core/network/rest/AuthInterceptorTest.kt`

**Interfaces:**
- Consumes: Task 4 的 DDP client（M1 起共构 RocketChatSdk 单例）
- Produces:
  - `RetrofitFactory.create(host: String, authProvider: () -> AuthSession?): Retrofit` — base URL `{host}/api/v1/`
  - `AuthInterceptor` — 已登录时注入 `X-Auth-Token`/`X-User-Id`
  - `class AuthSessionExpiredException : Exception`（对应 TS `AUTH_SESSION_EXPIRED_ERROR`）+ OkHttp `Authenticator`：401 且**非组织切换中**时触发全局登出事件（`SessionExpiredBus`，SharedFlow，M1 接 authStore 等价物）
  - `RocketApi` 接口：M0 先定义登录与会话恢复所需端点（对照 `services/api/auth.ts`），M1+ 按需追加：
    ```kotlin
    interface RocketApi {
        @POST("login")
        suspend fun login(@Body body: LoginRequest): LoginResponse
        @GET("info")
        suspend fun serverInfo(): ServerInfoResponse
    }
    ```

- [ ] **Step 1: 写失败测试**

```kotlin
package cn.appia.im.core.network.rest

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AuthInterceptorTest {
    private val server = MockWebServer()

    @Test
    fun `injects auth headers when session present`() {
        server.enqueue(MockResponse().setBody("{}"))
        val retrofit = RetrofitFactory.create(server.url("/").toString()) {
            AuthSession(token = "t-1", userId = "u-1")
        }
        retrofit.create(RocketApi::class.java)
        // 发一个请求后断言 recordedRequest.getHeader("X-Auth-Token") == "t-1"
    }

    @Test
    fun `401 emits session expired`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        // 断言 SessionExpiredBus 收到事件且不发生在 orgSwitch 期间
    }
}
```

- [ ] **Step 2: 运行确认失败 → Step 3: 实现 → Step 4: 测试通过 + 提交**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.appia.im.core.network.rest.AuthInterceptorTest"`
Expected: PASS

```bash
git add app/src/main/kotlin/cn/appia/im/core/network/rest/ app/src/test/kotlin/cn/appia/im/core/network/rest/
git commit -m "feat(rest): REST 客户端骨架（Retrofit + 鉴权注入 + 401 会话过期总线）"
```

---

### Task 6: Room schema（对照 WatermelonDB v6 九表）

**Files:**
- Create: `app/src/main/kotlin/cn/appia/im/core/database/AppiaDatabase.kt`、`.../database/entity/*.kt`（9 个）、`.../database/dao/*.kt`、`.../database/DatabaseManager.kt`
- Test: `app/src/test/kotlin/cn/appia/im/core/database/SchemaParityTest.kt`

**Interfaces:**
- Consumes: `/Users/bitmain/Projects/rebuild-mobile/appiaMobile/src/database/schema.ts`（v6，9 表）
- Produces:
  - `@Database(entities = [...9 个...], version = 1)` —— **注意：Android 侧版本号从 1 重新计数，不等于 WatermelonDB 的 6**
  - 实体：`ChatEntity`、`SubscriptionEntity`、`RoomEntity`、`MessageEntity`、`ThreadEntity`、`UserEntity`、`SettingEntity`、`UploadEntity`、`CustomEmojiEntity`，**列名保持 snake_case 原名**（`_id`、`fname`、`tunread`、`room_updated_at`…），复杂结构（roles/uids/usernames JSON 数组）存 TEXT + `TypeConverter`（kotlinx.serialization 编解码）
  - `DatabaseManager`：`fun databaseFor(normalizedServer: String): AppiaDatabase` —— db 文件名 = `appia_<normalizedServer>`（未登录 `__prelogin__`），缓存实例；`fun switchDatabase(server: String)`；`fun resetAll()`（登出清理）

- [ ] **Step 1: 写失败测试**

```kotlin
package cn.appia.im.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SchemaParityTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AppiaDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Test
    fun `all nine tables exist with RN column names`() {
        val tables = db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='table'"
        ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }
        // 断言含 chats/subscriptions/rooms/messages/threads/users/settings/uploads/custom_emojis
        assertTrue("subscriptions" in tables)
        // 抽查 subscriptions 列名（对照 schema.ts subscriptionColumns）
        val cols = db.openHelper.readableDatabase.query("PRAGMA table_info(subscriptions)")
            .use { c -> generateSequence { if (c.moveToNext()) c.getString(1) else null }.toSet() }
        listOf("_id","f","t","name","fname","rid","open","alert","unread","user_mentions","group_mentions").forEach {
            assertTrue(it in cols, "missing column $it")
        }
    }
}
```

- [ ] **Step 2: 运行确认失败 → Step 3: 实现（9 个 entity 逐表转录 schema.ts 列） → Step 4: 测试通过 + 提交**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.appia.im.core.database.SchemaParityTest"`
Expected: PASS

```bash
git add app/src/main/kotlin/cn/appia/im/core/database/ app/src/test/kotlin/cn/appia/im/core/database/
git commit -m "feat(database): Room schema 对照 WatermelonDB v6 九表（列名保持 RN 原名）"
```

---

### Task 7: MMKV 封装 + Maestro 冒烟 + M0 收尾

**Files:**
- Create: `app/src/main/kotlin/cn/appia/im/core/datastore/KvStore.kt`、`maestro/launch_android.yaml`
- Test: `app/src/test/kotlin/cn/appia/im/core/datastore/KvStoreTest.kt`

**Interfaces:**
- Produces: `KvStore`（MMKV 包装：`fun putString/getString/putBoolean/...`，Hilt 单例，`SharedPreferences` 接口风格）；Maestro YAML（复用 appiaMobile `maestro/launch_android.yaml` 内容，包名相同无需改）

- [ ] **Step 1: KvStore 实现 + 单测**（MMKV 需初始化 `MMKV.initialize(context)`——单测中用 Robolectric；Hilt module 提供 `@Singleton KvStore`）

- [ ] **Step 2: 全量门禁**

Run: `./gradlew :app:testDebugUnitTest :app:lintDebug`
Expected: 全部 PASS

- [ ] **Step 3: Maestro 冒烟（需模拟器/真机）**

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
maestro test maestro/launch_android.yaml
```

Expected: 启动流程 PASS

- [ ] **Step 4: 提交 + 推送**

```bash
git add -A
git commit -m "chore(m0): MMKV 封装与 Maestro 冒烟，M0 基础设施完成"
git push
```

---

## M0 完成定义

1. `./gradlew :app:assembleDebug` 成功，app 可安装启动
2. 协议层单测（DDP 7+ 用例、REST 2+ 用例、Schema 九表、i18n key 数量）全绿
3. `git push` 到 `github.com/hahawodeyi/AppiaAndroid` main 分支
4. Maestro `launch_android.yaml` 通过（有设备时）

M0 完成后：产出 M1（认证与多组织）详细计划文档。

# Appia 原生重写 M1 用户自测协议（版本 0.2.0）

> 本文是面向自测人的操作手册。原生版（`cn.appia.im.debug`，versionName **0.2.0**）已实现 M1 全部链路：企业码验证 → 服务器列表 → SMS 登录 → 会话持久化 → 占位主屏 → 组织切换 → 登出/会话失效。
> 自测方法：**与 RN 版（appiaMobile）side-by-side 对照**——同一操作在两个版本各做一遍，比较表现差异。

## 一、安装步骤

APK 由开发侧交付（也可自建：仓库根目录执行 `./gradlew :app:assembleDebug`，产物在 `app/build/outputs/apk/debug/app-debug.apk`）。

```bash
# -r = 已安装时覆盖升级，保留本地数据
adb install -r app-debug.apk
```

启动：桌面点开 **Appia** 图标（debug 版）。确认版本号 0.2.0：企业码页底部页脚常驻版本行，应显示 `0.2.0`。

**与生产版共存**：debug 包名为 `cn.appia.im.debug`（生产版为 `cn.appia.im`），二者可同时安装在同一台设备，数据完全隔离，互不影响。注意 debug 版**不会**覆盖 RN/生产版的数据，需用真实账号重新登录一次。

前置条件：设备可访问真实验证环境（企业码 verify 接口走 `https://appia.cn`）。

## 二、自测清单

逐项勾选。每项标注「RN 对照点」——即与 RN 版比较时应关注什么。

### 1. 企业码验证（真码）

- [ ] 输入真实企业码 → 点「验证激活」→ 跳转登录页（短信模式）
- [ ] 输入不存在的企业码 → 弹「企业验证失败」Alert，点「关闭」可关闭并留在本页
- [ ] 不输入直接点「验证激活」→ 弹「信息不完整 / 请输入企业识别码。」

**RN 对照点**：跳转时机（RN `navigation.replace`）、Alert 标题/正文文案、loading 期间按钮禁用表现。

### 2. SMS 登录（发码 / 滑块 / 验证码）

- [ ] 输入手机号 → 点「获取验证码」→ 弹「安全验证」滑块，拖动通过 → 手机收到短信验证码
- [ ] 滑块未完成时点「获取验证码」→ 提示需先完成滑块
- [ ] 输入收到的验证码 → 点「登录」→ 进入主屏
- [ ] 输错验证码 → 失败提示（文案与 RN 比对）
- [ ] （可选）CAS SSO 入口：点「SSO 登录」→ WebView → 登录成功进主屏

**RN 对照点**：滑块弹层形态（底部弹层 vs 居中）、倒计时秒数与「重新发送」文案、验证码位数、错误文案。

### 3. 登录后占位主屏（Main）

- [ ] 主屏顶部大字显示姓名（无姓名时显示用户名），下方「用户名」字段显示登录用户名
- [ ] 「服务器」字段显示登录服务器 URL
- [ ] 连接状态文本显示「在线」（离线时「离线」）

**RN 对照点**：RN 版此处已是完整房间列表；M1 原生版是占位屏（仅主体信息），此项**不是缺陷**，见「已知差异」第 6 条引言。

### 4. 房间数据落库核对

登录成功后，校验房间数据已写入本地数据库（8 表：`rooms` / `subscriptions` / `chats` / `messages` / `users` / `settings` / `uploads` / `custom_emojis`）。

```bash
# 1) 列出该应用的数据库文件（登录后应出现 appia_<服务器标识>.db）
adb shell run-as cn.appia.im.debug ls databases/
# 期望看到 appia_prelogin.db（未登录占位库）和 appia_<normalizedServer>.db

# 2) 把库拉到本地（Room 用 WAL，三个文件一起拉，拉之前先杀掉 App 保证落盘）
adb shell am force-stop cn.appia.im.debug
adb exec-out run-as cn.appia.im.debug cat databases/appia_<normalizedServer>.db        > /tmp/appia.db
adb exec-out run-as cn.appia.im.debug cat databases/appia_<normalizedServer>.db-wal    > /tmp/appia.db-wal
adb exec-out run-as cn.appia.im.debug cat databases/appia_<normalizedServer>.db-shm    > /tmp/appia.db-shm

# 3) 本机用 sqlite3 查
sqlite3 /tmp/appia.db "SELECT count(*) FROM rooms;"
sqlite3 /tmp/appia.db "SELECT _id, substr(custom_fields,1,80) FROM rooms LIMIT 5;"
```

（如果本机没有 sqlite3，也可直接看文件体积：登录后 `appia_<server>.db` 应显著大于 `appia_prelogin.db`，即为有数据写入。）

- [ ] 登录后 `databases/` 出现 `appia_<服务器标识>.db`
- [ ] `rooms` 表条数 > 0，且与 RN 版同账号的房间数量一致

**RN 对照点**：RN 版用 WatermelonDB（同名 8 表）；重点比对 `rooms` 条数与 `subscriptions` 条数是否一致（同一账号、同一服务器）。

### 5. 组织切换

- [ ] 主屏点「我的企业」→ 底部弹层列出候选组织（含当前组织）
- [ ] 选另一个组织 → 弹层关闭，主屏刷新为新组织的主体信息，「服务器」字段变为新组织 URL
- [ ] 切换失败场景（可开飞行模式后切换）→ 弹「企业验证失败」Alert

**RN 对照点**：候选列表内容与顺序、切换后主屏数据刷新速度。注意已知差异第 3 条：离线时候选列表最长延迟 15s，RN 是缓存即时显示。

### 6. 杀进程重启恢复

- [ ] 在主屏直接杀掉 App（最近任务划掉，或 `adb shell am force-stop cn.appia.im.debug`）→ 重新打开 → **直接回到主屏**，无需重新登录
- [ ] 重启后「在线/离线」状态能自动恢复

**RN 对照点**：RN 版重启同样直达主屏；比对恢复耗时。

### 7. 登出

- [ ] 主屏点「退出登录」→ 回到企业码页
- [ ] 再次登录同一账号 → 正常进主屏

**RN 对照点**：登出后落点（RN 同样回企业码页）、登出后重启 App 不应自动恢复会话。

### 8. `auth_session_expired`（服务端踢出，可选）

任选其一触发（均需管理员配合，做不到可跳过本项）：

1. 服务端吊销该账号会话（管理端踢出该用户在线会话）→ App 内触发任意 REST 请求（如下拉刷新/重进主屏）→ 自动登出回企业码页
2. 服务端吊销会话后杀进程重开 → 冷启动 DDP resume 失败 → 自动登出回企业码页
3. 手工使令牌失效（如服务端改密导致旧 token 失效）→ 触发任意请求 → 同上

- [ ] 登录后由服务端踢出会话（或令牌失效后触发任意 REST 请求）→ App 自动登出并回到企业码页

**RN 对照点**：RN 版会 toast 提示后登出；原生 M1 **直接回企业码页、无 toast 提示**（toast 基建 M5 补），属已知差异第 6 条范畴，不是缺陷但请记录实际表现。

## 三、问题反馈格式

每个问题一条，包含：

1. **复现步骤**：从哪个页面开始、点了什么、输入了什么（精确到第几步可复现）
2. **RN 表现**：（同操作在 RN 版的行为/文案/时序；「RN 也这样」也要写明）
3. **原生表现**：实际看到的行为/文案/时序
4. **截图/录屏**：两侧各一张（`adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png`）
5. 环境：设备型号、Android 版本、App 版本号（0.2.0）

示例：

> - 复现步骤：登录成功后点「我的企业」→ 选择候选组织 B
> - RN 表现：弹层内点击后约 1s 主屏切换完成
> - 原生表现：点击后主屏立即切换，但连接状态文本 3s 内显示「离线」再变「在线」
> - 截图：attach
> - 环境：Pixel 7 / Android 14 / 0.2.0

## 四、已知差异（对照 RN 版，非缺陷，请勿按缺陷反馈）

1. **登录页语言切换挂件未移植**：原生版跟随系统语言（中文/英文），登录页内切换语言的入口留到 M5 建应用内语言基建时恢复。
2. **区号回落显示时机**：RN 选择后乐观先显 `+86`（返回登录页后再按实际所选项替换）；原生版等从区号页返回到登录页后才显示所选区号。
3. **组织候选列表离线延迟**：RN 弹层先显缓存再异步刷新；原生 M1 等会话就绪后一次加载，离线时最长延迟 15s。M2 改两段式。
4. **CAS enabled 严格布尔**：服务端返回非严格 `true` 时原生按「未启用」处理（fail-closed）；RN 更宽松。现网无该场景。
5. **密码滑块 4xx/5xx 无横幅**：密码模式下滑块验证页遇到 4xx/5xx 不显错误横幅；只有 SMS 底部弹层滑块有错误横幅（对齐 RN 行为差异待 M2 复查）。
6. **RN 的 `VerificationScreen` 未复刻**：该屏在 RN 侧为死路由（screenRegistry 注册后自身无任何入口导航到），原生未复刻，两侧用户均不可见。

另：主屏（Main）本身是 M1 占位屏（主体信息 + 我的企业 + 登出），房间列表 UI 属 M2 范围——数据是否落库用第二节第 4 项核对，不对照主屏 UI。

## 五、密码登录模式的开启方式

密码模式默认**关闭**，SMS 为主模式。如需自测密码登录（含第 5 条已知差异）：

打开 `app/src/main/kotlin/cn/appia/im/feature/login/ui/LoginScreen.kt`，将

```kotlin
internal const val ENABLE_PASSWORD_LOGIN = false
```

改为 `true` 后重新构建安装（`./gradlew :app:assembleDebug`）。该常量只在 debug 构建自测时有意义；改完记得改回 `false` 再提交。

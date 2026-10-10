import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    // FCM 辅助通道（GMS 机型离线送达）：仅 release 应用，见下方条件 apply
    alias(libs.plugins.google.services) apply false
}

// Release 签名沿用 RN 的 release.properties 机制：文件缺失时仅警告，debug 构建不受阻。
// KEYSTORE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD 可被同名环境变量覆盖。
val releaseProps = Properties().apply {
    val f = rootProject.file("release.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    } else {
        logger.warn("release.properties 不存在，release 构建将不配置签名（debug 构建不受影响）")
    }
}
fun releaseProp(key: String): String? =
    System.getenv(key)?.takeIf { it.isNotEmpty() } ?: releaseProps.getProperty(key)

// versionCode 策略（坑 10）：生产 RN 版已在用户设备装到 28187853（v1.21.9），系统安装器拒绝
// 降级——自更新覆盖安装要求新包 versionCode > 28187853，否则安装直接失败。
// 显式来源：release.properties VERSION_CODE（同名环境变量可覆盖，与 KEYSTORE 同机制）；
// 未提供时兜底 DEFAULT_VERSION_CODE，保证裸 release 包天然可覆盖安装；显式给出 ≤ 下限的值
// 则构建失败。M11 接管完整 VERSION_* 发布接线；ABI 拆分公式（RN abi*2^20+base）留作 M11 选项
// ——AA 无 splits（单一 universal APK），现无需 per-ABI code。
val MIN_INSTALLABLE_VERSION_CODE = 28187853
val DEFAULT_VERSION_CODE = 28187900
val versionCodeOverride = releaseProp("VERSION_CODE")?.trim()?.toIntOrNull()
if (versionCodeOverride != null && versionCodeOverride <= MIN_INSTALLABLE_VERSION_CODE) {
    throw GradleException(
        "VERSION_CODE=$versionCodeOverride 不合法：自更新覆盖安装要求 > $MIN_INSTALLABLE_VERSION_CODE" +
            "（生产 RN 版已装 28187853，降级安装会被系统拒绝）"
    )
}

android {
    namespace = "cn.appia.im"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.appia.im"
        minSdk = 24
        targetSdk = 36
        versionCode = versionCodeOverride ?: DEFAULT_VERSION_CODE
        versionName = "0.8.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true // RocketSdk.initialize 的 dev 重连间隔（RN __DEV__ → BuildConfig.DEBUG）
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        // debug 与生产版共存（T1-T12 冒烟用 Maestro appId cn.appia.im.debug）
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = false
            val keystorePath = releaseProp("KEYSTORE")
            if (keystorePath != null) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(keystorePath)
                    storePassword = releaseProp("KEYSTORE_PASSWORD")
                    keyAlias = releaseProp("KEY_ALIAS")
                    keyPassword = releaseProp("KEY_PASSWORD")
                }
            } else {
                logger.warn("release.properties 未提供 KEYSTORE，release APK 将使用 debug 签名输出（仅开发用途）")
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric 需要真实资源（strings.xml）才能解析 getIdentifier / getString
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.useJUnitPlatform() }
    }
}

// google-services 插件按 google-services.json 的 package（cn.appia.im）匹配 applicationId，
// 而 debug 是 cn.appia.im.debug（与生产共存），插件会因找不到匹配 client 而构建失败。
// 故仅当任务图含 release 时应用插件：debug 构建完全不经过它（FCM meta-data 两 build 均带，
// 仅缺插件生成的 Firebase 初始化 values —— 只影响 debug 包的 GMS 离线送达，可接受）。
// 已知边界：不带 Release 字样的任务（裸 assemble / installDebug 后接 assemble）不会触发应用。
if (gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
    apply(plugin = "com.google.gms.google-services")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// Room schema 导出（app/schemas/<Db>/<version>.json），迁移评审与测试基线
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.network)
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)
    implementation(libs.mmkv)
    implementation(libs.coil.compose)
    // Coil 网络模块（ServiceLoader 注册 NetworkFetcher，AsyncImage 才能拉远程头像；T3 遗留）
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // 阿里云 EMAS 推送：主 SDK + 厂商通道（华为/荣耀/vivo/小米/OPPO/魅族/GCM 桥）
    implementation(libs.aliyun.push)
    implementation(libs.aliyun.third.push)
    // Agora RTC（M8 会议 greenfield；T1 仅依赖 spike，引擎初始化归 T6）
    implementation(libs.agora.rtc.full)

    testImplementation(libs.bundles.test)
    // Compose UI 测试：Robolectric + createComposeRule（T3 起）；manifest 提供 test activity
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    // Robolectric 仍是 JUnit4 runner，经 vintage 引擎混跑在 JUnit Platform 上
    testImplementation(libs.bundles.test.android)
    testRuntimeOnly(libs.junit.vintage.engine)
    // Gradle 8.13 注入的 launcher 与 jupiter 5.13 引擎版本不对齐，需显式对齐
    testRuntimeOnly(libs.junit.platform.launcher)
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
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

android {
    namespace = "cn.appia.im"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.appia.im"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
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

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.network)
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)
    implementation(libs.mmkv)
    implementation(libs.coil.compose)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.bundles.test)
}

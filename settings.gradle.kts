pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 阿里云 EMAS 推送 SDK（alicloud-android-push / third-push）仅发布在此仓
        maven("https://maven.aliyun.com/nexus/content/repositories/releases/")
    }
}

rootProject.name = "AppiaAndroid"
include(":app")

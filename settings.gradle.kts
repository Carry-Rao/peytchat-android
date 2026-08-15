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
    }
}

rootProject.name = "peytchat"

include(
    ":app",
    ":core",
    ":data",
    ":theme",
    ":patch-api",
    ":patch:chat",
    ":feature:shell",
    ":feature:login",
    ":feature:chat"
)

// :core/:data/:theme/:patch-api 源码放在 libs/ 下。
// 注意根目录 core/ 是 chatmail/core 的 Rust 子模块，不能作为 Gradle 工程参与构建。
project(":core").projectDir = file("libs/core")
project(":data").projectDir = file("libs/data")
project(":theme").projectDir = file("libs/theme")
project(":patch-api").projectDir = file("libs/patch-api")

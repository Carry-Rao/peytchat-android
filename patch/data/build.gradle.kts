plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cn.yzjtiantian.android.patch"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// 补丁元信息（与 updates/update.json 保持一致）
val patchModule = "data"
val patchVersion = "0.0.2"

dependencies {
    // 只需 :core（ModuleManager / TextSendHook），不依赖 :data——
    // 补丁类不覆盖基座类，只是注册新行为
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
}

/**
 * 把本模块打成热更新补丁 dex：
 *  - 依赖 bundleLibRuntimeToJarDebug 拿到「模块自己的 classes.jar」（不含依赖）；
 *  - 用 SDK 里的 d8 打成 dex，输出到 updates/${patchModule}_${patchVersion}.dex。
 *
 * 用法:
 *   ./gradlew :patch:data:packagePatchDex
 * 然后生成清单:
 *   OUT=updates/update.json ./scripts/gen-update-manifest.sh \
 *       https://peyt.org/peytchat-android-update/ updates/data_0.0.1.dex
 */
val packagePatchDex = tasks.register("packagePatchDex") {
    group = "hot-update"
    description = "打补丁 dex 到 updates/${patchModule}_${patchVersion}.dex"
    dependsOn("bundleLibRuntimeToJarDebug")

    doLast {
        val sdkDir = android.sdkDirectory
        val buildToolsDir = File(sdkDir, "build-tools")
        val d8 = buildToolsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.name }
            ?.resolve("d8")
            ?: error("找不到 SDK d8（build-tools 未安装？）")
        val androidJar = File(sdkDir, "platforms/android-${android.compileSdk}/android.jar")
        val classesJar = layout.buildDirectory
            .file("intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar")
            .get().asFile
        require(classesJar.exists()) { "未找到 classes.jar: $classesJar" }

        val outDir = rootProject.layout.projectDirectory.dir("updates").asFile
        outDir.mkdirs()

        project.exec {
            commandLine(
                d8.absolutePath,
                "--lib", androidJar.absolutePath,
                // minSdk=26 原生支持接口默认方法，无需 d8 脱糖
                "--min-api", "26",
                "--output", outDir.absolutePath,
                classesJar.absolutePath,
            )
        }

        val produced = File(outDir, "classes.dex")
        require(produced.exists()) { "d8 未产出 classes.dex" }
        val target = File(outDir, "${patchModule}_${patchVersion}.dex")
        if (target.exists()) target.delete()
        produced.copyTo(target)
        println("✔ 补丁 dex 已生成: ${target.absolutePath}")
    }
}

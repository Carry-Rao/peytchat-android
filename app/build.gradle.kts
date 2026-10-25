import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        load(keystorePropsFile.inputStream())
    }
}

android {
    namespace = "cn.yzjtiantian.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.yzjtiantian.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        val builtAbis = file("src/main/jniLibs")
            .listFiles { f -> f.isDirectory && File(f, "libpeytchat_bridge.so").exists() }
            ?.map { it.name }
            .orEmpty()
        if (builtAbis.isNotEmpty()) {
            ndk { abiFilters += builtAbis }
        }
    }
    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 构建前校验 native 桥接库已就位：缺失时直接报可操作的错误，
// 而不是产出一个「能装、一启动就 NoClassDefFoundError」的包。
val verifyNativeLibs = tasks.register("verifyNativeLibs") {
    val jniDir = file("src/main/jniLibs")
    doFirst {
        val abis = jniDir.listFiles { f -> f.isDirectory && File(f, "libpeytchat_bridge.so").exists() }
            ?.map { it.name }
            .orEmpty()
        if (abis.isEmpty()) {
            throw GradleException(
                "app/src/main/jniLibs/ 下没有 libpeytchat_bridge.so，构建出的 APK 会在启动时崩溃。\n" +
                    "请先生成 native 库，例如：\n" +
                    "  ./scripts/build-android.sh debug arm64-v8a,x86_64"
            )
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyNativeLibs) }

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":theme"))
    implementation(project(":feature:shell"))
    implementation(project(":feature:login"))
    implementation(project(":feature:chat"))

    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation(libs.androidx.ui.tooling)
}
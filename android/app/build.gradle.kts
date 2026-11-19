import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 三端共用资源（图标、文案、设计变量、品牌图形）和编辑器网页来自旁边的主仓库：
//   lemon-paperplane/
//     lemon-paperplane-main/      shared/、lemon-paperplane-main-editor/dist/
//     lemon-paperplane-mobile/    本仓库
// 放在别处时用 local.properties 里的 paperplane.mainRepo=<路径>、-Ppaperplane.mainRepo 或环境变量 PAPERPLANE_MAIN_REPO 指定
val localProps = Properties().also { p ->
    rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use { r -> p.load(r) }
}
val mainRepo = ((findProperty("paperplane.mainRepo") as String?) ?: localProps.getProperty("paperplane.mainRepo")
    ?: System.getenv("PAPERPLANE_MAIN_REPO"))
    ?.let { file(it) }
    ?: rootProject.projectDir.resolve("../../lemon-paperplane-main").normalize()
val generatedAssets = layout.buildDirectory.dir("generated/paperplane-assets")

val syncPaperplaneAssets by tasks.registering(Sync::class) {
    description = "Copy shared icons / i18n / design tokens and the editor web pages from lemon-paperplane-main"
    val shared = mainRepo.resolve("shared")
    val editor = mainRepo.resolve("lemon-paperplane-main-editor/dist")
    doFirst {
        require(shared.isDirectory) { "找不到 $shared：把 lemon-paperplane-main 克隆到 lemon-paperplane-mobile 旁边" }
        require(editor.resolve("index.html").isFile) {
            "找不到编辑器网页 $editor：在 lemon-paperplane-main/lemon-paperplane-main-editor 下执行 npm ci && npm run build"
        }
    }
    from(shared) {
        include("icons/*.svg", "branding/*.svg", "i18n/*.json", "i18n/android/*.json", "design-tokens.json")
        into("shared")
    }
    from(editor) { into("editor") }
    into(generatedAssets)
}

android {
    namespace = "cn.lemonit.paperplane"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.lemonit.paperplane"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 正式签名配置好之前先用调试签名
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    sourceSets["main"].assets.srcDir(generatedAssets)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.named("preBuild") { dependsOn(syncPaperplaneAssets) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.webkit:webkit:1.13.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // SVG 图标（三端共用的 shared/icons 和插件图标）
    implementation("com.caverock:androidsvg-aar:1.4")
    // 扫码添加服务器（不依赖 Google Play 服务，国内手机也能用）
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

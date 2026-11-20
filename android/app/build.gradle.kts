import java.net.URI
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
        // 云端构建传入：版本号取标签（v0.2.0 → 0.2.0），versionCode 取构建序号（每次递增，手机才能覆盖升级）
        versionCode = System.getenv("PAPERPLANE_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("PAPERPLANE_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 发布签名：密钥由 android/scripts/make-signing-key.sh 生成，存在仓库 Secrets（云端构建时解出来，经环境变量传入）。
    // 没有这些环境变量时（本机开发）用调试签名。
    val releaseKeystore = System.getenv("PAPERPLANE_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("PAPERPLANE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PAPERPLANE_KEY_ALIAS")
                keyPassword = System.getenv("PAPERPLANE_KEY_PASSWORD") ?: System.getenv("PAPERPLANE_KEYSTORE_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            // R8 压缩先不开：kotlinx.serialization 与插件网页桥接的保留规则还没在真机上验证过
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        // 截图测试（Robolectric + Roborazzi）要读 assets 里的图标、文案和设计变量
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // 截图测试每次都重新生成 PNG（写到 app/build/outputs/roborazzi/）；-Proborazzi.record=false 时跳过
            test.systemProperty("roborazzi.test.record", findProperty("roborazzi.record")?.toString() ?: "true")
            // Robolectric 运行时要下载 android-all；本机走代理时把 HTTPS_PROXY 交给测试进程
            System.getenv("HTTPS_PROXY")?.let { URI(it) }?.takeIf { it.host != null && it.port > 0 }?.let { u ->
                test.systemProperty("https.proxyHost", u.host)
                test.systemProperty("https.proxyPort", u.port.toString())
            }
            test.maxHeapSize = "3g"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.named("preBuild") { dependsOn(syncPaperplaneAssets) }

// 网络层联调测试：设置了 PAPERPLANE_TEST_BIN（go build 出来的内核）时才跑，否则自动跳过。
// 作为任务输入登记，换了内核或开关联调都会重跑测试，不会命中缓存
tasks.withType<Test>().configureEach {
    val bin = System.getenv("PAPERPLANE_TEST_BIN").orEmpty()
    inputs.property("paperplaneTestBin", bin)
    if (bin.isNotEmpty()) environment("PAPERPLANE_TEST_BIN", bin)
}

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
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")

    // 界面截图测试：JVM 上用 Robolectric 渲染 Compose，Roborazzi 存 PNG（ui/ 的组件陈列页与关键组件）
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.43.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.43.0")
}

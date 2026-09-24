plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lunashare.app"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.lunashare.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "1.0.14"

        // FFmpeg native libs — only ship arm64 (all modern devices); keeps APK small
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            // 固定签名：release 与 debug 共用，避免不同设备编译签名不一致。
            // 本项目完全开源、无商业计划，密钥信息公开于 README.md。
            storeFile = rootProject.file("app/keystore/lunashare-release.jks")
            storePassword = "ad6061a7383916a03868ab57618960bffe1c"
            keyAlias = "lunashare"
            keyPassword = "ad6061a7383916a03868ab57618960bffe1c"
        }
    }

    buildTypes {
        debug {
            // 与 release 共用同一固定签名，避免不同设备编译签名不一致
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // JavaCPP jars ship GraalVM native-image metadata we don't need at runtime
            excludes += "META-INF/native-image/**"
            // ffmpeg/ffprobe executables must NOT be packed into lib/ — the package
            // installer chokes on non-.so files there. JavaCPP extracts them from
            // the classpath at runtime instead.
            excludes += "lib/arm64-v8a/ffmpeg"
            excludes += "lib/arm64-v8a/ffprobe"
        }
        jniLibs {
            // Extract native libs to disk so the bundled ffmpeg engine
            // (libffmpeg_engine.so) is a real executable file we can run.
            useLegacyPackaging = true
        }
    }

    // No native libraries needed - pure Kotlin/Java application
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // NanoHTTPD - lightweight HTTP server
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // ByWebView — 成熟 WebView 封装（进度条/上传/下载/JS/混合内容/错误页）
    implementation("com.github.youlookwhat:ByWebView:1.2.1")

    // ZXing Android Embedded — 二维码/条码扫码（link Tab 扫码连接）
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // JavaCPP FFmpeg — FLAC → ALAC transcoding in the file browser
    // (android-arm64 classifier only; abiFilters above keeps the APK lean)
    implementation("org.bytedeco:ffmpeg:${libs.versions.bytedecoFfmpeg.get()}")
    implementation("org.bytedeco:ffmpeg:${libs.versions.bytedecoFfmpeg.get()}:android-arm64")
    implementation("org.bytedeco:javacpp:1.5.11:android-arm64")

    // OkHttp for OpenFrp API calls
    implementation(libs.okhttp)

    // Bouncy Castle for Curve25519/XSalsa20-Poly1305 (OpenFrp Remote Login)
    implementation(libs.bouncycastle.bcprov)

    // Unit tests (JUnit)
    testImplementation("junit:junit:4.13.2")

    // jmDNS — 局域网 mDNS 广播（让同网设备用 lunashare.local 访问本机共享）
    // 用本地 jar 而非 maven 坐标：避免沙箱/离线环境下远程 resolve 失败导致类缺失
    // jmDNS 运行时依赖 slf4j-api（日志门面，无绑定仅 warning 不报错）
    implementation(files("libs/jmdns-3.5.9.jar"))
    implementation(files("libs/slf4j-api-1.7.36.jar"))

    // 连接共享（远程客户端）：FTP（Apache Commons Net）+ SMB（jcifs-ng，SMB1/2/3）
    // 同样用本地 jar：jcifs-ng 还需 bcpkix（EllipticCurve 密钥协商）与 slf4j/bcprov（已有）
    implementation(files("libs/commons-net-3.11.1.jar"))
    implementation(files("libs/jcifs-ng-2.1.10.jar"))
    implementation(files("libs/bcpkix-jdk15on-1.67.jar"))
}

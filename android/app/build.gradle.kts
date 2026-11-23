plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.persona.ar"
    // Filament 1.81.0 的 AAR 声明 minCompileSdk=37（见其 aar-metadata.properties）；compileSdk 只影响编译期可见 API，
    // 与 targetSdk（运行时行为）和 minSdk（可安装机型）解耦，故仅提升 compileSdk
    compileSdk = 37

    defaultConfig {
        applicationId = "com.persona.ar"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9 内置 Kotlin：Kotlin jvmTarget 默认跟随上方 compileOptions.targetCompatibility (17)，无需再设；
    // Compose 编译器由 org.jetbrains.kotlin.plugin.compose 提供，不再使用 kotlinCompilerExtensionVersion
    buildFeatures {
        compose = true
    }
    androidResources {
        // GLB 与 .filamat 已是压缩友好的二进制，禁用 AAPT 压缩以便零解压直接读入直接缓冲
        noCompress += listOf("glb", "filamat")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Jetpack Compose BOM & UI
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    // CameraX (用于 2.5D 平面透视与 AR 背景采集)
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Google ARCore (用于 3D 真实平面检测与空间锚定)
    implementation("com.google.ar:core:1.44.0")

    // Google Filament 真 3D 渲染引擎：离线 PBR 着色、方向光阴影贴图、SSAO、透明 SwapChain 叠加相机画面（纯端侧，零 GMS）
    // 1.81.0 依赖 kotlin-stdlib 2.4.0，与本项目 Kotlin 2.4.21 的 metadata 版本兼容（1.76 之前的版本为纯 Java 绑定）
    // gltfio：glTF/GLB 资产加载（UbershaderProvider 内置 PBR 材质）；utils：Utils.init() 原生库初始化、IBL 预滤波工具
    val filamentVersion = "1.81.0"
    implementation("com.google.android.filament:filament-android:$filamentVersion")
    implementation("com.google.android.filament:gltfio-android:$filamentVersion")
    implementation("com.google.android.filament:filament-utils-android:$filamentVersion")

    // 网络与 API 交互 (对接专属情感后端)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // 图片与立绘加载
    implementation("io.coil-kt:coil-compose:2.6.0")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 测试
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

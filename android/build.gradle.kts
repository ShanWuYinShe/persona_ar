buildscript {
    dependencies {
        // AGP 9 内置 Kotlin 默认绑定 KGP 2.2.10；此处按官方「Upgrade to a higher KGP version」升到 2.4.21，
        // 以读取 Filament 1.81 依赖的 kotlin-stdlib 2.4.0 metadata（Kotlin 编译器只能向前读一个小版本的 metadata）。
        // https://developer.android.com/build/releases/agp-9-0-0-release-notes#android-gradle-plugin-built-in-kotlin
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    // 工具链：Gradle 9.6.0 / AGP 9.3.1 / KGP 2.4.21（经上方 buildscript classpath 指定），运行在 Android Studio 自带 JBR (JDK 25) 上。
    // - AGP 9.x 为 Gradle 9 设计，Kotlin 编译内置于 AGP，模块不再应用 org.jetbrains.kotlin.android（Compose 编译器插件仍需单独应用）；
    // - Filament 1.81 要求 compileSdk 37；SDK 仓库仅有次版本命名的 platforms;android-37.0，AGP < 8.9 无法解析。
    id("com.android.application") version "9.3.1" apply false
    // Compose 编译器插件随 KGP 同版本发布，版本须与上方 classpath 指定的 KGP 一致；compose 开启时为必需
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

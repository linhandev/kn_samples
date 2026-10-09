import android.databinding.tool.ext.capitalizeUS
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    ohosArm64 {
        compilerOptions.freeCompilerArgs.add("-Xklib-duplicated-unique-name-strategy=allow-all-with-warning")
        // HiLog and all other ohos platform bindings now come from the cpf 0.4-built klib
        // published to maven local (com.example:ohos-capi:22-0.1-SNAPSHOT, package
        // platform.PerformanceAnalysisKit.HiLog etc.) — no local cinterop here.
        binaries.sharedLib {
            baseName = "kn"
            // HMS sysroot (.so stubs for HarmonyOS-SDK-Only Kits) checked into the repo.
            val hmsLib = "${rootProject.projectDir.parentFile}/sysroot/sysroot-hms-aarch64-6.0.2.640-02/usr/lib/aarch64-linux-ohos"
            freeCompilerArgs += "-linker-options=-L$hmsLib"
            // Set SONAME so the NAPI libentry.so's NEEDED records bare "libkn.so" (not a host
            // absolute path), letting the device dlopen it from the app's libs dir.
            freeCompilerArgs += "-linker-options=-Wl,-soname,libkn.so"
            // weak API 防护需要保留被使用库的 -l；在 KN 层裁剪未使用的 KLIB。
            freeCompilerArgs += "-Xpurge-user-libs"

        }
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    // uncomment below to enable address sanitizer
                    // freeCompilerArgs.add("-Xbinary=sanitizer=address")
                }
            }
        }
    }

    sourceSets {
        androidMain.dependencies {}
        commonMain.dependencies {
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val ohosArm64Main by getting {
            dependencies {
                // klib built with cpf 0.4, consumed by 2.3.20-HUAWEI.
                // ohos-capi(144 ohos def)+ hms-capi(19 hms 扩展 def,hms POM depend ohos-capi)。
                // demo 测 hms API(HandWrite 不依赖 ohos / CANN 依赖 ohos 的 NeuralNetworkRuntime)。
                implementation("org.cpf.kotlin:ohos-capi:${property("klibVersion")}")
                implementation("org.cpf.kotlin:hms-capi:${property("klibVersion")}")
            }
        }
    }
}

android {
    namespace = "com.example.kmpmultiplatform"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.example.kmpmultiplatform"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {}

arrayOf("debug", "release").forEach { type ->
    tasks.register<Copy>("publish${type.capitalizeUS()}BinariesToHarmonyApp") {
        group = "harmony"
        dependsOn("link${type.capitalizeUS()}SharedOhosArm64")
        into(rootProject.file("harmonyApp"))
        from("build/bin/ohosArm64/${type}Shared/libkn_api.h") {
            into("entry/src/main/cpp/include/")
        }
        from(project.file("build/bin/ohosArm64/${type}Shared/libkn.so")) {
            into("entry/libs/arm64-v8a/")
        }
    }
}

arrayOf("debug", "release").forEach { type ->
    tasks.register<Copy>("publish${type.capitalizeUS()}BinariesToHarmonyAppX64") {
        group = "harmony"
        dependsOn("link${type.capitalizeUS()}SharedOhosX64")
        into(rootProject.file("harmonyApp"))
        from("build/bin/ohosX64/${type}Shared/libkn_api.h") {
            into("entry/src/main/cpp/include/")
        }
        from(project.file("build/bin/ohosX64/${type}Shared/libkn.so")) {
            into("entry/libs/x86_64/")
        }
    }
}

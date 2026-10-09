plugins { kotlin("multiplatform") }

group = "org.cpf.kotlin"

// 同一份业务代码分别验证 BOM 和 leaf 依赖：-PcapiDependencyMode=bom|leaf。
val capiDependencyMode = providers.gradleProperty("capiDependencyMode").getOrElse("bom")
require(capiDependencyMode in setOf("bom", "leaf")) { "capiDependencyMode must be bom or leaf" }

kotlin {
    ohosArm64 {
        compilerOptions.freeCompilerArgs.add("-Xklib-duplicated-unique-name-strategy=allow-all-with-warning")
        binaries.sharedLib {
            baseName = "c2k"
            // 在 KN 层裁剪未使用的用户 KLIB；不要对 weak API 引用使用 --as-needed。
            freeCompilerArgs += "-Xpurge-user-libs"
            // HUAWEI 经 clang 驱动，CPF 直接调用 ld.lld。
            val soname = if (property("kotlinVersion").toString().contains("HUAWEI"))
                "-Wl,-soname,libc2k.so" else "-soname libc2k.so"
            freeCompilerArgs += "-linker-options=$soname"
        }
    }
    sourceSets {
        val ohosArm64Main by getting {
            dependencies {
                val version = property("klibVersion")
                implementation("org.cpf.kotlin:biz-klib:$version")
                if (capiDependencyMode == "bom") {
                    implementation("org.cpf.kotlin:ohos-capi:$version")
                } else {
                    implementation("org.cpf.kotlin:AVTranscoder:$version")
                    implementation("org.cpf.kotlin:HiLog:$version")
                    implementation("org.cpf.kotlin:posix:$version")
                }
            }
        }
    }
}

listOf("Debug", "Release").forEach { type ->
    tasks.register("publish${type}BinariesToHarmonyApp") {
        dependsOn("link${type}SharedOhosArm64")
        doLast {
            val bin = layout.buildDirectory.dir("bin/ohosArm64/${type.lowercase()}Shared").get().asFile
            copy {
                from(bin.resolve("libc2k.so"))
                into(rootProject.file("harmonyApp/entry/libs/arm64-v8a"))
            }
            copy {
                from(bin.resolve("libc2k_api.h"))
                into(rootProject.file("harmonyApp/entry/src/main/cpp/include"))
            }
        }
    }
}

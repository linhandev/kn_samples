plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "org.cpf.kotlin"

base.archivesName.set("static-lib-demo")

kotlin {
    ohosArm64 {
        compilations {
            val main by getting {
                cinterops {
                    val mylib by creating {
                        defFile("$projectDir/nativeInterop/ohosArm64/mylib.def")
                        // .h and .a live in csrc/
                        extraOpts("-compiler-option", "-I$projectDir/csrc")
                        extraOpts("-libraryPath", "$projectDir/csrc")
                    }
                }
            }
        }
    }

}

publishing {
    publications {
        withType<MavenPublication> {
            artifactId = "static-lib-demo"
        }
    }
    // Publish to the repo-local Maven repo (kn_sample/m2, gitignored) instead of ~/.m2.
    // Use `./gradlew :static-lib-demo:publish`. 仅发布到本地仓库。
    repositories {
        maven { name = "m2"; url = uri(rootProject.projectDir.parentFile.resolve("m2")) }
    }
}

// 单目标测试包只发布 Native publication，避免 KMP 根 publication 覆盖同一坐标。
tasks.withType<org.gradle.api.publish.maven.tasks.PublishToMavenRepository>().configureEach {
    if (publication.name == "kotlinMultiplatform") enabled = false
}

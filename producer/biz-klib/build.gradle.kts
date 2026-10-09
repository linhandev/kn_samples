plugins { kotlin("multiplatform"); `maven-publish` }
group = "org.cpf.kotlin"
base.archivesName.set("biz-klib")
kotlin {
    ohosArm64 {
        compilerOptions.freeCompilerArgs.add("-Xklib-duplicated-unique-name-strategy=allow-all-with-warning")
    }
    sourceSets {
        val ohosArm64Main by getting {
            dependencies {
                implementation("org.cpf.kotlin:AVTranscoder:${property("klibVersion")}")
                implementation("org.cpf.kotlin:AIP:${property("klibVersion")}")
            }
        }
    }
}
publishing {
    publications.withType<MavenPublication> { artifactId = "biz-klib" }
    repositories { maven { name = "m2"; url = uri(rootProject.projectDir.parentFile.resolve("m2")) } }
}

// 单目标测试包只发布 Native publication，避免 KMP 根 publication 覆盖同一坐标。
tasks.withType<org.gradle.api.publish.maven.tasks.PublishToMavenRepository>().configureEach {
    if (publication.name == "kotlinMultiplatform") enabled = false
}

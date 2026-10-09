// Local Maven repo at the repo root (kn_sample/m2, gitignored). Producer publishes klibs here;
// consumers resolve com.example:ohos-capi / static-lib-demo from here instead of ~/.m2.
// Inlined in the dependencyResolutionManagement block (it doesn't see top-level vals).
pluginManagement {
    val knActionMavenUrl: String? = System.getenv("KN_ACTION_BUILD_REPO_ABS")
        ?.let { java.io.File(it).toURI().toString() }
    val huaweiMavenUrl = uri("https://devrepo.devcloud.cn-north-4.huaweicloud.com/artgalaxy/cn-north-4_a8338babc8534bb8aabb062c35845155_maven_7_1/")
    val localProps = java.util.Properties().apply {
        runCatching { java.io.File("local.properties").inputStream() }.getOrNull()?.let { load(it) }
    }
    val huaweiUser = System.getenv("HUAWEI_MAVEN_USER") ?: localProps.getProperty("huaweiMavenUser")
    val huaweiPass = System.getenv("HUAWEI_MAVEN_PASS") ?: localProps.getProperty("huaweiMavenPass")
    repositories {
        knActionMavenUrl?.let { maven(it) }
        maven {
            url = huaweiMavenUrl
            content { includeVersionByRegex(".*", ".*", ".*HUAWEI.*") }
            isAllowInsecureProtocol = false
            if (huaweiUser != null && huaweiPass != null) {
                credentials { username = huaweiUser; password = huaweiPass }
                authentication { create("basic", org.gradle.authentication.http.BasicAuthentication::class.java) }
            }
        }
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public")
        maven("https://mirrors.tencent.com/nexus/repository/maven-tencent")
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        val kotlinVersion: String by settings
        kotlin("multiplatform") version kotlinVersion
    }
}

dependencyResolutionManagement {
    val knActionMavenUrl: String? = System.getenv("KN_ACTION_BUILD_REPO_ABS")
        ?.let { java.io.File(it).toURI().toString() }
    val huaweiMavenUrl = uri("https://devrepo.devcloud.cn-north-4.huaweicloud.com/artgalaxy/cn-north-4_a8338babc8534bb8aabb062c35845155_maven_7_1/")
    val localProps = java.util.Properties().apply {
        runCatching { java.io.File("local.properties").inputStream() }.getOrNull()?.let { load(it) }
    }
    val huaweiUser = System.getenv("HUAWEI_MAVEN_USER") ?: localProps.getProperty("huaweiMavenUser")
    val huaweiPass = System.getenv("HUAWEI_MAVEN_PASS") ?: localProps.getProperty("huaweiMavenPass")
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri(rootDir.parentFile.resolve("m2")) } }
            filter { includeGroup("org.cpf.kotlin") }
        }

        knActionMavenUrl?.let { maven(it) }
        maven {
            url = huaweiMavenUrl
            content { includeVersionByRegex(".*", ".*", ".*HUAWEI.*") }
            isAllowInsecureProtocol = false
            if (huaweiUser != null && huaweiPass != null) {
                credentials { username = huaweiUser; password = huaweiPass }
                authentication { create("basic", org.gradle.authentication.http.BasicAuthentication::class.java) }
            }
        }
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public")
        maven("https://mirrors.tencent.com/nexus/repository/maven-tencent")
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
        mavenCentral()
    }
}

rootProject.name = "c2k"

include("kotlinApp")

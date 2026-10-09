rootProject.name = "KMPMultiplatform"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Local Maven repo at the repo root (kn_sample/m2, gitignored). Producer publishes klibs here;
// consumers resolve com.example:ohos-capi / static-lib-demo from here instead of ~/.m2.
// Inlined in each block: pluginManagement/dependencyResolutionManagement don't see top-level vals.
pluginManagement {
    val localProps = java.util.Properties().apply {
        runCatching { java.io.File("local.properties").inputStream() }.getOrNull()?.let { load(it) }
    }
    val huaweiUser = System.getenv("HUAWEI_MAVEN_USER") ?: localProps.getProperty("huaweiMavenUser")
    val huaweiPass = System.getenv("HUAWEI_MAVEN_PASS") ?: localProps.getProperty("huaweiMavenPass")
    repositories {

        maven {
            url = uri("https://devrepo.devcloud.cn-north-4.huaweicloud.com/artgalaxy/cn-north-4_a8338babc8534bb8aabb062c35845155_maven_7_1/")
            content { includeVersionByRegex(".*", ".*", ".*HUAWEI.*") }
            if (huaweiUser != null && huaweiPass != null) {
                credentials { username = huaweiUser; password = huaweiPass }
                authentication { create("basic", org.gradle.authentication.http.BasicAuthentication::class.java) }
            }
        }
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
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


        maven {
            url = uri("https://devrepo.devcloud.cn-north-4.huaweicloud.com/artgalaxy/cn-north-4_a8338babc8534bb8aabb062c35845155_maven_7_1/")
            content { includeVersionByRegex(".*", ".*", ".*HUAWEI.*") }
            if (huaweiUser != null && huaweiPass != null) {
                credentials { username = huaweiUser; password = huaweiPass }
                authentication { create("basic", org.gradle.authentication.http.BasicAuthentication::class.java) }
            }
        }
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":composeApp")

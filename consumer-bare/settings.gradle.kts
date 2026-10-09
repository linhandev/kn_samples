// Local Maven repo at the repo root (kn_sample/m2, gitignored). Producer publishes klibs here;
// consumers resolve com.example:ohos-capi / static-lib-demo from here instead of ~/.m2.
// Inlined in the dependencyResolutionManagement block (it doesn't see top-level vals).
pluginManagement {
    val knActionMavenUrl: String? = System.getenv("KN_ACTION_BUILD_REPO_ABS")
        ?.let { java.io.File(it).toURI().toString() }
    val localProps = java.util.Properties().apply {
        runCatching { java.io.File("local.properties").inputStream() }.getOrNull()?.let { load(it) }
    }
    val colabUser = System.getenv("COLAB_MAVEN_USER") ?: localProps.getProperty("colabMavenUser")
    val colabPass = System.getenv("COLAB_MAVEN_PASS") ?: localProps.getProperty("colabMavenPass")
    repositories {
        knActionMavenUrl?.let { maven(it) }
        // Aliyun 保留了 HUAWEI 编译器；仅用于工具链，CAPI 仍从本地 m2 解析。
        if (!colabUser.isNullOrBlank() && !colabPass.isNullOrBlank()) {
            maven {
                name = "colabToolchain"
                url = uri("https://packages.aliyun.com/687e79a0e94e043d2d0f76ea/maven/colab")
                content { includeVersionByRegex(".*", ".*", ".*HUAWEI.*") }
                credentials { username = colabUser; password = colabPass }
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
    val localProps = java.util.Properties().apply {
        runCatching { java.io.File("local.properties").inputStream() }.getOrNull()?.let { load(it) }
    }
    val colabUser = System.getenv("COLAB_MAVEN_USER") ?: localProps.getProperty("colabMavenUser")
    val colabPass = System.getenv("COLAB_MAVEN_PASS") ?: localProps.getProperty("colabMavenPass")
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri(rootDir.parentFile.resolve("m2")) } }
            filter { includeGroup("org.cpf.kotlin") }
        }

        knActionMavenUrl?.let { maven(it) }
        // Aliyun 保留了 HUAWEI 编译器；仅用于工具链，CAPI 仍从本地 m2 解析。
        if (!colabUser.isNullOrBlank() && !colabPass.isNullOrBlank()) {
            maven {
                name = "colabToolchain"
                url = uri("https://packages.aliyun.com/687e79a0e94e043d2d0f76ea/maven/colab")
                content { includeVersionByRegex(".*", ".*", ".*HUAWEI.*") }
                credentials { username = colabUser; password = colabPass }
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

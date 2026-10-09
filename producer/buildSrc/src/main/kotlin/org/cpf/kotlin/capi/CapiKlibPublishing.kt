package org.cpf.kotlin.capi

import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import javax.inject.Inject

/**
 * Publishes each cinterop klib as its own Maven coordinate (artifactId = def name) and a BOM
 * coordinate that only api-depends on all leaves (POM/GMM, no aggregator klib). GMM attributes
 * match KMP ohosArm64ApiElements so HUAWEI consumers resolve leaves onto the ohosArm64 classpath.
 */
abstract class CapiKlibPublishing @Inject constructor(
    private val softwareComponentFactory: SoftwareComponentFactory,
) {
    data class DefInfo(val name: String, val depends: List<String>)

    fun configure(
        project: Project,
        defs: List<DefInfo>,
        bomArtifactId: String,
        /** All known def names across ohos+hms — used to validate depends tokens. */
        knownDefNames: Set<String>,
        archivesName: String,
        klibVersion: String,
        groupId: String = "org.cpf.kotlin",
    ) {
        validateDepends(project, defs, knownDefNames)

        val kotlinNativeTarget = Attribute.of("org.jetbrains.kotlin.native.target", String::class.java)
        val kotlinPlatformType = Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java)
        val artifactType = Attribute.of("artifactType", String::class.java)
        val jvmEnvironment = Attribute.of("org.gradle.jvm.environment", String::class.java)

        fun applyOhosArm64KlibAttributes(attrs: org.gradle.api.attributes.AttributeContainer) {
            attrs.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, "kotlin-api"))
            attrs.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.LIBRARY))
            attrs.attribute(kotlinPlatformType, "native")
            attrs.attribute(kotlinNativeTarget, "ohos_arm64")
            attrs.attribute(artifactType, "org.jetbrains.kotlin.klib")
            attrs.attribute(jvmEnvironment, "non-jvm")
        }

        project.extensions.configure<PublishingExtension> {
            // Leaf publications
            defs.forEach { d ->
                val configName = "capiLeaf${d.name.replace(Regex("[^A-Za-z0-9]"), "_")}ApiElements"
                val config = project.configurations.create(configName) {
                    isCanBeConsumed = true
                    isCanBeResolved = false
                    isVisible = false
                    applyOhosArm64KlibAttributes(attributes)
                }

                d.depends.forEach { dep ->
                    project.dependencies.add(configName, "$groupId:$dep:$klibVersion")
                }

                val klibTaskName = "ohosArm64Cinterop-${d.name}Klib"
                val klibFileName = "$archivesName-ohosArm64Cinterop-${d.name}Main-$klibVersion.klib"
                val klibProvider = project.layout.buildDirectory.file("libs/$klibFileName")
                config.outgoing.artifact(klibProvider) {
                    name = d.name
                    extension = "klib"
                    type = "klib"
                    builtBy(klibTaskName)
                }

                val component = softwareComponentFactory.adhoc("capiLeaf${d.name}Component")
                component.addVariantsFromConfiguration(config) {
                    mapToMavenScope("compile")
                }

                publications.create<MavenPublication>("leaf${d.name}") {
                    this.groupId = groupId
                    artifactId = d.name
                    version = klibVersion
                    from(component)
                }
            }

            // BOM: POM/GMM only — api all leaves, no aggregator klib.
            val bomConfigName = "capiBom${bomArtifactId.replace("-", "_")}ApiElements"
            val bomConfig = project.configurations.create(bomConfigName) {
                isCanBeConsumed = true
                isCanBeResolved = false
                isVisible = false
                applyOhosArm64KlibAttributes(attributes)
            }
            defs.forEach { d ->
                project.dependencies.add(bomConfigName, "$groupId:${d.name}:$klibVersion")
            }

            val bomComponent = softwareComponentFactory.adhoc("capiBom${bomArtifactId}Component")
            bomComponent.addVariantsFromConfiguration(bomConfig) {
                mapToMavenScope("compile")
            }

            publications.create<MavenPublication>("bom") {
                this.groupId = groupId
                artifactId = bomArtifactId
                version = klibVersion
                from(bomComponent)
            }

            // Always → m2/; colab only when KN_SAMPLE_ONLINE=1 (creds: env or local.properties)
            repositories {
                maven {
                    name = "m2"
                    url = project.uri(project.rootProject.projectDir.parentFile.resolve("m2"))
                }
                if (System.getenv("KN_SAMPLE_ONLINE") == "1") {
                    val localProps = java.util.Properties().apply {
                        runCatching {
                            project.rootProject.file("local.properties").inputStream().use { load(it) }
                        }
                    }
                    val colabUser = System.getenv("COLAB_MAVEN_USER")
                        ?: localProps.getProperty("colabMavenUser").orEmpty()
                    val colabPass = System.getenv("COLAB_MAVEN_PASS")
                        ?: localProps.getProperty("colabMavenPass").orEmpty()
                    if (colabUser.isNotEmpty() && colabPass.isNotEmpty()) {
                        maven {
                            name = "colab"
                            url = project.uri("https://packages.aliyun.com/687e79a0e94e043d2d0f76ea/maven/colab")
                            credentials {
                                username = colabUser
                                password = colabPass
                            }
                            authentication {
                                create(
                                    "basic",
                                    org.gradle.authentication.http.BasicAuthentication::class.java,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Disable KMP auto-publications (they still bundle every cinterop under one artifactId).
        project.tasks.withType(PublishToMavenRepository::class.java).configureEach {
            val pubName = publication.name
            if (pubName == "kotlinMultiplatform" || pubName == "ohosArm64") {
                enabled = false
            }
        }
        project.tasks.matching {
            it.name.startsWith("publishKotlinMultiplatformPublicationTo") ||
                it.name.startsWith("publishOhosArm64PublicationTo") ||
                it.name.startsWith("generateMetadataFileForKotlinMultiplatform") ||
                it.name.startsWith("generateMetadataFileForOhosArm64") ||
                it.name.startsWith("generatePomFileForKotlinMultiplatform") ||
                it.name.startsWith("generatePomFileForOhosArm64")
        }.configureEach { enabled = false }
    }

    companion object {
        /** Marker so re-materialize is idempotent. */
        const val THROW_SHIM_MARKER = "/* KN_SAMPLE_THROW_ILLEGAL_STATE_SHIM */"

        /**
         * Same weak C++ body injected into every C++ def's `---` (via [materializeDefWithThrowShim]).
         * ELF weak coalescing keeps a single definition in the final consumer .so; a future
         * strong runtime symbol still wins.
         *
         * Mirrors CPF `Exceptions.cpp` ThrowIllegalStateExceptionFromCString using HUAWEI-exported
         * runtime entry points (CalledFromNativeGuard / CreateStringFromCString /
         * ThrowIllegalStateException{,WithMessage}) — no abort, so ApiGuard can catch ISE.
         * CalledFromNativeGuard layout must stay 16 bytes (HUAWEI mm.bc dereferenceable(16)).
         */
        private val THROW_SHIM_CXX = """
            |$THROW_SHIM_MARKER
            |struct ObjHeader;
            |struct MemoryState;
            |namespace kotlin {
            |enum class ThreadState : int { kRunnable = 0, kNative = 1 };
            |// HUAWEI mm.bc: SwitchThreadState(MemoryState*, ThreadState, bool) —
            |// MemoryState is global, not nested in kotlin (mangling EP11MemoryState…).
            |ThreadState SwitchThreadState(::MemoryState* thread, ThreadState newState,
            |                              bool reentrant = false) noexcept;
            |class CalledFromNativeGuard {
            |public:
            |    explicit CalledFromNativeGuard(bool reentrant = false) noexcept;
            |    ~CalledFromNativeGuard() noexcept {
            |        SwitchThreadState(thread_, oldState_, reentrant_);
            |    }
            |private:
            |    ::MemoryState* thread_;
            |    ThreadState oldState_;
            |    bool reentrant_;
            |};
            |}
            |static_assert(sizeof(kotlin::CalledFromNativeGuard) == 16,
            |              "CalledFromNativeGuard size must match HUAWEI mm.bc");
            |extern "C" {
            |ObjHeader* CreateStringFromCString(const char* cstring, ObjHeader** result);
            |void ThrowIllegalStateException();
            |void ThrowIllegalStateExceptionWithMessage(ObjHeader* message);
            |}
            |extern "C" __attribute__((weak))
            |void ThrowIllegalStateExceptionFromCString(const char* message) {
            |    kotlin::CalledFromNativeGuard guard(/* reentrant = */ true);
            |    if (message == nullptr || message[0] == '\0') {
            |        ThrowIllegalStateException();
            |    } else {
            |        ObjHeader* str = nullptr;
            |        CreateStringFromCString(message, &str);
            |        ThrowIllegalStateExceptionWithMessage(str);
            |    }
            |}
        """.trimMargin()

        fun parseDefs(dir: java.io.File): List<DefInfo> =
            dir.listFiles { f -> f.extension == "def" }!!
                .map { f ->
                    val name = f.nameWithoutExtension
                    val lines = f.readLines()
                    val deps = lines
                        .firstOrNull { it.startsWith("depends") }
                        ?.substringAfter("=")?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }
                        ?: emptyList()
                    DefInfo(name, deps)
                }
                .sortedBy { it.name }

        /**
         * Copy [src] → [dest], and for C++ defs append a shared weak
         * [ThrowIllegalStateExceptionFromCString] into `---` plus
         * `excludedFunctions` so the shim is not exposed as a Kotlin API.
         */
        fun materializeDefWithThrowShim(src: java.io.File, dest: java.io.File) {
            dest.parentFile.mkdirs()
            val raw = src.readText()
            val lines = raw.lines()
            val isCpp = lines.any {
                val t = it.trim()
                t == "language = C++" || t == "language=C++"
            }
            if (!isCpp) {
                dest.writeText(raw)
                return
            }

            var text = raw
            val excludeLine =
                "excludedFunctions = ThrowIllegalStateExceptionFromCString CreateStringFromCString ThrowIllegalStateException ThrowIllegalStateExceptionWithMessage"
            fun insertExcludeBeforeDashOrLang(srcText: String): String {
                val dash = Regex("""(?m)^---\s*$""")
                if (dash.containsMatchIn(srcText)) {
                    return srcText.replaceFirst(dash, "$excludeLine\n---")
                }
                val lang = Regex("""(?m)^language\s*=\s*C\+\+\s*$""")
                val m = lang.find(srcText)
                if (m != null) {
                    return srcText.replaceRange(m.range, "${m.value}\n$excludeLine")
                }
                return srcText.trimEnd() + "\n$excludeLine\n"
            }
            if (!text.contains("excludedFunctions") ||
                !text.contains("ThrowIllegalStateExceptionFromCString")
            ) {
                // Ensure excludedFunctions lists the shim (idempotent if already present).
                if (!text.contains("excludedFunctions")) {
                    text = insertExcludeBeforeDashOrLang(text)
                }
            }

            if (!text.contains(THROW_SHIM_MARKER)) {
                text = if (Regex("""(?m)^---\s*$""").containsMatchIn(text)) {
                    text.trimEnd() + "\n" + THROW_SHIM_CXX + "\n"
                } else {
                    text.trimEnd() + "\n---\n" + THROW_SHIM_CXX + "\n"
                }
            }
            dest.writeText(text)
        }

        fun assertNoNameCollision(ohosNames: Set<String>, hmsNames: Set<String>) {
            val clash = ohosNames.intersect(hmsNames)
            check(clash.isEmpty()) {
                "ohos/hms def basename collision (leaf artifactIds must be globally unique): $clash"
            }
        }

        private fun validateDepends(project: Project, defs: List<DefInfo>, known: Set<String>) {
            defs.forEach { d ->
                d.depends.forEach { dep ->
                    check(dep in known) {
                        "${project.path} def ${d.name}: depends token '$dep' is not a known ohos/hms def"
                    }
                }
            }
        }
    }
}

fun Project.capiKlibPublishing(): CapiKlibPublishing =
    extensions.getByType(CapiKlibPublishing::class.java)

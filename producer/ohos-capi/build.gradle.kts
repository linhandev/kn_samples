import org.cpf.kotlin.capi.CapiKlibPublishing
import org.cpf.kotlin.capi.CapiKlibPublishingPlugin

apply<CapiKlibPublishingPlugin>()

plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "org.cpf.kotlin"

base.archivesName.set("ohos-capi")

val defDir = file("$projectDir/nativeInterop/ohosArm64")
val cinteropOutDir = layout.buildDirectory.dir("classes/kotlin/ohosArm64/main/cinterop")
val generatedDefs = layout.buildDirectory.dir("generated/defs")
val prepareDefs = tasks.register("prepareCapiDefs") {
    inputs.dir(defDir)
    outputs.dir(generatedDefs)
    doLast {
        defDir.listFiles { f -> f.extension == "def" }!!.forEach { src ->
            CapiKlibPublishing.materializeDefWithThrowShim(src, generatedDefs.get().file(src.name).asFile)
        }
    }
}


val ohosDefs = CapiKlibPublishing.parseDefs(defDir)
val ohosNames = ohosDefs.map { it.name }.toSet()

data class DefInfo(val name: String, val depends: List<String>)
val defs: List<DefInfo> = ohosDefs.map { DefInfo(it.name, it.depends) }
val defByName = defs.associateBy { it.name }

fun transitiveClosure(name: String): List<String> {
    val seen = linkedSetOf<String>()
    fun visit(n: String) {
        defByName[n]?.depends?.forEach { d ->
            if (d in defByName && seen.add(d)) visit(d)
        }
    }
    visit(name)
    return seen.toList()
}

kotlin {
    ohosArm64 {
        compilations {
            val main by getting {
                cinterops {
                    defs.forEach { d ->
                        create(d.name) {
                            defFile(generatedDefs.get().file("${d.name}.def").asFile)
                            extraOpts("-Xshort-module-name", d.name)
                            extraOpts("-no-default-libs")
                            transitiveClosure(d.name).forEach { dep ->
                                val depDir = cinteropOutDir.map { it.file("ohos-capi-cinterop-${dep}").asFile.absolutePath }.get()
                                extraOpts("-library", depDir)
                            }
                        }
                    }
                }
            }
        }
        compilerOptions.freeCompilerArgs.addAll(
            "-Xklib-duplicated-unique-name-strategy=allow-all-with-warning"
        )
    }
}

fun cinteropTaskName(name: String) = "cinterop${name.replaceFirstChar { it.uppercase() }}OhosArm64"
defs.forEach { d ->
    transitiveClosure(d.name).forEach { dep ->
        tasks.findByName(cinteropTaskName(d.name))?.dependsOn(cinteropTaskName(dep))
    }
}

defs.forEach { d ->
    tasks.named(cinteropTaskName(d.name)).configure {
        dependsOn(prepareDefs)
        val moduleNameField = this.javaClass.superclass.getDeclaredField("moduleName")
        moduleNameField.isAccessible = true
        moduleNameField.set(this, "org.jetbrains.kotlin.native.platform.${d.name}")
    }
}

val klibVersion = property("klibVersion") as String
// ohos defs only depend on ohos defs; hms names checked in :hms-capi.
extensions.getByType(CapiKlibPublishing::class.java).configure(
    project = project,
    defs = ohosDefs,
    bomArtifactId = "ohos-capi",
    knownDefNames = ohosNames,
    archivesName = "ohos-capi",
    klibVersion = klibVersion,
)

// 仅供缺失 API 回归测试使用，不注册 Maven publication。
tasks.register("prepareProtectionProbe") {
    val source = rootProject.file("../tests/protection/probe.def")
    val destination = layout.buildDirectory.file("protection/probe.def")
    inputs.file(source)
    outputs.file(destination)
    doLast { CapiKlibPublishing.materializeDefWithThrowShim(source, destination.get().asFile) }
}

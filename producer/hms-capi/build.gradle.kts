import org.cpf.kotlin.capi.CapiKlibPublishing
import org.cpf.kotlin.capi.CapiKlibPublishingPlugin

apply<CapiKlibPublishingPlugin>()

plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "org.cpf.kotlin"

base.archivesName.set("hms-capi")

// HMS 扩展 Kit klib。headers 在 HMS sysroot; cinterop 显式 -I。
// linkerOpts 的 -l 全是 HMS-only。跨边界 depends 走 leaf POM → ohos leaf 坐标。

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


val repoRoot = rootProject.projectDir.parentFile!!
val hmsInclude = file("$repoRoot/sysroot/sysroot-hms-aarch64-6.0.2.640-02/usr/include")

val ohosCapiProject = project(":ohos-capi")
val ohosCapiDefDir = ohosCapiProject.file("nativeInterop/ohosArm64")

val hmsDefs = CapiKlibPublishing.parseDefs(defDir)
val ohosDefs = CapiKlibPublishing.parseDefs(ohosCapiDefDir)
val hmsNames = hmsDefs.map { it.name }.toSet()
val ohosNames = ohosDefs.map { it.name }.toSet()
CapiKlibPublishing.assertNoNameCollision(ohosNames, hmsNames)
val knownDefNames = ohosNames + hmsNames

data class DefInfo(val name: String, val depends: List<String>)
val defs: List<DefInfo> = hmsDefs.map { DefInfo(it.name, it.depends) }
val hmsDefByName = defs.associateBy { it.name }
val ohosCapiDefByName = ohosDefs.associate { it.name to DefInfo(it.name, it.depends) }

fun transitiveClosure(start: String, graph: Map<String, DefInfo>): List<String> {
    val seen = linkedSetOf<String>()
    fun visit(n: String) {
        graph[n]?.depends?.forEach { d ->
            if (graph.containsKey(d) && seen.add(d)) visit(d)
        }
    }
    visit(start)
    return seen.toList()
}

fun hmsClosure(name: String) = transitiveClosure(name, hmsDefByName)
fun ohosCapiClosure(dep: String) = transitiveClosure(dep, ohosCapiDefByName)
fun isOhosCapiDep(dep: String) = ohosCapiDefByName.containsKey(dep)

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
                            extraOpts("-compiler-option", "-I$hmsInclude")
                            hmsClosure(d.name).forEach { dep ->
                                val depDir = cinteropOutDir.map { it.file("hms-capi-cinterop-${dep}").asFile.absolutePath }.get()
                                extraOpts("-library", depDir)
                            }
                            d.depends.filter { isOhosCapiDep(it) }.forEach { dep ->
                                (listOf(dep) + ohosCapiClosure(dep)).distinct().forEach { transDep ->
                                    val depDir = ohosCapiProject.layout.buildDirectory.dir("classes/kotlin/ohosArm64/main/cinterop")
                                        .map { it.file("ohos-capi-cinterop-${transDep}").asFile.absolutePath }.get()
                                    extraOpts("-library", depDir)
                                }
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
    // No implementation(project(":ohos-capi")): consumer-facing ohos pull-in is via leaf POM
    // deps generated from def depends. Build-time -library paths above still resolve ohos klibs.
}

fun cinteropTaskName(name: String) = "cinterop${name.replaceFirstChar { it.uppercase() }}OhosArm64"
hmsDefs.forEach { d ->
    d.depends.filter { isOhosCapiDep(it) }.forEach { dep ->
        tasks.findByName(cinteropTaskName(d.name))?.dependsOn(":ohos-capi:${cinteropTaskName(dep)}")
    }
    hmsClosure(d.name).forEach { dep ->
        tasks.findByName(cinteropTaskName(d.name))?.dependsOn(cinteropTaskName(dep))
    }
}

hmsDefs.forEach { d ->
    tasks.named(cinteropTaskName(d.name)).configure {
        dependsOn(prepareDefs)
        val moduleNameField = this.javaClass.superclass.getDeclaredField("moduleName")
        moduleNameField.isAccessible = true
        moduleNameField.set(this, "org.jetbrains.kotlin.native.platform.${d.name}")
    }
}

val klibVersion = property("klibVersion") as String
extensions.getByType(CapiKlibPublishing::class.java).configure(
    project = project,
    defs = hmsDefs,
    bomArtifactId = "hms-capi",
    knownDefNames = knownDefNames,
    archivesName = "hms-capi",
    klibVersion = klibVersion,
)

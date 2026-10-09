> 历史说明：本文记录旧聚合发布/链接方案。当前 22-0.5 的独立 leaf、内联异常 shim 和验证步骤见[根目录说明](../README.md)，下文旧版本号和命令不作为当前操作入口。

# kn_sample 设计文档

> 用 cpf 0.4（Kotlin `2.2.21-0.4.0-03`）把 HarmonyOS 独有的 **159 个 ohos-only cinterop def** + 4 个 dist-only def（posix/linux/gles3/glesCommon）封装成一个 Maven klib，
> 给基于 `2.3.20-HUAWEI` 的消费者项目用，并通过 capi-demo 的 UI 自动化测试端到端验证。klib depends 完全自闭环到 `org.cpf.kotlin` 坐标，不依赖消费者 dist 的任何 platformLib。

---

## 1. 目标

| 目标 | 说明 |
|---|---|
| **生产** | 用 cpf 0.4 遍历 163 个 def（159 ohos-only + 4 dist-only: posix/linux/gles3/glesCommon）跑 cinterop，打成 **一个 Maven 坐标** `org.cpf.kotlin:ohos-capi:22-0.1`，发布到仓库内 `m2/`（gitignore，方便检查） |
| **消费** | 两个 `2.3.20-HUAWEI` 消费者依赖该 klib，验证**跨版本 ABI**（cpf 0.4 `abi_version 2.2.0` 被 HUAWEI `2.3.0` 读取，同 major 兼容）下能编译/链接/运行 |
| **验证** | `consumer-capi-demo` 的 `autotest.py` 用 hdc UI 自动化跑 9 模块 CAPI smoke test，139 用例端到端验证 |
| **运行时** | `.so` 不进 HAP，按 soname 走设备 ROM 系统库；NEEDED 只含实际引用的库 |

### 命名与坐标

| 项 | 值 | 说明 |
|---|---|---|
| artifactId | `ohos-capi` | 库封装 159 个 ohos-only def / ~40 个 Kit，HiLog 仅其一，故以"ohos CAPI 聚合"命名 |
| version | `22-0.1-SNAPSHOT` | 快照版本 |
| groupId | `org.cpf.kotlin` | ✅ **已决策** — 参考 gitcode `CPF-KMP-CMP/akinterop` 的命名空间（华为官方 CPF 生态统一 groupId） |

> Gradle 模块名/目录 `producer/ohos-capi/`、cinterop 输出目录前缀 `ohos-capi-cinterop-<Def>`、Maven 路径 `m2/org/cpf/kotlin/ohos-capi/` 均由 artifactId 派生。klib 的 Kotlin `package`（`platform.PerformanceAnalysisKit.HiLog` 等 159 个）与 def/cinterop 名保持 cpf 原版不变——这层名字受 HarmonyOS 官方约束，改了消费者解析不了。

---

## 2. 仓库结构

```
kn_sample/
├── producer/                 # 生产者 · Kotlin 2.2.21-0.4.0-03 (cpf 0.4)
│   ├── ohos-capi/           #   遍历 163 def 跑 cinterop，发布 ohos-capi
│   │   ├── build.gradle.kts  #   自动注册 + 传递闭包 -library + HMS -I
│   │   └── nativeInterop/ohosArm64/*.def   # 163 个 def（159 ohos-only + 4 dist-only，去掉 enableUndefinedApiProtection）
│   ├── static-lib-demo/      #   cinterop + staticLibraries(.a) 示例，消费者自动 link .a
│   ├── ohos-only-defs.txt    #   159 个 ohos-only def 清单
│   ├── settings.gradle.kts   #   eazytec 仓库 + mavenLocal
│   └── gradle.properties     #   kotlinVersion=2.2.21-0.4.0-03
├── consumer-bare/            # 简单消费者 · Kotlin 2.3.20-HUAWEI
│   ├── kotlinApp/            #   最小 demo：HiLog + Asset，验证编译/链接/运行
│   └── settings.gradle.kts   #   devcloud 仓库（BasicAuthentication）+ mavenLocal
├── consumer-capi-demo/       # 复杂消费者 · Kotlin 2.3.20-HUAWEI
│   ├── composeApp/           #   KMP 项目，依赖 ohos-capi，产出 libkn.so
│   ├── harmonyApp/           #   ArkTS + NAPI 桥接 HAP
│   ├── autotest.py           #   UI 自动化（hdc uitest 驱动，9 模块 139 用例）
│   ├── testFiles/用例.csv     #   测试用例清单
│   └── test_reports/         #   CSV + HTML 报告
├── sysroot/                  # check 进仓库的 ohos+hms sysroot（git-lfs 管 .so/.a/.o）
│   ├── sysroot-ohos-aarch64-6.0.2.640-04/   # 主 ohos sysroot（基础 Kit .so stub）
│   └── sysroot-hms-aarch64-6.0.2.640-02/    # HMS sysroot（扩展 Kit 头 + .so stub）
├── design/                   # 本设计文档 + SUMMARY.html（实现总结）
└── run-all.sh                # 端到端：producer publish → bare smoke → capi-demo autotest
```

### 角色与 Kotlin 版本

| 角色 | 路径 | Kotlin | 职责 |
|---|---|---|---|
| 生产者 | `producer/` | `2.2.21-0.4.0-03` (cpf 0.4) | 遍历 163 def（159 ohos-only + 4 dist-only）跑 cinterop，打成一个 Maven 坐标发布到仓库内 `m2/` |
| 消费者 A | `consumer-bare/` | `2.3.20-HUAWEI` | 简单 HiLog demo，最早验证 maven klib 可编译/链接/运行 |
| 消费者 B | `consumer-capi-demo/` | `2.3.20-HUAWEI` | 真实 KMP 项目，9 模块 CAPI smoke test + autotest.py UI 自动化 |

### 工作流

```
producer (cpf 0.4)  ──publish──>  m2/org/cpf/kotlin/ohos-capi  (仓库内，gitignore)
                                                    │
                              consumer-bare (HUAWEI) ┴ consumer-capi-demo (HUAWEI)
                                    implementation("org.cpf.kotlin:ohos-capi:22-0.1")
```

### 前置依赖（本机环境，非本仓库）

- **DevEco Studio** `/Applications/DevEco-Studio.app` — ohos 主 sysroot（基础 Kit .so stub）+ HAP 工具链（ohpm/hvigor/hdc）
- **cpf 0.4 KN dist** `~/.konan/kotlin-native-prebuilt-macos-aarch64-2.2.21-0.4.0-03` — 生产者 KN（embeddable 现从 Maven 解析，可不设 `kotlin.native.home`）
- **HMS sysroot** `sysroot/sysroot-hms-aarch64-6.0.2.640-02` — 扩展 Kit（AppGalleryKit/CANNKit/DeviceSecurityKit/...）的头 + .so stub，DevEco 不自带，已 check 进仓库
- **devcloud Maven 凭据** — 消费者拉 2.3.20-HUAWEI 的 KGP/stdlib，放 `local.properties`（git-ignored）

---

## 3. 生产者实现

### 3.1 自动化 `ohos-capi/build.gradle.kts`

遍历 `nativeInterop/ohosArm64/*.def`，为每个 def 注册一个 cinterop，全部打进同一个 Maven 坐标。关键点：

```kotlin
kotlin {
  ohosArm64 { compilations { val main by getting {
    cinterops {
      defs.forEach { d ->
        create(d.name) {
          defFile("$defDir/${d.name}.def")
          extraOpts("-Xshort-module-name", d.name)              // ① klib 有 short_name，供 depends 解析
          extraOpts("-compiler-option", "-I$hmsInclude")        // ② HMS sysroot 的扩展 Kit 头
          transitiveClosure(d.name).forEach { dep ->            // ③ 传递闭包全部 -library
            extraOpts("-library", "$cinteropDir/ohos-capi-cinterop-${dep}")
          }
        }
      }
    }
  } }
}
// ④ 任务拓扑：cinterop<Name 首字母大写>OhosArm64 dependsOn 闭包内各 dep
fun cinteropTaskName(name: String) = "cinterop${name.replaceFirstChar { it.uppercase() }}OhosArm64"
defs.forEach { d -> transitiveClosure(d.name).forEach { dep ->
    tasks.findByName(cinteropTaskName(d.name))?.dependsOn(cinteropTaskName(dep))
} }

publishing { publications { withType<MavenPublication> { artifactId = "ohos-capi" } } }
```

### 3.2 关键工程处理

| 问题 | 处理 |
|---|---|
| cpf 0.4 dist 的 ohos_arm64 内置 platform klib 与我们要发的 package 同名 | **不清空 dist**（保持原版）。cinterops 生成的 klib `unique_name` 不同（`org.cpf.kotlin:ohos-capi-cinterop-HiLog` vs dist 的 `org.jetbrains.kotlin.native.platform.HiLog`），`package` 相同不冲突。消费者（无 cpf dist 的 ohos platform lib）依赖我们的 maven klib，import 包名跟 cpf 内置一致 |
| 159 个 def 都有 `enableUndefinedApiProtection=true` | **全部删掉**。该 flag 让 cinterop stub 引用 `ThrowIllegalStateExceptionFromCString`，cpf 0.4 runtime 有此符号但 2.3.20-HUAWEI 没有 → 消费者 `libc2k.so` 加载时 relocation 失败 |
| 19 个扩展 Kit def（AIP/CANN/DeviceSecurity/HandWrite/XEngine 等）头找不到 | 全局给每个 cinterops 传 `-compiler-option -I<HMS>/usr/include`（cpf 0.4 的 `additionalTargetSysRoot` 有这些头，但 cinterop 不自动加） |
| cinterop 的 `depends` 只写直接依赖 | 传递依赖由各 klib manifest 链式声明。build 必须用 `-library <dep klib path>` 显式加载传递闭包（`-libraryPath` 只是搜索路径不自动加载）；且让 cinterops 解析传递依赖到**我们的 klib**（`com.example:...`）而非 dist 内置（`org.jetbrains.kotlin.native.platform.*`）——消费者无 dist，depends 必须指向我们的 klib 才能解析 |
| 消费者拉 2.3.20-HUAWEI 的 KGP/stdlib | settings.gradle.kts 加 devcloud 仓库 + `BasicAuthentication` 强制认证头（devcloud 缺凭据返回 403 非 401，Gradle 默认不发认证） |
| cpf 0.4 的 `kotlin-native-compiler-embeddable` 从 Maven 解析 | KGP 的 `downloadKotlinNativeDistribution` 从 eazytec 仓库拉 0.4.0-03 embeddable jar，无需 `kotlin.native.home` 指本地 dist（已去掉） |

### 3.3 publication 覆盖警告（benign）

KMP + `maven-publish` 自动创建**两个** MavenPublication：

- `kotlinMultiplatform` — metadata publication，含 common 的 `.jar` + `.module`（Gradle Module Metadata，记录各 target variant）
- `ohosArm64` — target publication，含 `.klib` + `.pom`

`withType<MavenPublication> { artifactId = "ohos-capi" }` 把两者 artifactId 都设成 `ohos-capi`，发布时 `ohos-capi-22-0.1-SNAPSHOT.pom` 先由 metadata 写一次，再被 target **覆盖**写一次 → 警告 `Multiple publications ... will overwrite each other!`。

**为什么 benign**：Gradle 消费者优先读 `.module`（含 ohosArm64 variant 坐标），按 target 选 `.klib`；`.pom` 被覆盖只影响纯 Maven 消费者。本项目都是 Gradle 消费者，所以能跑。`#17` 曾尝试给两个 publication 不同 artifactId 彻底消除警告，但破坏了 `.module` variant 解析 → **已回退**，benign 警告保留。

### 3.4 static-lib-demo（对比示例）

`static-lib-demo` 用 `staticLibraries(.a)` 嵌入 klib，消费者**自动 link .a**（KGP 对 included `.a` 自动 link，但 `.so` 不自动）。它 `implementation("com.example:ohos-capi")`，消费者拉一个坐标即可同时得到两个 klib。这是与 `.so` 的 `linkerOpts` 方案（需消费者配 `-L`）的对比。

---

## 4. .so 链接策略（最终方案 c）

核心问题：**klib 怎么让消费者 link 时找到 ohos 的 .so**。探索了 4 条路，最终落定 (c)。

| 方案 | 做法 | 结果 | 为什么 |
|---|---|---|---|
| (a) klib 内嵌 .so，消费者解压 -L | cinterops `-staticLibrary` 把 .so 塞进 klib 的 `included/`；消费者 build 解出来 `-L` 指向 | ❌ 否决 | 消费者要手动解压 |
| (b) klib 内嵌 .so，靠 KGP 自动 -L | 同 (a)，期望 KGP 自动加 link 的 -L | ❌ 否决 | KGP 对 maven 传递的 cinterops klib 创建了 `included/` 但**留空**（不解压 .so），link 命令无 -L 指向 included |
| **(c) klib 只声明 linkerOpts，消费者用自己 sysroot** | klib manifest 带 `linkerOpts=-lxxx`；消费者 link 用自己 ohos sysroot（DevEco）的 .so stub；`--as-needed` 让 NEEDED 只含实际 UND 的；soname 让 NEEDED 记裸名 | ✅ 采用 | 消费者本就有 ohos sysroot（DevEco） |
| (d) 用 .a（staticLibraries） | 把 .so 转 .a，`staticLibraries` 嵌入，KGP 自动 link | ❌ 否决 | cpf sysroot 只有 .so 没 .a；.a 静态链入增大体积且不走系统运行时，违背"运行时走 ROM" |

### 关键发现：为什么 .so 不能像 .a 那样自动 link

- cinterops `-staticLibrary` 不校验后缀，能嵌 .so 进 klib 的 `included/`，.a 和 .so 嵌入后 manifest 字段完全一样
- 但 KGP link 时区分处理：对 maven 传递的 cinterops klib，KGP 创建了消费者 build 的 `included/` 目录但**留空**，link 命令也没有 `-L` 指向 included
- 外部印证：socket-quic issue #162（2026-06-13）遇到完全相同问题——"maven 发布的 cinterops klib carries the bindings, not the .a; linkerOpts don't propagate to downstream consumers"。KN 社区也说 ".so 必须在 def 定义的路径，即使消费者项目也要；要自包含只能 staticLibraries + .a"
- **结论**：KGP 当前机制下，"消费者零配置 + .so + 运行时走系统"三者不可兼得。要零配置必须 .a（但 cpf 没 .a 且不走系统）；要 .so 走系统必须消费者配 `-L`

### 方案 (c) 完整链路

```
生产者 klib (cpf 0.4 构建)
  └─ manifest: linkerOpts = -lhilog_ndk.z        // 只声明依赖，不嵌 .so

消费者 link libkn.so (2.3.20-HUAWEI)
  ├─ KN 用 HUAWEI dist 的 targetSysRoot = DevEco native/sysroot   // 主 ohos sysroot，基础 Kit .so stub
  ├─ freeCompilerArgs += "-linker-options=-L<HMS sysroot>/usr/lib/aarch64-linux-ohos"  // 补扩展 Kit .so stub
  ├─ freeCompilerArgs += "-linker-options=-Wl,--as-needed"   // NEEDED 只含实际 UND 的库
  └─ freeCompilerArgs += "-linker-options=-Wl,-soname,libkn.so"  // NEEDED 记裸名，设备 dlopen 成功

运行时(设备)
  └─ libkn.so 的 NEEDED = [libhilog_ndk.z.so, libasset_ndk.z.so, ...实际引用的]
      └─ 设备 ROM 的 ld 按 soname 从系统库解析（基础 Kit 高版本 ROM 都有）
```

### SONAME 问题根因

KN 的 `sharedLib` 产出的 `libc2k.so`/`libkn.so` 二进制里**没有 `DT_SONAME` 字段**。HarmonyOS App 的 NAPI 库 `libentry.so`（CMake 编译）用 `IMPORTED_LOCATION` 绝对路径链接 `libc2k.so` 时，ld 因无 SONAME 把**完整绝对路径**烤进 `libentry.so` 的 `DT_NEEDED` → 设备 dlopen 读到宿主机绝对路径 → 不存在 → namespace 检查失败 → `load module default/entry failed` → `runHelloWorld failed: {}`。

**修复**：`-linker-options=-Wl,-soname,libkn.so`，`DT_SONAME=libkn.so`，NEEDED 记裸名，设备从 App libs 目录解析。**待确认**：是否 HUAWEI 版本特殊（其他 KN 版本在此场景是否也默认不设 soname），未对比。

### NEEDED 精准化实测（bare helloworld）

| 场景 | NEEDED | 说明 |
|---|---|---|
| 无 `--as-needed` | 159 个库全 NEEDED（含未引用的扩展 Kit） | DevEco sysroot 缺扩展 Kit → link 失败；即使 link 过，设备加载时缺库也失败 |
| 有 `--as-needed` | 4 个：`libc.so` `libhilog_ndk.z.so` `libasset_ndk.z.so` `libc++_shared.so` | helloworld 只用 HiLog + Asset，按 UND 精准裁剪。低版本 ROM 只需这 4 个 |

> 注：`--as-needed` 不是"不查找"，是"找到 .so 后判断未引用则不记 NEEDED"。所以 ld 仍要能**找到**所有 `-l` 声明的 .so（在 `-L` 路径），才能判断是否引用——这就是消费者要加 `-L<HMS>` 的原因（即使不用扩展 Kit）。

---

## 5. 消费者侧

### consumer-bare

最小 demo：依赖 maven klib，调用 HiLog + AssetApi→AssetType，验证编译/链接/运行。

```kotlin
// settings.gradle.kts: devcloud 仓库 + BasicAuthentication + mavenLocal
// gradle.properties: kotlinVersion=2.3.20-HUAWEI
// kotlinApp: implementation("org.cpf.kotlin:ohos-capi:22-0.1")
//   + -L<HMS> + -Wl,--as-needed + -Wl,-soname,libc2k.so
```

#### bare demo 消费链路

```mermaid
flowchart TB
    subgraph PROD["生产者 producer/ohos-capi · Kotlin 2.2.21-0.4.0-03 (cpf 0.4)"]
        P1["遍历 159 个 .def 跑 cinterop<br/>每个 def 产一个 klib<br/>extraOpts: -Xshort-module-name=&lt;Def&gt;<br/>+ -I&lt;HMS sysroot&gt;<br/>+ -library &lt;传递闭包 dep klib&gt;"]
        P2["打成单个 Maven 坐标<br/>org.cpf.kotlin:ohos-capi:22-0.1"]
        P1 --> P2
    end

    subgraph KLIB["发布的 klib（160 个文件 → m2/org/cpf/kotlin/ohos-capi/22-0.1-SNAPSHOT/）"]
        K1["main klib<br/>unique_name = com.example:ohos-capi<br/>abi_version = 2.2.0"]
        K2["159 个 cinterop klib<br/>unique_name = org.cpf.kotlin:ohos-capi-cinterop-&lt;Def&gt;<br/>例: org.cpf.kotlin:ohos-capi-cinterop-HiLog<br/>package = platform.PerformanceAnalysisKit.HiLog 等（cpf 原版，不可改）<br/>short_name = &lt;Def&gt;（供 depends 链解析）<br/>manifest: linkerOpts = -l&lt;_ndk.z&gt;（如 -lhilog_ndk.z）"]
        K1 -. depends .-> K2
    end

    P2 --> KLIB

    subgraph BARE["消费者 consumer-bare/kotlinApp · Kotlin 2.3.20-HUAWEI"]
        B1["settings.gradle.kts<br/>仓库内 m2/ + devcloud(BasicAuth)<br/>无 cpf dist → 无内置 ohos platform klib"]
        B2["build.gradle.kts<br/>implementation('org.cpf.kotlin:ohos-capi:22-0.1')<br/>+ implementation('org.cpf.kotlin:static-lib-demo:22-0.1')"]
        B3["Gradle 读 .module 选 ohosArm64 variant<br/>拉 main klib + 用到的 cinterop klib<br/>跨版本 ABI: 2.2.0 被 2.3.0 读（同 major 兼容）"]
        B1 --> B2 --> B3
    end

    KLIB -->|Gradle 解析| B3

    subgraph COMPILE["编译期"]
        C1["helloworld.kt<br/>import platform.PerformanceAnalysisKit.HiLog.OH_LOG_Print<br/>import platform.AssetStoreKit.AssetApi.OH_Asset_FreeBlob<br/>→ AssetApi depends AssetType（a→b 验证传递依赖解析）"]
        C2["@CName('kn_helloworld')<br/>导出供 ArkTS/NAPI 调用"]
        C1 --> C2
    end

    B3 --> C1

    subgraph LINK["链接期 linkDebugSharedOhosArm64 → libc2k.so"]
        L1["linkerOpts 来自 klib manifest<br/>-lhilog_ndk.z / -lasset_ndk.z / ... 159 个"]
        L2["-L&lt;DevEco ohos sysroot&gt;（基础 Kit .so stub，自动）<br/>+ -L&lt;仓库内 HMS sysroot&gt;（扩展 Kit stub，手动补）<br/>+ -Wl,--as-needed → NEEDED 只留实际 UND 的库<br/>+ -Wl,-soname,libc2k.so → DT_SONAME=libc2k.so"]
        L1 --> L2
    end

    C2 --> L1

    subgraph RUN["运行期（设备 ROM）"]
        R1["libentry.so (CMake/NAPI)<br/>DT_NEEDED = libc2k.so（裸名，因 soname）"]
        R2["设备 ld 按裸名从 App libs/ dlopen libc2k.so<br/>libc2k.so NEEDED = libhilog_ndk.z.so / libasset_ndk.z.so / ...<br/>→ 设备 ROM 系统库按 soname 解析<br/>.so 不进 HAP"]
        R1 --> R2
    end

    L2 --> R1
```

> 关键标识：Maven 坐标 `org.cpf.kotlin:ohos-capi:22-0.1`；main klib `unique_name=com.example:ohos-capi`、`abi_version=2.2.0`；cinterop klib `unique_name=org.cpf.kotlin:ohos-capi-cinterop-<Def>`、`short_name=<Def>`、`package=platform.<Kit>.<Def>`（cpf 原版）；klib manifest 声明 `linkerOpts=-l<_ndk.z>` 但不嵌 .so；消费者补 `-L<HMS>` + `--as-needed` + `-soname,libc2k.so`。

### consumer-capi-demo

真实 KMP 项目（composeApp + harmonyApp）：

- 切 2.3.20-HUAWEI 后无内置 platform klib → 删自己的 cinterops，`ohosArm64Main { implementation("org.cpf.kotlin:ohos-capi:22-0.1") }`；项目用到的 15 个 platform 包被 159 def 全覆盖
- `binaries.sharedLib { baseName = "kn" }` 产 `libkn.so`，配 `-L<HMS>` + `-soname,libkn.so` + `--as-needed`
- `autotest.py` 用 hdc `uitest dumpLayout` + `uiInput click` 驱动 UI：启动 App → 逐模块点"运行验证" → 读页面清单统计 → 生成 CSV/HTML 报告

### autotest 模块（9 模块，139 用例）

| 模块 | 结果 |
|---|---|
| RDB / CommonEvent / HuksKeyApi / NetConnection / HiAppEvent / HiLog / Drawing | ✅ 通过 |
| Failure（故意传坏参数验证错误码，7 用例） | ⚠️ 预期错误返回，脚本标"失败"实为正确 |
| VersionGuard（7 函数+2 常量） | ✅ 7/7 成功 |

报告：`test_reports/test_report.csv` + `test_report.html`（139 用例 = 132 成功 + 7 Failure 预期错误）。`Failure` 模块返回预期错误码判成功（#9）。

---

## 6. 当前状态（2026-06-29）

### 已完成并 commit 的改动

| # | 改动 | 状态 |
|---|---|---|
| 7 | SUMMARY 修正：删 devcloud 凭据/IR001/bundleName/签名/klib 自带 .so 等非问题条目；合并 NEEDED 目标+目的 | ✅ 完成 |
| 8 | 解释 SONAME 问题根因 + publication 覆盖警告根因 | ✅ 完成 |
| 9 | autotest Failure 模块返回预期错误码判成功（139 用例 0 失败） | ✅ 完成 |
| 10 | ohos+hms sysroot 重新下载，check 进仓库 sysroot/（git-lfs），生产者用仓库内路径，消费者只用 DevEco | ✅ 完成 |
| 11 | 去掉 producer kotlin.native.home，cpf 0.4 embeddable 从 Maven（eazytec）下载 | ✅ 完成 |
| 12 | 不清空 cpf 0.4 dist 的 ohos_arm64（保持原版），package 不变，靠 unique_name 区分 | ✅ 完成 |
| 13 | def 的 depends 只写直接依赖（传递由 build 的 transitiveClosure -library 处理） | ⚠️ **不完整** — 只改了文档（cb95c46 仅改 SUMMARY），build 未真正让 depends 指向我们的 klib，见 #18 根因 |
| 15 | static-lib-demo：cinterop + staticLibraries(.a) 示例，消费者自动 link .a（对比 .so 的 linkerOpts） | ✅ 完成 |
| 17 | 解决 publication 覆盖警告 | ❌ **回退** — artifactId 拆分破坏 .module variant 解析，回退到 withType（benign 警告保留） |
| 14 | 拆分 ohos 主 140 + hms 扩展 19 两个坐标 | ❌ **回退** — 拆分后触发 bare 编译回归，回退到单 ohos-capi（159） |
| 16 | 增加 ohosX64 支持 | ⚠️ 未做 — 依赖 #14 |
| 18 | **修复 bare 回归**：cinterop 加 `-no-default-libs` + 把 dist-only def(posix/linux/gles3/glesCommon) 纳入 producer，让 klib depends 自闭环到 com.example | ✅ **完成** — run-all 全流程通过（见下） |

### ✅ bare 回归已解决（2026-06-29，#18）

**根因**（通过 `~/git/reference/kotlin` cinterop 源码定位）：生产者 cinterop klib 的 manifest `depends` 写成 cpf 0.4 dist 的 platform lib 全名（`org.jetbrains.kotlin.native.platform.NeuralNetworkRuntime`），而非我们自己的 `org.cpf.kotlin:ohos-capi-cinterop-NeuralNetworkRuntime`。消费者 HUAWEI dist(2.3.20) 把 `NeuralNetworkRuntime` 改名 `NeuralNetworkRuntimeKit`、删了 `glesCommon` → KLIB resolver 找不到 → ohos-capi 整条 depends 链加载中断 → 所有 `platform.*` unresolved。

cinterop 默认从 dist 搜索路径加载，def 的 `depends = NeuralNetworkRuntime`（短名）按 short_name 解析到 dist 同名 klib 写进 manifest。`-library` 传我们的 klib 只供 stub 引用符号，不改 manifest depends 记录的 unique_name。

**解法**：
1. cinterops 加 `extraOpts("-no-default-libs")` —— 模仿 cpf `GeneratePlatformLibraries.kt`，禁用 dist 自动加载，depends 只解析到我们 `-library` 传的 com.example klib。验证：所有 klib 的 depends 不再引用任何 `org.jetbrains.kotlin.native.platform.*`
2. 把 dist-only def（posix/linux/gles3/glesCommon）从 cpf 0.4 dist `konan/platformDef/ohos_arm64/` 拷进 producer —— 现在 producer 共 163 def，posix 等也成为 `org.cpf.kotlin:ohos-capi-cinterop-posix` klib，**depends 完全自闭环，不假定 consumer dist 有任何 platformLib**

**端到端验证**：run-all 全流程通过 —— producer 发 164 klib；bare compile+link 过，设备 hilog 抓到 `posix getenv(PATH)=/data/app/bin:...`（Linux 基础库从 com.example klib 解析、compile/link/运行全通）；capi-demo autotest 139 用例 0 失败。libc2k.so NEEDED 只 4 个（libc/hilog/asset/c++_shared），`--as-needed` 丢弃 posix.def 的 `-lresolv/-lutil/-lcrypt/-lrt` 等未引用库。

> posix.def 的 linkerOpts 是 Linux 系统库（-lresolv -lm -lpthread -lutil -lcrypt -lrt），ohos sysroot 都没有，但 `--as-needed` 丢弃未引用的所以 link 过、运行只走 libc.so。若未来某模块真用到 resolv/util/crypt 符号，需改 posix.def 适配 ohos（ohos musl libc 已合并 pthread/math）。

---

## 7. 遗留问题与注意事项

### 真实 gap（待解）

- **消费者要加 `-L<HMS sysroot>`**：聚合 klib 带了 19 个扩展 Kit def 的 `linkerOpts`，`--as-needed` 下 ld 仍要找到这些 .so 才能判断未引用。consumer-bare/capi-demo 都加了这行（指向仓库内 sysroot）。待 #14 拆分重新做后，不用扩展 Kit 的消费者无需此 -L
- **#14 拆分待重新做**：ohos 主 140 + hms 扩展 19 两个坐标。首次尝试触发 bare 回归（根因已在 #18 修复，重做时不再回归）。重做时需确保 hms-klib 的 maven 坐标依赖 ohos-capi 坐标，且不破坏消费者解析
- **#17 publication 警告待解**：artifactId 拆分破坏 .module variant 解析，回退 withType（benign 警告保留）。需找别的方式消警告
- **ohosX64 待支持**（#16）：当前 klib 只发 ohosArm64，capi demo 的 x64 target 禁用。依赖 #14
- **覆盖范围**：159 ohos-only + 4 dist-only(posix/linux/gles3/glesCommon) = 163 def 已进 maven klib。其余共享 def（gles31/gles32/vulkan 等）已在 producer（vulkan.def/gles32.def 是 ohos-only 集合内的），但 gles31 仍未纳入
- **SONAME 待确认**：KN sharedLib 默认不设 DT_SONAME，`-Wl,-soname` 修复有效。是否 HUAWEI 版本特殊未对比

### 已解决（run-all 全流程验证通过，2026-06-29）

- **bare 回归**（#18）：cinterop `-no-default-libs` + dist-only def 纳入 producer，depends 自闭环到 com.example
- 跨版本 ABI：cpf 0.4（abi 2.2.0）构建的 klib 被 2.3.20-HUAWEI（2.3.0）读取 —— 同 major 兼容
- 符号导出：消费者 `libkn.so` 的 NEEDED 含 `libhilog_ndk.z.so`/`libasset_ndk.z.so`/`libhuks_ndk.z.so` 等，设备进程 maps 全部加载
- 163 def 全 cinterop 成功，0 失败
- NEEDED 精准化：`--as-needed` 让 libkn.so 只 NEEDED 实际 UND 的库（bare helloworld 只 NEEDED 4 个）
- soname：`-Wl,-soname,libkn.so` 让 NEEDED 记裸名，设备 dlopen 从 App libs 目录成功
- 运行时走系统：NEEDED 是裸 soname，设备 ROM 的 ld 按名从系统库解析，.so 不进 HAP
- sysroot 进仓库（#10）：ohos+hms sysroot check 进 `kn_sample/sysroot/`（git-lfs）
- embeddable 从 Maven 下（#11）；dist 不清空（#12）；static-lib-demo（#15）；autotest Failure 判成功（#9）

### 非问题（已从 gap 列表删除）

devcloud 需要密码 / HUAWEI KGP/stdlib 来自 devcloud / IR001 签名 bundleName / klib 自带 .so 供 ld / cpf 0.4 embeddable 无 Maven 坐标 —— 均不是真问题。

### 未做的事

- commonTest 的 JVM 执行：capi demo 的 commonTest 跑 Android 单元测试需 ANDROID_HOME，未配置；ohosArm64 侧已通过 autotest.py 设备测试验证（回归前）
- CI 化：整套仍是本地手动跑，未写成 CI 脚本

---

## 8. 关键文件

| 文件 | 作用 |
|---|---|
| `producer/ohos-capi/build.gradle.kts` | 生产者：自动遍历 def + 传递闭包 -library + HMS -I + 发布 |
| `producer/static-lib-demo/build.gradle.kts` | .a 嵌入 klib 示例，消费者自动 link |
| `producer/ohos-only-defs.txt` | 159 个 ohos-only def 清单（ohos 有、其他 target 无） |
| `producer/PLAN.md` | 原始 6 步计划 |
| `consumer-capi-demo/composeApp/build.gradle.kts` | 消费者：依赖 ohos-capi + `-L<HMS>` + soname + --as-needed |
| `consumer-capi-demo/autotest.py` | UI 自动化测试（hdc uitest 驱动，9 模块 139 用例） |
| `consumer-capi-demo/test_reports/` | 测试报告 CSV + HTML |
| `run-all.sh` | 端到端：producer publish → bare smoke → capi-demo autotest |
| `design/SUMMARY.html` | 实现总结与问题（本 md 的 HTML 姊妹篇） |
| `m2/org/cpf/kotlin/ohos-capi/22-0.1-SNAPSHOT/` | 发布的 164 个 klib（仓库内 m2/，gitignore） |

---

## 9. 端到端验证流程（run-all.sh）

1. **前置检查**：java / hdc / DevEco / HMS sysroot / 设备 / devcloud 凭据
2. **producer** `:ohos-capi:publish :static-lib-demo:publish` → 期望 164 klib，发到仓库内 `m2/`
3. **consumer-bare** `:kotlinApp:linkDebugSharedOhosArm64` + `startHarmonyAppDebug` → 验证 KN hilog（含 posix getenv）+ ArkTS greeting
4. **consumer-capi-demo** `:composeApp:publishDebugBinariesToHarmonyApp` + ohpm install + hvigor assembleHap + install HAP
5. **autotest.py** → 9 模块 139 用例，解析 `测试结果统计` 块（成功/失败/总计）

> ✅ 2026-06-29 run-all 全流程通过：producer 164 klib / bare KN hilog + ArkTS greeting / capi-demo 139 用例 0 失败。

---

*生产者 Kotlin `2.2.21-0.4.0-03` · 消费者 Kotlin `2.3.20-HUAWEI` · 端到端验证：autotest.py 9 模块 139 用例（全绿）*

# 独立 CAPI Maven 封装

以 Aliyun Maven `org.cpf.kotlin:*:22-0.5` 为实现基线，用 CPF Kotlin `2.2.21-0.5.0-02` 生成 OHOS ARM64 CAPI KLIB。消费者默认使用 `2.3.20-HUAWEI`。

- `producer/ohos-capi`：144 个独立 leaf；`ohos-capi` 仅发布聚合依赖的 POM/Gradle Module Metadata，不含 KLIB。
- `producer/hms-capi`：19 个独立 leaf；`hms-capi` 同样仅聚合依赖。跨 OHOS 的依赖直接指向相应 leaf。
- leaf 坐标为 `org.cpf.kotlin:<def 名>:22-0.5`；KLIB 保留 `org.jetbrains.kotlin.native.platform.<def 名>`，业务 KLIB 不需要重写 manifest。
- 151 个 C++ def 开启 `enableUndefinedApiProtection`。生成 def 时内联同一个 weak throw shim，进入 Kotlin runnable 态后抛出可捕获异常；没有单独的 `InteropApiProtection` 包，也不调用 `abort()`。
- 12 个非 C++ def 不启用该保护。保护检查的是函数符号缺失，不能解决整个系统共享库缺失导致的加载失败。
- def 保留普通 `-l`。消费者使用 `-Xpurge-user-libs` 裁剪未使用的 KLIB；不要给这些 weak API 引用加 `--as-needed`。

## 本地构建

需要 JDK 21、CPF Native 发行包及其依赖，Git LFS 中的 HMS stub 与静态库。生产者版本在 `producer/gradle.properties` 中固定。

```bash
git lfs pull
export KOTLIN_NATIVE_HOME=/path/to/cpf-native
export GRADLE_USER_HOME=/path/to/session/gradle-home
./publish-ohos-capi.sh
```

默认只发布到仓库内 `m2/`，测试用 `biz-klib`、`static-lib-demo` 也只注册本地发布仓库。两处消费者的 `org.cpf.kotlin` 依赖限定从该目录解析，避免测试意外使用远端包。不要把机器路径、凭据、签名材料或产物提交到 Git。

## 核对发布内容

```bash
python3 verify-publication.py --llvm-bin /path/to/llvm/bin
# reference 是只读下载目录，层级为 org/cpf/kotlin 下的各 artifactId。
python3 verify-publication.py --reference /path/to/reference \
  --llvm-bin /path/to/llvm/bin --output /path/to/comparison.json
```

检查 BOM 集合、leaf 依赖、module 中 SHA-256、platform unique_name、防护标志和 bitcode 中的 weak shim。与参考包比较时分别报告压缩包字节、解包内容、Kotlin 元数据与 manifest；`includedHeaders` 的源路径哈希单独排除，不能将 ZIP 哈希不同直接解释成 API 不同。

## 消费者与运行验证

`2.3.20-HUAWEI` 的 KGP、插件 marker 和 Native 发行包从 [Aliyun colab Maven](https://packages.aliyun.com/687e79a0e94e043d2d0f76ea/maven/colab/) 获取，旧华为 Maven 源已移除。凭据通过 `COLAB_MAVEN_USER` / `COLAB_MAVEN_PASS` 或各消费者的 `local.properties` 配置，文件键为 `colabMavenUser` / `colabMavenPass`。不要将密码写进命令行或报告。工具链仓库只匹配 HUAWEI 版本，CAPI 包仍限定来自本地 `m2`。

macOS ARM64 Native 包的精确坐标路径为：

```text
org/jetbrains/kotlin/kotlin-native-prebuilt/2.3.20-HUAWEI/kotlin-native-prebuilt-2.3.20-HUAWEI-macos-aarch64.tar.gz
```

这个旧发行包的默认 NDK 下载地址已不可用。使用已有 OHOS LLVM 19 和 sysroot 时，在本地 Native 发行包副本的 `konan/konan.properties` 中配置以下路径（不提交机器配置）：

```properties
dependencies.macos_arm64-ohos_arm64 =
targetToolchain.macos_arm64-ohos_arm64 = /path/to/ohos-llvm-19
targetSysRoot.ohos_arm64 = /path/to/ohos-sysroot
```

本轮使用 OHOS LLVM 19.1.7 和 `sysroot-ohos-aarch64-6.0.2.640-04`，保留原发行包的编译器与 runtime。通过 `-Pkotlin.native.home=/path/to/huawei-native` 指向该副本，或运行组合脚本时设置 `CONSUMER_NATIVE_HOME`。

```bash
# 同一业务代码分别经 BOM、leaf 解析；Debug 与 Release 都链接。
cd consumer-bare
./gradlew :kotlinApp:linkDebugSharedOhosArm64 :kotlinApp:linkReleaseSharedOhosArm64 -PcapiDependencyMode=bom
./gradlew :kotlinApp:linkDebugSharedOhosArm64 :kotlinApp:linkReleaseSharedOhosArm64 -PcapiDependencyMode=leaf
```

```bash
# 回到仓库根目录。生成故意缺失的 C API，检查异常可捕获且进程继续运行。
export CONSUMER_NATIVE_HOME=/path/to/huawei-native
export HDC_DEVICE=<leased-device-id>
./verify-protection.sh
# 本地发布、BOM/leaf 编译和保护测试的组合入口：
./run-all.sh
```

设备访问前应按开发环境约定取得租约。`verify-protection.sh` 在设备上分别执行 Debug/Release，断言三次缺失 API 调用均抛出 `IllegalStateException`，并检查前后的正常 C 调用。未设置 `HDC_DEVICE` 时只编译，不代表运行验证通过。

`consumer-capi-demo` 保留完整 UI 用例，可执行 `:composeApp:publishDebugBinariesToHarmonyApp`，再在其 `harmonyApp` 中使用 `devecocli run --device <id>` 构建、安装、启动，最后以同一个 `HDC_DEVICE` 运行 `autotest.py`。签名配置属于本地环境。

本轮实测结果及未完成的 HUAWEI/设备验证，见 [对齐验证记录](ALIGNMENT-22-0.5.md)。

原 Cursor plan 的“独立 shim leaf + abort”是中间方案，与已发布 `22-0.5` 不同。本分支以已发布产物为准，采用内联、可捕获异常的实现。原始设计资料保留在 `design/`，阅读时以当前说明和实测报告为准。

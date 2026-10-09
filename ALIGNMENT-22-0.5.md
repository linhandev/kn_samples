# Maven 22-0.5 对齐验证

验证日期：2026-10-09。生产者为 CPF Kotlin 2.2.21-0.5.0-02，OHOS ARM64。
参考输入为 Aliyun colab 仓库当日 metadata 中的最新版本 22-0.5。读取远端产物用于比对，未向远端 Maven 上传。

## 已完成的验证

| 检查 | 实测结果 |
|---|---|
| OHOS leaf 全量构建 | 144/144 |
| HMS leaf 全量构建 | 19/19 |
| 本地与远端 KLIB 完整文件字节一致 | 163/163 |
| 本地与远端 POM 完整文件字节一致 | 165/165 |
| 本地与远端 Gradle Module Metadata 完整文件字节一致 | 165/165 |
| KLIB 解包内容、Kotlin 元数据一致 | 163/163 |
| C++ weak throw shim、线程状态 guard、异常调用、无 abort | 151/151 |
| 非 C++ 不注入 shim | 12/12 |
| biz-klib、static-lib-demo 本地 Maven 发布 | 通过 |
| consumer-bare，CPF 工具链，BOM × Debug/Release | 2/2 链接通过 |
| consumer-bare，CPF 工具链，leaf × Debug/Release | 2/2 链接通过 |
| consumer-bare，HUAWEI 工具链，BOM × Debug/Release | 2/2 链接通过 |
| consumer-bare，HUAWEI 工具链，leaf × Debug/Release | 2/2 链接通过 |
| 故意缺失 API 的测试程序，CPF/HUAWEI 工具链 | 两套工具链的 Debug/Release 均编译通过 |
| Shell/Python 语法、git diff --check | 通过 |

总计 493 个正式发布文件逐字节相同，无需对 ZIP 时间戳、绝对路径或 manifest 做规范化即可复现远端结果。两个 Maven 版本索引不属于本地产物的一致性判定范围。

生产者生成各 def 的完整依赖闭包，保留 platform unique_name。151 个 C++ def 内联 weak throw shim；155 个 def 去除旧的 as-needed 包装；uv 依赖对齐为 posix。没有独立 InteropApiProtection leaf。

本地测试修正了旧示例的重复 publication 坐标覆盖、CPF/HUAWEI SONAME 参数差异，并移除不再使用的 manifest transform 和 linkerOpts 包装脚本。测试包仅注册本地发布仓库。

## 验证范围限制

- 已补齐 2.3.20-HUAWEI 消费者编译/链接验证：KGP 及依赖从 Aliyun colab 解析，Native 从该仓库的精确版本坐标下载。包内 `compilerVersion=2.3.20-HUAWEI`，stdlib 的编译标记为 `2.3.20-OHOS101`。
- 旧华为 Maven 未认证返回 401，找到本地已有凭据后访问仍返回 403；两个消费者已移除该源。Aliyun 对应版本索引及 Native SHA-1 sidecar 返回 404，但精确产物坐标可下载；Native 下载大小与服务端 Content-Length 一致，gzip 完整性检查通过。
- 原 Native 包仍指向旧 NDK 内网下载地址，该地址实测返回 502。仅在本任务的 Native 副本中覆盖依赖路径，使用本机 OHOS LLVM 19.1.7 与 sysroot-ohos-aarch64-6.0.2.640-04；未更改编译器 JAR、stdlib 或 runtime。机器路径没有提交。配置方法见 README。
- 本轮没有设备运行结果：真机已有其他任务的租约；API 22、API 26 模拟器启动后在超时内均未出现在 HDC，已停止。等待真机的本任务队列已取消。
- 因此不能将测试程序“编译通过”报告成“缺失 API 的异常捕获已运行通过”，也未宣称完整 HAP/UI 用例通过。
- 当前强证据是生产者可复现远端全部正式文件。HUAWEI 的四组消费者链接验证已通过；设备回归仍可用根目录 README 的命令继续。

## Aliyun Native 输入

坐标路径：`org/jetbrains/kotlin/kotlin-native-prebuilt/2.3.20-HUAWEI/kotlin-native-prebuilt-2.3.20-HUAWEI-macos-aarch64.tar.gz`。

- 大小：241844354 字节。
- 本地计算的 SHA-256：`e41ca6ab4e4083d61661375bb9910ef33d57fbfd3c0043622f6313d2300bf783`。
- 这是下载包的本地指纹，不是与仓库 checksum sidecar 比对的结果。

## 重跑

`publish-ohos-capi.sh` 默认只发布仓库内 m2。使用 `verify-publication.py --reference <只读参考目录> --llvm-bin <LLVM bin> --output <报告路径>` 核对产物；报告保留各 KLIB SHA-256 和差异文件列表。

`run-all.sh` 执行本地发布、默认 HUAWEI 的 BOM/leaf 编译，以及 `verify-protection.sh`。设置 CONSUMER_NATIVE_HOME 指定消费者 Native；指定 HDC_DEVICE 后才会执行设备端的 Debug/Release 程序并检查三次异常捕获、后续调用和退出状态。设备访问须先取得租约。

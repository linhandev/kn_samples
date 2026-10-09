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
| 故意缺失 API 的测试程序，CPF 工具链 | Debug/Release 均编译通过 |
| Shell/Python 语法、git diff --check | 通过 |

总计 493 个正式发布文件逐字节相同，无需对 ZIP 时间戳、绝对路径或 manifest 做规范化即可复现远端结果。两个 Maven 版本索引不属于本地产物的一致性判定范围。

生产者生成各 def 的完整依赖闭包，保留 platform unique_name。151 个 C++ def 内联 weak throw shim；155 个 def 去除旧的 as-needed 包装；uv 依赖对齐为 posix。没有独立 InteropApiProtection leaf。

本地测试修正了旧示例的重复 publication 坐标覆盖、CPF/HUAWEI SONAME 参数差异，并移除不再使用的 manifest transform 和 linkerOpts 包装脚本。测试包仅注册本地发布仓库。

## 验证范围限制

- 本轮没有完成 2.3.20-HUAWEI 消费者验证：本机未找到该 Native 发行包，华为 Maven 未认证请求返回 HTTP 401。CPF 测试不能证明 HUAWEI runtime 的 ABI。
- 本轮没有设备运行结果：真机已有其他任务的租约；API 22 模拟器启动后在超时内未出现在 HDC，已停止。等待真机的本任务队列已取消。
- 因此不能将测试程序“编译通过”报告成“缺失 API 的异常捕获已运行通过”，也未宣称完整 HAP/UI 用例通过。
- 当前强证据是生产者可复现远端全部正式文件。HUAWEI 和设备回归的可执行入口已提供，补足环境后可用根目录 README 的命令继续验证。

## 重跑

`publish-ohos-capi.sh` 默认只发布仓库内 m2。使用 `verify-publication.py --reference <只读参考目录> --llvm-bin <LLVM bin> --output <报告路径>` 核对产物；报告保留各 KLIB SHA-256 和差异文件列表。

`run-all.sh` 执行本地发布、默认 HUAWEI 的 BOM/leaf 编译，以及 `verify-protection.sh`。设置 CONSUMER_NATIVE_HOME 指定消费者 Native；指定 HDC_DEVICE 后才会执行设备端的 Debug/Release 程序并检查三次异常捕获、后续调用和退出状态。设备访问须先取得租约。

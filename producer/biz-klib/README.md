> 历史说明：本文记录旧聚合发布/链接方案。当前 22-0.5 的独立 leaf、内联异常 shim 和验证步骤见[根目录说明](../../README.md)，下文旧版本号和命令不作为当前操作入口。

# biz-klib —— patch depends 穿刺的生产端

> 配合 `consumer-bare/kotlinApp` 的 `patchBizKlib` task,验证「盘上 patch klib manifest depends」这条路。
> 设计详见 `~/dl/task/独立capi封装/场景1-patch实现对比.html`。

## 这个模块是什么

一个**业务 klib**:用 cpf 0.4(`2.2.21-0.4.0-03`)编译,代码里 `import platform.PerformanceAnalysisKit.HiLog.*` 调 `OH_LOG_Print`。

**关键设计**:本模块**不加 `-no-default-libs`**。所以 `import platform.PerformanceAnalysisKit.HiLog` 解析到 cpf 0.4 dist 的内置 platformLib `org.jetbrains.kotlin.native.platform.HiLog`,于是本 klib 的 manifest `depends` 写:

```
depends=stdlib org.jetbrains.kotlin.native.platform.HiLog
unique_name=com.example:biz-klib
```

`org.jetbrains.kotlin.native.platform.HiLog` 是 CPF 原坐标。HUAWEI 消费者 dist 里**没有**这个库(CPF 170 个 platform klib,HUAWEI 仅 62 个,差 108 个全是 OHOS CAPI 绑定),直接依赖会解析失败。

## 消费者侧怎么用

`consumer-bare/kotlinApp` 不直接 `implementation("com.example:biz-klib")`,而是:

1. `bizKlibRaw` configuration 从 m2 拉原始 biz-klib 文件。
2. `patchBizKlib` task:zip 解包 → 把 manifest `depends` 里的 `org.jetbrains.kotlin.native.platform.HiLog` 改成 `org.cpf.kotlin:ohos-capi-cinterop-HiLog`(m2 里 `ohos-capi` 聚合自带) → 重打包到 `build/patched-klib/biz-klib.klib`。
3. `implementation(files(patchBizKlibTask))` 把 patched klib 作为文件依赖喂给编译。

验证:`./gradlew :kotlinApp:linkDebugSharedOhosArm64` 成功,证明 patch 后 konanc 按 `org.cpf.kotlin:ohos-capi-cinterop-HiLog` 解析到了 ohos-capi 聚合里的 HiLog klib。

## 为什么不选 posix

两边 dist 都有 `org.jetbrains.kotlin.native.platform.posix`(unique_name 完全一致),业务 klib depends posix 在 HUAWEI 侧能直接解析,不触发 patch。HiLog 才是「CPF 有、HUAWEI 无」的正确目标。

## 发布

```bash
cd producer
env kotlin.native.home=/Users/ohoskt/.konan/kotlin-native-prebuilt-macos-aarch64-2.2.21-0.4.0-03 \
  ./gradlew :biz-klib:publish --no-daemon
```

发布后检查 manifest:
```bash
unzip -p m2/org/cpf/kotlin/biz-klib/22-0.1-SNAPSHOT/*.klib default/manifest | grep depends
# 期望:depends=stdlib org.jetbrains.kotlin.native.platform.HiLog
```

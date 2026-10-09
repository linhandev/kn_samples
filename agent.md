# 开发约定

当前发布结构、版本和验证命令以根目录 README.md 为准。

- 每个 def 一个 leaf，两个 BOM 只含依赖元数据；不要重新引入聚合 KLIB 或独立 shim leaf。
- C++ def 通过 buildSrc 统一生成内联 weak throw shim，不重写业务 KLIB 的 manifest。
- 不对 weak API 引用增加 `--as-needed`，消费者使用 `-Xpurge-user-libs`。
- 默认只向仓库内 m2 发布。远端发布必须获得当前任务的明确授权。
- 使用独立 GRADLE_USER_HOME。设备命令指定 HDC_DEVICE，并按当前开发环境要求获取租约。
- 不提交 local.properties、签名配置、凭据和构建产物。
- 本地发布后运行 verify-publication.py；BOM/leaf 以及缺失 API 的 Debug/Release 检查见 run-all.sh。
- 编译通过、KLIB 静态一致、设备运行通过分别报告，不能相互替代。

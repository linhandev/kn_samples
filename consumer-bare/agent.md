# 简单消费者

遵循仓库根目录 agent.md 和 README.md。`capiDependencyMode=bom|leaf` 控制依赖模式，默认 BOM。
消费者直接解析 platform unique_name，不注册 manifest 修改 transform。
用 `publishDebugBinariesToHarmonyApp` 或 Release 对应任务复制产物，再由 devecocli 构建运行 HAP。

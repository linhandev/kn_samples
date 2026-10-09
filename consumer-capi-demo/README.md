> 历史说明：本文记录旧聚合发布/链接方案。当前 22-0.5 的独立 leaf、内联异常 shim 和验证步骤见[根目录说明](../README.md)，下文旧版本号和命令不作为当前操作入口。

# KMP CAPI 自动化验证示例

本项目是一个 Kotlin Multiplatform (KMP) + HarmonyOS 示例工程，聚焦通过 Kotlin/Native 与 HarmonyOS C-API 进行互操作，并提供“其他语法模块”的自动化验证能力（常量/宏/函数清单统计 → 报告）。

## 项目结构

- composeApp/
  - src/commonMain: 跨平台通用逻辑
  - src/ohosArm64Main: HarmonyOS 侧 Kotlin/Native 实现与 cinterop
- harmonyApp/
  - entry/src/main/cpp: C++ NAPI 桥接与导出
  - entry/src/main/ets: ArkTS 页面（包含模块列表与“运行验证”入口）
- autotest.py: 自动化测试主程序（通过 hdc 抓取页面文本并解析清单/常量/宏）
- run_test.sh: macOS/Linux 一键流程（编译/部署/自动化/报告）
- run_test.ps1: Windows 一键流程（编译/部署/自动化/报告）
- testFiles/用例.csv: 测试用例清单（编号、名称、预期等）
- test_reports/: 自动化报告输出目录（HTML/CSV/调试日志）

## 环境准备

- JDK 17（由 Gradle Wrapper 驱动，无需单独安装 Gradle）
- DevEco Studio（构建/调试 HarmonyOS 应用）
- HarmonyOS 工具链 hdc（加入 PATH）
- Python 3.7+（用于运行 autotest.py）
- 真机设备连接正常（hdc list targets 可见）

## 构建与部署

- macOS:
  - 构建并发布 KMP 产物到 harmonyApp:
    - ./gradlew :composeApp:publishDebugBinariesToHarmonyApp
  - 使用 DevEco Studio 打开 harmonyApp 并运行 entry，或参考下一节使用脚本自动安装

- Windows:
  - 构建并发布:
    - ./gradlew :composeApp:publishDebugBinariesToHarmonyApp
  - 使用 DevEco Studio 打开 harmonyApp 并运行 entry，或参考下一节使用脚本自动安装

## 自动化执行

- macOS:
  - 完整流程（编译+部署+自动化）:
    - ./run_test.sh
  - 跳过编译（仅部署+自动化）:
    - ./run_test.sh --skip-build
  - 仅编译:
    - ./run_test.sh -b

- Windows（PowerShell）:
  - 若策略限制，先使用:
    - powershell -ExecutionPolicy Bypass -File .\run_test.ps1
  - 完整流程:
    - .\run_test.ps1
  - 跳过编译:
    - .\run_test.ps1 --skip-build
  - 仅编译:
    - .\run_test.ps1 -b

脚本能力（两端一致）：
- 构建 KMP 产物并发布至 harmonyApp
- 尝试自动安装 hap 并启动应用（失败则提示 DevEco 手动部署）
- 启动“其他语法模块”并点击“运行验证”，等待模块完成后抓取页面文本
- 解析“清单统计/常量列表/宏定义列表/失败与未执行明细”，生成报告

## 报告

- 输出位置:
  - test_reports/test_report.html
  - test_reports/test_report.csv
  - test_reports/debug_all_modules.txt（调试日志）
- 解析规则要点：
  - 清单统计：按页面“清单统计”段落逐条提取 OH_* 函数状态
  - 宏定义列表：按“宏定义列表”段解析“一致/不一致”→ 成功/失败
  - 常量列表：按“常量列表”段或页面“→ [section] NAME = …”行解析常量与状态；若对照汇总出现 MISMATCH 明细，则标记失败并附原因
  - 名称归一化：匹配时忽略括号尾注（如 CH_SET_* (1<<n)）并大小写不敏感，减少“未找到”

## 常见问题排查

- hdc not found
  - 将 HarmonyOS 工具链加入 PATH；验证 hdc list targets
- 设备未连接
  - 检查 USB 调试与授权，确保 hdc list targets 能看到设备
- Windows 运行脚本被阻止
  - 使用 powershell -ExecutionPolicy Bypass -File .\run_test.ps1
- Windows 自动化出现 UnicodeDecodeError
  - 已在脚本中强制以 UTF-8 读取 hdc 输出；若仍异常，请提供报错首行以便定位
- hap 未找到
  - 先执行构建发布任务；或在 DevEco 中构建后再执行脚本
- 自动安装失败
  - 使用 DevEco Studio 运行 entry 到设备后按提示回车继续

## 提示

- 若仅需快速查看自动化结果，可直接跳过编译使用 --skip-build
- 如需扩展更多模块或页面字段，请在页面“运行验证”输出内增加明确的“清单/常量/宏”行，脚本会自动解析

---

Enjoy KMP x HarmonyOS Interop & Automation!

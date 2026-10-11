# EPLAN 标签助手 PC · 0.1.0 初版

Windows 10/11 x64 的离线桌面工具。读取 EPLAN 导出的带文字 PDF，提取号码标签，查看来源、编辑结果并直接生成 Excel。Purple 浅深色、跟随系统、Roboto 和 Material Symbols Rounded 延续手机版设计；桌面采用图纸、结果表格、PDF 来源三栏工作台。

## 使用

1. 安装 EXE/MSI，或完整解压便携包后运行 `EplanTagAssistantPC.exe`。安装包包含 Java 与 Python 运行环境；使用时无需联网、无需安装 Python/Java/Office。
2. 点击“导入 PDF”或把 PDF 文件拖入窗口。程序保存自己的副本，原始文件不改动。
3. 选择完整提取或 A–F 六个单项之一并开始。各模式规则独立保存。提取完成自动保存，取消时保留已处理的部分结果。
4. 结果支持搜索、分类、选择、复制、修改、补充、批量删除和撤销。点标签可在右侧 PDF 查看原文位置。A–F 是各自连续的打印列表，不强制逐行配对。
5. 点击“导出 Excel”直接生成 XLSX，不增加核对门槛。同名文件自动另存，不覆盖现有文件。可在文件页打开、收藏、改显示名称和真正删除本地文件。
6. 设置可选择跟随系统、浅色或深色；偏好重启保留。离线工具可检查导入副本存在性、大小、相同内容与 Excel 工作表/文本单元格/公式结构，不修改文件。

默认数据目录：`%LOCALAPPDATA%\EplanTagAssistantPC`。图纸副本、结果、规则、文件收藏和主题均保存在本机。导出目录可以在设置中选择，支持监听与刷新。应用一次只允许一个实例写同一工作区；关闭处理中的任务会先取消并保存。

快捷键包括 Ctrl+O 导入、Ctrl+E 导出；结果表格提供 Ctrl+C、Ctrl+Z、F2、Delete 和方向键。分组标题、数量与备用由业务规则管理，不能当普通标签随意改写。

## 初版范围

- 正式提取核心移植自 Android 0.6.12；保留真实安装布局来源、物理位置重复、实际安装行序、原生文字证据及现有 A–F 规则。来源与平台适配见 [CORE-PROVENANCE](docs/CORE-PROVENANCE.md)。
- 本轮不修改 Android 原仓库。PC 与手机尚未接入同一个源码模块，也没有跨设备数据同步；后续可在验证后抽取共享模块与离线项目包。
- 当前支持文字型 EPLAN PDF，未加入扫描件 OCR、云端 AI 或完整电气拓扑分析。Python 首版承担资料检查，未用另一套算法覆盖正式提取结果。
- 大型 PDF 使用按页解析及释放。默认整页渲染回退有资源保护，无法渲染时仍可按可用文字处理并显示说明；PDF 预览本身也有分辨率保护。
- 测试用于验证已覆盖的规则和样本，不构成所有图纸的准确率承诺。旧版输出不是唯一真值，应结合原图判断。
- Windows 安装包尚未配置 Authenticode 发布证书。Android 发布私钥不用于 PC，任何凭据均不随包交付。

## 云端编译

用户指定仓库：[3030606794/Tag-Assistant-pc](https://github.com/3030606794/Tag-Assistant-pc)。只在此仓库 GitHub Actions 编译、测试、渲染与打包；不运行本地 Gradle/Kotlin 构建，不运行 Android 模拟器或真机。

工作流 `Windows offline preview` 在 Windows runner 执行业务回归、桌面存储测试、Python 工具测试、实际 Compose 浅深色渲染、EXE/MSI/便携包生成，以及打包后可执行程序的导入/提取/编辑/撤销/导出/重启/删除/Python 离线检查。

交付物在成功工作流的 `EplanTagAssistantPC-v0.1.0-Windows-x64` artifact 中，验证报告和预览在 `PC-v0.1.0-cloud-evidence` 中。具体已通过的构建与检查以 [BUILD-RESULT](BUILD-RESULT.md) 为准。

## 技术与许可

Kotlin 2.2.20、Compose Desktop 1.12.1、Material3 1.12.0-alpha03、Apache PDFBox 2.0.37、JDK 17、Gradle 9.3.1。离线工具仅使用标准库，随包附带固定摘要校验的 CPython 3.13.9 Windows x64 嵌入运行时。

项目沿用原仓库 MIT 许可；Roboto、Material Symbols、CPython 和依赖库保留各自许可。原标签 SVG 作为白底黑色应用图标，不随界面主题变化。

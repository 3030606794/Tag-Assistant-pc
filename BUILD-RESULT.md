# PC 0.1.2 中文版构建记录

0.1.2 合并汉化、圆角图标、大字号中文字体、Windows 原生文件选择器和拖拽导入完善，正在准备新仓库 GitHub Actions 验证。此前0.1.1测试与预览已通过，安装打包编码问题已修正；不将其记作成功交付。本次实际结果完成后补充。

## PC 0.1.0 历史交付

2026-10-11，PC 0.1.0 初版云端构建与交付校验完成。

- 仓库：[3030606794/Tag-Assistant-pc](https://github.com/3030606794/Tag-Assistant-pc)。
- 构建源码：`7b0c8e7e023f7ba9fc803dfd761272399531e1e5`。
- [GitHub Actions run 38109951389](https://github.com/3030606794/Tag-Assistant-pc/actions/runs/38109951389)：`completed / success`，Windows Server 2022 x64 runner、Temurin JDK 17。
- 全部编译、测试、实际 Compose 渲染和 Windows 打包均在新仓库 GitHub Actions 执行；未在本地运行 Gradle/Kotlin 构建或测试、Android 模拟器或真机。

## 已通过的检查

直接读取本次云端 JUnit XML：engine **236 项 / 28 套**，desktop **27 项 / 3 套**，合计 **263 项 / 31 套**；失败、错误和跳过均为 0。另有 Python 标准库工具 **4 项**测试通过，单独统计。

核心覆盖既有 A–F 规则、真实安装行顺序、重复编号、原生文字证据、FU 安数、取消、缓存与 XLSX。云端还验证了 512 MiB heap 读取构造的 999 MiB PDF、4,023 页及超过 180 万 xref 项的增量处理。桌面覆盖原子持久化、输入校验、导入局部失败、提取失败保留旧结果、切换图纸与结果、编辑撤销、导出防覆盖、Windows 文件身份、外部改名、原位编辑、替换文件保护及真实删除。

实际 Compose 界面渲染 **8 张 1440×900 PNG**：空状态、提取工作台、文件、设置各有浅色和深色。已逐张审图，未见阻断性重叠或裁切。截图未覆盖所有窗口尺寸、极长文件名或每项交互，不能据此宣称这些场景均已实机验收。

从打包后的 `EplanTagAssistantPC.exe` 启动离线检查，真实 fixture `layout-cabinet-native.pdf` 提取 **2 页 / 132 条记录**。`smoke-result.json` 为 `ok: true`，通过 10 项检查：内置运行时、PDF 导入、原生提取、编辑与撤销、不覆盖 XLSX 导出、收藏持久化、主题持久化、重启恢复、实际文件删除、内置离线 Python。没有把 CLI 检查计入上述单元测试数。

## 交付物

成功工作流产物：`EplanTagAssistantPC-v0.1.0-Windows-x64`（artifact `11690717789`）。

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| EplanTagAssistantPC-v0.1.0-setup.exe | 108178432 | `dbc30265f45ca3ca1dfb679713dfec87b65f86c07e4eb20470160472ec09a194` |
| EplanTagAssistantPC-v0.1.0.msi | 107612499 | `2db46f0b079a101132e21fc6cb14553030db4fef781df40bd92f1589b87d7a26` |
| EplanTagAssistantPC-v0.1.0-portable.zip | 108081284 | `c636a8b4131391b55e570641f0a2c64da7f87d7f57254bd49090d64b1eff9e46` |

下载后的外层产物 ZIP SHA-256 为 `6115128fb4c297793d90eee8486bc96803d5739f655de95066cf0e5ebd88b8d0`，与 GitHub artifact digest 一致；三个交付文件均与云端 `SHA256SUMS.txt` 一致。已只读检查 EXE/MSI 文件头，以及便携包中的 JVM、Python 可执行程序/标准库与离线工具；便携包没有回归 PDF 或示例工作区。正常初次启动显示空状态。

证据产物：`PC-v0.1.0-cloud-evidence`（artifact `11690503181`），包含 JUnit XML/HTML、8 张预览与打包检查报告。证据 ZIP SHA-256 为 `578c2b099cc87a1073d171183b3942aa41759d94abdc0e77ef118518bfe96647`，与 GitHub digest 一致。

## 边界与原项目保护

目标为 Windows 10/11 x64；本次执行环境为 GitHub Windows runner，尚未在用户电脑手动验收。安装包含 Java 与 CPython 3.13.9，正式功能不需要联网或安装 Office/Python/Java。当前支持文字型 EPLAN PDF，未接入扫描件 OCR 或云端 AI；Python 检查文件存在性、文件头、大小、重复内容和 Excel 基本结构，不能证明所有 PDF 均完整可解析或标签业务准确。

Windows 安装包未配置 Authenticode 证书，本地只读签名检查状态为 `NotSigned`。没有使用 Android 私钥或将任何凭据随包交付。只读扫描未发现签名私钥；64 项原迁移输入摘要与原仓库一致。Android 原仓库 HEAD、远端和完整历史保留，工作区干净，本轮未改动。

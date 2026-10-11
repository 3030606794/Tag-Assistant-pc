# PC 0.1.2 中文版构建记录

2026-10-11，PC 0.1.2 云端构建、打包后运行检查与下载交付校验完成。

- 仓库：[3030606794/Tag-Assistant-pc](https://github.com/3030606794/Tag-Assistant-pc)。构建源码：`28df00b5949f2fbd6e63a96e981e27af7de34d9e`。
- [GitHub Actions run 38114999042](https://github.com/3030606794/Tag-Assistant-pc/actions/runs/38114999042)：`completed / success`，Windows Server 2022 x64、Temurin JDK 17。
- 全部 Gradle/Kotlin 编译、测试、Compose 渲染、SDK 资源写入、Windows 打包和实际程序运行均在上述新仓库 GitHub Actions 执行。本地只做源码与静态检查、素材生成和下载产物的只读分析，没有本地应用编译、测试或运行。

## 本次实现

界面、标准文本右键菜单、安装向导、快捷方式和用户错误提示使用中文，应用名为“EPLAN标签助手”。图标沿用原黑色图案，固定白底与连续圆角，外角透明；提供多个原生 ICO 尺寸，不随浅深色变化。

中文改用随包内置的 Noto Sans SC 400/500/700 三种真实字重，正文与表格为 18sp，其他排版同步增大并调整行高。字体使用完整矢量轮廓，在实际渲染密度下绘制；保留 SIL OFL 许可，使用时无需下载字体。

Windows PDF 导入与 Excel 导出目录选择改为系统 `IFileOpenDialog`，支持 PDF 多选、路径导航与中文路径。文件夹选择使用专用目录模式。主窗口接入 Compose 标准拖放目标，支持多份本机 PDF，拖入时显示提示；只接受复制，保留原始文件，校验与局部失败仍由原导入流程处理。

## 本次验证结果

实际读取最终云端 JUnit XML：engine **242 项 / 29 套**、desktop **34 项 / 6 套**，合计 **276 项 / 35 套**，失败、错误和跳过均为 **0**；Python 工具另有 **6 项**测试通过。新增检查覆盖中文错误展示、真实加密 PDF、中文标准菜单、Windows 原生选择器 COM 配置与路径、拖入数据边界；提取及排序规则未重写。

实际 Compose 界面生成 **12 张 PNG**：空状态、工作台、文件、设置、拖入提示的浅深色共 10 张 1440×900，另有工作台 125% 浅色 1800×1125、150% 深色 2160×1350。逐张审图未见阻断性重叠、裁切或按钮挤压；同时检查了圆角图标。字体报告验证三种字重各有 30,890 个 Unicode 映射，包内字体与报告 SHA-256 逐项一致。

打包后从真实的 `EPLAN标签助手.exe` 运行两次离线检查：一次位于构建的中文应用目录，一次复制到额外包含中文和空格的便携目录；输出参数也包含中文路径。两份报告均为 `ok: true`、版本 `0.1.2`、`zh-CN`，各完成 **11 项**检查，stderr 均为空。真实 fixture `layout-cabinet-native.pdf` 提取 **2 页 / 132 条记录**；检查包含内置 JVM、PDF 导入、原生提取、修改与撤销、不覆盖 Excel 导出、收藏及主题持久化、重启恢复、实际文件删除、内置离线 Python和原生 Windows 选择器配置与中文路径。

原生选择器测试及打包检查调用真实 Windows COM，在专用 STA 线程创建并释放接口，核对 PDF 筛选、多选选项、目录模式、中文文件名和 Shell 路径；没有调用 `Show`。拖入检查验证本机文件列表、混合文件和文本/网址拒绝等边界，未自动模拟资源管理器的实际鼠标拖放。因此这些结果不等同于用户电脑的逐项人工交互验收。

## 中文安装与启动

独立 MSI 和 EXE 内嵌 MSI 均通过只读资源检查：`ProductLanguage=2052`、`x64;2052`、版本 `0.1.2`，中文安装向导、导航按钮、快捷方式、中文许可及完整 MIT 原文均正确。原升级标识 `{E80107F9-71C7-44F0-B405-650D3D275768}`、安装目录 `EplanTagAssistantPC`、数据目录 `%LOCALAPPDATA%\EplanTagAssistantPC` 保留。升级语言条件允许原英文版；这里验证了实际安装数据库配置，没有运行安装、卸载或旧版升级操作。

JDK 17 Windows 启动器会将展开后的 JVM 参数转为进程 ANSI 代码页，英文系统下中文类路径会损坏。构建使用官方 Windows SDK 为生成的应用启动器合并进程 `activeCodePage=UTF-8` 清单，再从实际 EXE 读回并严格比较原身份、权限、DPI 和 OS 兼容节点，全部保留。该配置不修改系统全局代码页，Unicode 路径能力要求 **Windows 10 1903 及以上 / Windows 11**，见 [Microsoft 官方说明](https://learn.microsoft.com/en-us/windows/apps/design/globalizing/use-utf8-code-page)。两次中文路径检查验证了修复后的实际启动，而非只检查 XML。

已验证启动器 SHA-256：`1bcc8f4a23c0501af0d68772f9e9bc4dbf66496488c2d1e7af0a5ccf7d37d64c`，与交付便携包内 EXE 一致。安装版 EXE 的本地只读元数据为中文产品名与说明、版本 `0.1.2`；Authenticode 状态为 `NotSigned`。当前没有 Windows 发布证书，不使用 Android 签名材料。

## PC 0.1.2 交付物

成功产物：`EplanTagAssistantPC-v0.1.2-Windows-x64`（artifact `11693367485`）。

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| EPLAN标签助手-v0.1.2-安装版.exe | 127432192 | `1b75aa7a587e28b742113281a1a592fce90918551d4db0ce5fcc6fa2e8291176` |
| EPLAN标签助手-v0.1.2.msi | 126859710 | `7265eb2a75a3979ae550132085bca36ec19e3e0a5536f52ef2b6e2c17fe55b14` |
| EPLAN标签助手-v0.1.2-便携版.zip | 127247999 | `ba61ef689983f2cb37e182a5492f0c61ab61206d2c0699b624c15bff6e84bced` |

下载的外层产物 ZIP 共 377,671,790 字节，SHA-256 为 `0e1bdeaf924ca3fe76aeafcafd75f9897c95155d744134d022f9ab292e29d454`，与 GitHub artifact digest 一致；三个交付文件均与 `SHA256SUMS.txt` 一致。外层与便携 ZIP 的 CRC 检查通过，便携包 477 项包含 JVM、CPython 3.13.9、标准库、离线工具、字体和许可。主 JAR 中原生选择器与拖入实现完整，字体、图标、启动器及独立 MSI 与最终云端证据摘要一致。便携包没有回归 PDF 或示例工作区，初次启动显示空状态。

证据产物：`PC-v0.1.2-cloud-evidence`（artifact `11693492232`），SHA-256 为 `0c6d8e25d52dd624867a76ec115abcf01e1be85b0f27ed24b1dc923b9c534621`，与 GitHub digest 一致。包含实际 JUnit XML/HTML、12 张界面、字体与汉化报告、清单读回、安装器报告、两份打包后运行报告及标准输出/错误。

Android 原仓库保持工作区干净，HEAD 仍为 `6662ffbcd49cc6c9e2476523b5a3cb60d1c8a25d`；64 项迁移原始输入的字节数与 SHA-256 全部一致。PC 仍使用本地数据，支持文字型 EPLAN PDF，未增加扫描件 OCR 或跨设备同步；完整技术边界见 [README](README.md) 与 [核心出处](docs/CORE-PROVENANCE.md)。

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

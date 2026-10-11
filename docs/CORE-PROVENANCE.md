# 提取核心出处与桌面适配

本 PC 工程的提取业务来自 [Tag-Assistant Android](https://github.com/3030606794/Tag-Assistant) 0.6.12 / code 20。

- 已验证 APK 的构建源码：`eae9b20df659384b1c919d8f74313fb251703f4e`，[原云端记录](https://github.com/3030606794/Tag-Assistant/actions/runs/38105640959)。原 Android Release 测试为 291 项 / 38 套，另有 18 项界面渲染；这些数字不是 PC 验证结果。
- 复制时的原仓库 HEAD：`6662ffbcd49cc6c9e2476523b5a3cb60d1c8a25d`，包含 APK 验证后的文档及静态清单更新。所复制的核心源码、测试和 fixtures 在这两个提交之间没有差异。
- [SOURCE-BASELINE.json](../engine/SOURCE-BASELINE.json) 保存 64 个迁移前原文件的路径、大小与 SHA-256：14 个原生产文件、26 个测试文件、24 个既有测试资源。它描述原始输入，PC 当前源码由仓库历史和发布清单追溯。

## 保留的业务

`EplanParsing`、`AbLayoutAnalysis`、`AbLabelRules`、`LayoutFuseRatings`、`DocumentNames`、`OfflineModels`、`XlsxExporter` 原逻辑保留；`LabelEdits` 只扩大对象可见性，`AbInstallationOrder` 只调整接口可见性。`CompactPdfXref`、`EmbeddedLayoutRaster` 仅将 PDFBox 包名迁移到 Apache 命名空间。`IncrementalPdfDocument` 另增加预览预检使用的低结构预算重载；原三参数提取构造仍使用原来的 200,000 节点上限。

仍从实际安装布局读取 A/B，保留同名设备的物理重复、独立信号、原文及源位置；安装行排序不使用文件名、柜名或预期设备清单。只有 FU 关联所属安数；缺失功能时只有既有 ECT/AI/AO/DI/DO 规则补名。C/E 与仅数量模式保留原取数视图，单项只输出其选择范围；机台名称保持完整 PDF 显示文件名及扩展名。

## 平台适配

- `OfflinePdfEngine(cacheDirectory: File, layoutRasterSource: LayoutRasterSource? = null)` 保留原 `extract` 参数与结果模型，去除 Android Context 和资源加载器。
- PDFBox Android 2.0.27.0 替换为 Apache PDFBox 2.0.37。只读检查官方 Maven 源码确认现有自定义 parser 所需的 protected 方法、COS xref 接口及字符矩阵接口仍存在；真实兼容性由本仓库云端回归验证。
- Apache 2.0.37 的 `PDFTextStripper` 页计数器从 1 开始，Android port 原计数从 0 开始。保留直接单页处理方式，将父类页范围门控适配为 1/1，并增加第二页选择回归；实际目标页仍由页面索引选择，没有改为整份遍历。
- 页缓存使用 Gson streaming reader/writer；排序缓存使用 Gson JSON。目录带 `desktop-pdfbox2-v6` 后缀，避免混用 Android 解码几何。保留双文字视图、原生 BT/文字操作序号、页面旋转及排序排列校验。Windows 缓存写入使用关闭句柄后的原子替换，失败不影响提取结果。
- 安装几何优先使用原有逐行解压的嵌入 RGB 图像路径。仅在无法使用该路径时，以独立普通 `PDDocument` 和 `PDFRenderer` 渲染当前页，最大边 1200 像素、启用图像采样与取消检查。增量提取文档绝不用于渲染或保存。
- `DesktopPdfPreviewBudget` 在普通预览加载前，先用增量 metadata 预检实际文件大小、页数、xref 与内存余量。超过 128 MiB、1500 页、200,000 xref 项或 20,000 结构节点时停止内嵌预览，并给出原因；预检不准备页面、不解码图像。它不是对任意恶意或特殊页面的绝对内存保证。
- 正式数据的读写、导入副本、导出索引和桌面预览由 desktop 模块负责；Python 不参与正式提取与排序。本核心没有网络调用，也不伪造 OCR 结果。

## 实际迁移的测试

engine 源码中包含 26 套原核心测试的 227 项用例，去除 Robolectric/Context/Android 初始化后使用普通 JUnit 运行。保留原安装位置与功能归属、密集原生字段、FU、单项与完整结果一致性、缓存、XLSX、取消、链接安全及大文件用例。

其中大文件回归在云端以 512 MiB heap 读取现场构造的 999 MiB PDF；长文档回归在云端构造 4,023 页及超过 180 万 xref 项，检查紧凑索引和资源释放。大文件不会作为仓库资源提交。

24 个既有 fixtures 合计 9,592,251 字节，是原仓库已有的裁剪图纸、1200px 安装图及独立标注 JSON；没有加入用户完整原始图纸或 Excel。真实样本覆盖设备实例计数、独立人工读取的安装行、重复编号、中文原文和对应功能。断言样例位于测试，生产排序不读取这些期望清单。

另新增 4 项桌面真实渲染用例（非零 CropBox/四向旋转、绘制中取消并重试、超大普通渲染保护、无效页句柄释放）、1 项 Windows 缓存覆盖回归、3 项预览预算回归（真实 native fixture、超大文件解析前早退、未知页数/低结构预算拒绝与默认提取预算保留），以及 1 项 Apache 单页计数门控回归。当前 engine 共 236 项 / 28 套；通过与否及最终数量以本仓库 GitHub Actions 的实际报告为准。本地没有运行 Gradle/Kotlin 编译或测试。

原 Android 的 `OfflineStore` 序列化部分在 engine 测试中替换为 Gson 模型往返，继续检查字段、顺序与工作簿；desktop 的实际文件持久化另有独立测试。这不是 Android/PC 数据同步功能。

## 已知边界

当前正式提取支持原生文字 PDF，扫描件仍需事先 OCR。没有证据证明更换语言或增加第二套读取器会自动提高准确率；旧版输出一致性与独立标注正确性分别验证。

普通 PDFBox 加载会展开 catalog，不能继承增量 parser 的内存保证。因此默认几何 fallback 对超过 200,000 xref 项或超过 256 MiB 的文件跳过普通渲染；嵌入图像和原生文字路径仍可继续。内存余量不足、图像格式不支持或结构不明确时，保留原文字位置排序并记录说明，不生成新的设备或臆测安装行。桌面普通预览也不等同于大文件增量提取。

当前测试迁移是保留业务基线的措施，PC 质量仍须以本仓库云端报告和真实图纸验收为准，不能直接沿用 Android 的“已通过”结论。

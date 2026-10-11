# 简体中文 Windows 安装器

打包仅在本仓库 Windows GitHub Actions 执行。Compose 先生成应用镜像，再由同一 JDK 17 的官方 `jpackage` 使用该镜像生成 MSI 和 EXE，完整保留离线运行环境。没有修改已经生成的安装器二进制。

- JVM 默认地区只在打包进程中设为 `zh-CN`，使 WiXUI 使用简体中文资源，不改变用户系统地区。
- jpackage 参数全部写入 UTF-8 无 BOM 的参数文件，原生命令行仅传 ASCII 文件名；同时指定工具进程 `file.encoding=UTF-8`，避免 JDK 17 在英文 Windows 上把中文参数降级为问号。
- `overrides.wxi` 设置 MSI `ProductLanguage=2052`，同时检查 Summary Template 为 `x64;2052`。
- `InstallDirNotEmptyDlg.wxs` 保留 jpackage 的目录确认动作及对话框序列，只补全 JDK 17 默认资源未翻译的安装器标题。
- 不启用 JDK 默认仍含英文的可选快捷方式选择弹窗；桌面和开始菜单快捷方式直接生成中文名称。
- `WinInstaller.properties` 为 EXE 的文件说明和产品说明补充中文；内部版本号等原字段仍由 jpackage 填充。
- 许可页显示中文参考译文，再附完整 MIT 英文原文。原始 `LICENSE` 随交付包保留。
- 沿用原 PC `UpgradeCode` 和 `EplanTagAssistantPC` 安装目录。应用数据目录不受显示名称和安装器语言影响。升级表不限制旧版语言，可识别之前的英文安装包。

`scripts/verify_windows_installer.py` 通过 Windows Installer 只读 API 检查真实 MSI 的语言、中文对话框/按钮、快捷方式、版本、安装路径和升级标识；再通过 Windows PE 资源只读 API 提取 EXE 内嵌的 MSI，执行同等检查。该步骤不安装或运行应用，验证报告进入云端 evidence。

官方依据：

- [JDK 17 jpackage 命令](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jpackage.html)：`--app-image`、`--resource-dir`、`--install-dir`、Windows 快捷方式与升级标识。
- [OpenJDK 17u WinMsiBundler](https://github.com/openjdk/jdk17u/blob/master/src/jdk.jpackage/windows/classes/jdk/jpackage/internal/WinMsiBundler.java)：根据地区选择 WiX 本地化资源。
- [OpenJDK 17u overrides.wxi](https://github.com/openjdk/jdk17u/blob/master/src/jdk.jpackage/windows/classes/jdk/jpackage/internal/resources/overrides.wxi)：明确支持覆盖 `JpProductLanguage`。
- [OpenJDK 17u WixUiFragmentBuilder](https://github.com/openjdk/jdk17u/blob/master/src/jdk.jpackage/windows/classes/jdk/jpackage/internal/WixUiFragmentBuilder.java)：允许覆盖目录确认对话框资源。
- [Microsoft：Windows Installer 本地化](https://learn.microsoft.com/en-us/windows/win32/msi/localizing-a-windows-installer-package)：ProductLanguage、Summary Template、文字与代码页要求。
- [OpenJDK 17u MsiWrapper](https://github.com/openjdk/jdk17u/blob/master/src/jdk.jpackage/windows/native/msiwrapper/MsiWrapper.cpp)：EXE 内嵌 MSI 的 `RT_RCDATA` / `msi` 资源约定。

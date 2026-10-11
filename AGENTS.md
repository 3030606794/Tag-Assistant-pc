# EPLAN 标签助手 PC

- 用户明确授权在本仓库 `3030606794/Tag-Assistant-pc` 开发、推送并使用 GitHub Actions 编译；这是独立 PC 工程。不要修改或重置相邻 Android 仓库。
- 不在本地运行 Gradle/Kotlin 编译或测试。编译、业务测试、桌面预览和打包在本仓库 GitHub Actions 执行。
- 提取规则来自 Android 0.6.12 的已验证业务基线，原出处与变更记录见 docs/CORE-PROVENANCE.md。保留实际安装布局来源、物理重复、真实行序和原生文字证据；禁止按文件名/柜名/样例清单硬编码。
- 提取自动保存，导出直接生成，不添加人工核对门槛。所有用户数据持久化；删除失败保留记录并提示。
- Desktop 与 Python 工具均离线运行。Python 仅写任务输出，由主程序管理正式数据。
- Purple 浅深色、系统跟随、Roboto 与 Rounded 图标保持一致；桌面按鼠标键盘和宽屏重新设计。
- 不能把 Android 签名证书当作 Windows Authenticode 证书。没有 Windows 证书时如实说明安装包未做 Authenticode 签名；不得伪造证书或上传任何凭据。

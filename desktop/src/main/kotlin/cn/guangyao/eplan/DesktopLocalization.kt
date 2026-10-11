package cn.guangyao.eplan

import java.util.Locale
import javax.swing.JComponent
import javax.swing.UIManager

/** Called before Compose/Swing creates components, including cloud preview and packaged checks. */
internal fun initializeChineseDesktop() {
    val previousFormat = Locale.getDefault(Locale.Category.FORMAT)
    Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
    Locale.setDefault(Locale.Category.DISPLAY, Locale.SIMPLIFIED_CHINESE)
    Locale.setDefault(Locale.Category.FORMAT, previousFormat)
    JComponent.setDefaultLocale(Locale.SIMPLIFIED_CHINESE)
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val strings = mapOf(
        "OptionPane.okButtonText" to "确定", "OptionPane.cancelButtonText" to "取消",
        "OptionPane.yesButtonText" to "是", "OptionPane.noButtonText" to "否",
        "OptionPane.messageDialogTitle" to "提示", "OptionPane.inputDialogTitle" to "输入",
        "FileChooser.openDialogTitleText" to "打开文件", "FileChooser.saveDialogTitleText" to "保存文件",
        "FileChooser.openButtonText" to "打开", "FileChooser.saveButtonText" to "保存",
        "FileChooser.cancelButtonText" to "取消", "FileChooser.updateButtonText" to "刷新",
        "FileChooser.helpButtonText" to "帮助", "FileChooser.directoryOpenButtonText" to "打开文件夹",
        "FileChooser.fileNameLabelText" to "文件名：", "FileChooser.fileNameLabelMnemonic" to 78,
        "FileChooser.filesOfTypeLabelText" to "文件类型：", "FileChooser.lookInLabelText" to "查找范围：",
        "FileChooser.saveInLabelText" to "保存位置：", "FileChooser.acceptAllFileFilterText" to "所有文件",
        "FileChooser.openButtonToolTipText" to "打开所选文件", "FileChooser.saveButtonToolTipText" to "保存所选文件",
        "FileChooser.cancelButtonToolTipText" to "取消选择", "FileChooser.upFolderToolTipText" to "上一级文件夹",
        "FileChooser.homeFolderToolTipText" to "个人文件夹", "FileChooser.newFolderToolTipText" to "新建文件夹",
        "FileChooser.listViewButtonToolTipText" to "列表", "FileChooser.detailsViewButtonToolTipText" to "详细信息",
        "FileChooser.fileNameHeaderText" to "名称", "FileChooser.fileSizeHeaderText" to "大小",
        "FileChooser.fileTypeHeaderText" to "类型", "FileChooser.fileDateHeaderText" to "修改日期",
        "FileChooser.fileAttrHeaderText" to "属性", "FileChooser.newFolderButtonText" to "新建文件夹",
        "FileChooser.viewMenuLabelText" to "查看", "FileChooser.refreshActionLabelText" to "刷新",
        "FileChooser.newFolderActionLabelText" to "新建文件夹", "FileChooser.listViewActionLabelText" to "列表",
        "FileChooser.detailsViewActionLabelText" to "详细信息", "FileChooser.renameFileDialogTitleText" to "重命名文件",
        "FileChooser.renameFileErrorTitle" to "无法重命名", "FileChooser.newFolderErrorText" to "无法创建文件夹",
        "FileChooser.newFolderErrorSeparator" to "：", "FileChooser.other.newFolder" to "新建文件夹",
        "FileChooser.win32.newFolder" to "新建文件夹", "FileChooser.directoryDescriptionText" to "文件夹",
        "FileChooser.fileDescriptionText" to "文件", "FileChooser.directoryOpenButtonToolTipText" to "打开所选文件夹",
        "ProgressMonitor.progressText" to "正在处理",
    )
    strings.forEach { (key, value) -> UIManager.put(key, value) }
}

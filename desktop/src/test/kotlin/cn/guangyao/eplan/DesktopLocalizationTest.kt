package cn.guangyao.eplan

import org.junit.Assert.*
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.text.NumberFormat
import java.util.Locale
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.UIManager

class DesktopLocalizationTest {
    /** One EDT transaction owns and restores all global display settings used by this test. */
    @Test fun englishHostGetsChineseDialogsWithoutChangingNumberFormatting() {
        SwingUtilities.invokeAndWait {
            val originalDefault = Locale.getDefault()
            val originalDisplay = Locale.getDefault(Locale.Category.DISPLAY)
            val originalFormat = Locale.getDefault(Locale.Category.FORMAT)
            val originalComponent = JComponent.getDefaultLocale()
            val originalLookAndFeel = UIManager.getLookAndFeel()
            val originalDefaults = UIManager.getDefaults().entries.associate { it.key to it.value }
            try {
                Locale.setDefault(Locale.US)
                Locale.setDefault(Locale.Category.DISPLAY, Locale.US)
                Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY)
                JComponent.setDefaultLocale(Locale.US)
                val originalNumber = NumberFormat.getNumberInstance().format(1234.5)

                initializeChineseDesktop()

                assertEquals("zh", Locale.getDefault().language)
                assertEquals("zh", Locale.getDefault(Locale.Category.DISPLAY).language)
                assertEquals("zh", JComponent.getDefaultLocale().language)
                assertEquals(Locale.GERMANY, Locale.getDefault(Locale.Category.FORMAT))
                assertEquals(originalNumber, NumberFormat.getNumberInstance().format(1234.5))
                assertEquals(12.5, "12.5".toDouble(), 0.0)

                val chooser = JFileChooser()
                chooser.dialogType = JFileChooser.OPEN_DIALOG
                assertEquals("zh", chooser.locale.language)
                assertEquals("打开", chooser.ui.getApproveButtonText(chooser))
                chooser.dialogType = JFileChooser.SAVE_DIALOG
                assertEquals("保存", chooser.ui.getApproveButtonText(chooser))
                assertTrue(UIManager.getString("FileChooser.fileNameLabelText", chooser.locale).contains("文件名"))
                assertTrue(UIManager.getString("FileChooser.filesOfTypeLabelText", chooser.locale).contains("文件类型"))
                assertEquals("取消", UIManager.getString("FileChooser.cancelButtonText", chooser.locale))

                // Construct real Swing button components, without showing a native window.
                val confirmation = JOptionPane("是否继续？", JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_CANCEL_OPTION)
                assertTrue(buttonLabels(confirmation).containsAll(setOf("是", "否", "取消")))
                val notice = JOptionPane("操作完成。", JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION)
                assertTrue(buttonLabels(notice).contains("确定"))
            } finally {
                Locale.setDefault(originalDefault)
                Locale.setDefault(Locale.Category.DISPLAY, originalDisplay)
                Locale.setDefault(Locale.Category.FORMAT, originalFormat)
                JComponent.setDefaultLocale(originalComponent)
                UIManager.setLookAndFeel(originalLookAndFeel)
                UIManager.getDefaults().keys.toList().filter { it !in originalDefaults }.forEach { UIManager.put(it, null) }
                originalDefaults.forEach { (key, value) -> UIManager.put(key, value) }
            }
        }
    }

    private fun buttonLabels(component: Component): Set<String> = buildSet {
        if (component is JButton) add(component.text)
        if (component is Container) component.components.forEach { addAll(buttonLabels(it)) }
    }
}

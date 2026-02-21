package sm.toolwindow
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import sm.model.SmSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
class SmSettingsDialog(
    private val roots: List<VirtualFile>,
    private val onApply: () -> Unit,
) : DialogWrapper(true) {
    private val globalField = JTextField(SmSettings.formatExts(SmSettings.globalHiddenExtensions()), 30)
    private data class RootTab(val root: VirtualFile, val useGlobal: JCheckBox, val field: JTextField)
    private val rootTabs = mutableListOf<RootTab>()
    init { title = "SM Settings"; init() }
    override fun createCenterPanel(): JComponent {
        val tabs = JTabbedPane()
        val globalPanel = JPanel(BorderLayout(8, 8)).apply {
            border = JBUI.Borders.empty(12)
            add(JLabel("<html>Extensions to <b>hide</b> in the SM tree (comma-separated).<br>Everything else is shown.<br><i>Default: *.txt</i></html>"), BorderLayout.NORTH)
            val row = JPanel(FlowLayout(FlowLayout.LEFT)); row.add(JLabel("Hidden:")); row.add(globalField)
            add(row, BorderLayout.CENTER)
        }
        tabs.addTab("Global", globalPanel)
        for (root in roots) {
            val rootHidden = SmSettings.rootHiddenExtensions(root.path)
            val cb = JCheckBox("Use global settings", rootHidden == null)
            val field = JTextField(SmSettings.formatExts(rootHidden ?: SmSettings.globalHiddenExtensions()), 30)
            field.isEnabled = rootHidden != null; cb.addActionListener { field.isEnabled = !cb.isSelected }
            val tabName = SmSettings.getAlias(root.path) ?: root.path
            val panel = JPanel(BorderLayout(8, 8)).apply {
                border = JBUI.Borders.empty(12)
                add(JLabel("<html>Hidden extensions for <b>$tabName</b></html>"), BorderLayout.NORTH)
                val mid = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); add(cb); add(Box.createVerticalStrut(8))
                    val r = JPanel(FlowLayout(FlowLayout.LEFT)); r.add(JLabel("Hidden:")); r.add(field); add(r) }
                add(mid, BorderLayout.CENTER)
            }
            tabs.addTab(tabName, panel); rootTabs.add(RootTab(root, cb, field))
        }
        tabs.preferredSize = Dimension(480, 240); return tabs
    }
    override fun doOKAction() {
        SmSettings.setGlobalHiddenExtensions(parseField(globalField.text))
        for (rt in rootTabs) {
            if (rt.useGlobal.isSelected) SmSettings.setRootHiddenExtensions(rt.root.path, null)
            else SmSettings.setRootHiddenExtensions(rt.root.path, parseField(rt.field.text))
        }
        onApply(); super.doOKAction()
    }
    private fun parseField(text: String): Set<String> =
        text.split(",").map { it.trim().lowercase().removePrefix("*").removePrefix(".") }.filter { it.isNotEmpty() }.toSet()
}


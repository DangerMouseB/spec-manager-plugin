package sm.toolwindow

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.*

/**
 * Dialog for creating or editing an SM link (file or folder link).
 */
class SmLinkDialog(
    private val ownerFolder: VirtualFile,
    private val existingKey: String? = null,
    private val existingTarget: String? = null,
    private val existingDisplayName: String? = null,
    private val existingType: String? = null,
) : JDialog(JOptionPane.getRootFrame(), if (existingKey != null) "Edit Link" else "Add Link", true) {

    private val nameField = JTextField(existingKey ?: "", 25)
    private val targetField = JTextField(existingTarget ?: "", 35)
    private val displayNameField = JTextField(existingDisplayName ?: "", 25)
    private val fileRadio = JRadioButton("File")
    private val folderRadio = JRadioButton("Folder")
    private val typeGroup = ButtonGroup().apply { add(fileRadio); add(folderRadio) }
    private val relativeCheckbox = JCheckBox("Store as relative path", true)

    var resultKey: String? = null; private set
    var resultTarget: String? = null; private set
    var resultType: String? = null; private set
    var resultDisplayName: String? = null; private set
    var confirmed = false; private set

    init {
        if (existingType == "file") fileRadio.isSelected = true else folderRadio.isSelected = true
        if (existingKey != null) nameField.isEditable = false

        val content = JPanel(GridBagLayout()); content.border = JBUI.Borders.empty(10)
        val gbc = GridBagConstraints().apply { insets = JBUI.insets(4); anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL }

        fun addRow(row: Int, label: String, comp: JComponent) {
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; content.add(JLabel(label), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; content.add(comp, gbc)
        }

        addRow(0, "Link name:", nameField)
        val targetPanel = JPanel(BorderLayout(4, 0)); targetPanel.add(targetField, BorderLayout.CENTER)
        val browseBtn = JButton("Browse…"); browseBtn.addActionListener { browseForTarget() }; targetPanel.add(browseBtn, BorderLayout.EAST)
        addRow(1, "Target:", targetPanel)
        addRow(2, "Display name:", displayNameField)
        val typePanel = JPanel().apply { add(fileRadio); add(folderRadio) }; addRow(3, "Type:", typePanel)
        gbc.gridx = 1; gbc.gridy = 4; content.add(relativeCheckbox, gbc)

        val buttonPanel = JPanel()
        val okBtn = JButton("OK"); okBtn.addActionListener { onOk() }
        val cancelBtn = JButton("Cancel"); cancelBtn.addActionListener { confirmed = false; dispose() }
        buttonPanel.add(okBtn); buttonPanel.add(cancelBtn); getRootPane().defaultButton = okBtn

        val main = JPanel(BorderLayout()); main.add(content, BorderLayout.CENTER); main.add(buttonPanel, BorderLayout.SOUTH)
        contentPane = main; pack(); setLocationRelativeTo(parent)
    }

    private fun browseForTarget() {
        val descriptor = FileChooserDescriptor(true, true, false, false, false, false); descriptor.title = "Select Link Target"
        val chosen = FileChooser.chooseFile(descriptor, null, null) ?: return
        if (chosen.isDirectory) folderRadio.isSelected = true else fileRadio.isSelected = true
        targetField.text = if (relativeCheckbox.isSelected) computeRelativePath(ownerFolder.path, chosen.path, chosen.isDirectory) else chosen.path
        if (nameField.text.isBlank()) nameField.text = chosen.nameWithoutExtension
    }

    private fun onOk() {
        val name = nameField.text.trim(); val target = targetField.text.trim()
        if (name.isEmpty()) { JOptionPane.showMessageDialog(this, "Link name is required."); return }
        if (target.isEmpty()) { JOptionPane.showMessageDialog(this, "Target path is required."); return }
        if (name.contains('/') || name.contains('\\') || name.contains(':')) { JOptionPane.showMessageDialog(this, "Link name cannot contain /, \\ or :"); return }
        resultKey = name; resultTarget = target; resultType = if (folderRadio.isSelected) "folder" else "file"
        val dn = displayNameField.text.trim(); resultDisplayName = dn.ifEmpty { null }
        confirmed = true; dispose()
    }

    companion object {
        fun computeRelativePath(from: String, to: String, isDirectory: Boolean): String {
            return try {
                val fromPath: Path = Paths.get(from).toAbsolutePath().normalize()
                val toPath: Path = Paths.get(to).toAbsolutePath().normalize()
                var rel = fromPath.relativize(toPath).toString().replace('\\', '/')
                if (isDirectory && !rel.endsWith("/")) rel += "/"; rel
            } catch (_: Exception) { to }
        }
    }
}


package sm.viewer

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.io.File
import java.io.FileInputStream
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.text.rtf.RTFEditorKit

class RichTextPanel : JPanel(BorderLayout()) {
    private val editor = JEditorPane().apply { isEditable = true; editorKit = RTFEditorKit(); contentType = "text/rtf" }
    init { border = JBUI.Borders.empty(); add(JBScrollPane(editor), BorderLayout.CENTER) }
    fun load(file: File) { FileInputStream(file).use { fis -> editor.read(fis, file.name) } }
}


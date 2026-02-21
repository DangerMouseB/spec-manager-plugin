package sm.viewer

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel

class TextPanel(private val project: Project) : JPanel(BorderLayout()) {
    private var editor: Editor? = null
    private var vf: VirtualFile? = null
    init { border = JBUI.Borders.empty() }

    fun clear() { vf = null; setEditor(null) }

    fun load(file: VirtualFile) {
        vf = file
        val doc = FileDocumentManager.getInstance().getDocument(file)
        if (doc != null) setEditor(createEditor(doc, file))
        else setEditor(createEditor(EditorFactory.getInstance().createDocument(""), file))
    }

    private fun createEditor(doc: Document, file: VirtualFile): Editor {
        val fileType = FileTypeManager.getInstance().getFileTypeByFile(file)
        val ed = EditorFactory.getInstance().createEditor(doc, project, fileType, false)
        (ed as? EditorEx)?.apply { isViewer = false; setCaretVisible(true) }
        return ed
    }

    private fun setEditor(newEditor: Editor?) {
        val old = editor; editor = newEditor; removeAll()
        if (newEditor != null) add(JBScrollPane(newEditor.component), BorderLayout.CENTER)
        revalidate(); repaint()
        if (old != null) EditorFactory.getInstance().releaseEditor(old)
    }

    override fun removeNotify() { super.removeNotify(); editor?.let { EditorFactory.getInstance().releaseEditor(it) }; editor = null }
}


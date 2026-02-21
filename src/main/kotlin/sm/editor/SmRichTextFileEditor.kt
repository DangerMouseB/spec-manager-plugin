package sm.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import sm.model.SmSettings
import java.awt.*
import java.beans.PropertyChangeListener
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.CaretEvent
import javax.swing.event.CaretListener
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument
import javax.swing.text.StyledEditorKit
import javax.swing.text.rtf.RTFEditorKit

/**
 * WYSIWYG file editor for .rtf files.
 * Margins on wrapper panel (not editor border — that breaks caret navigation).
 * Font selector, Cochin default, paste normalisation, reliable save.
 */
class SmRichTextFileEditor(
    private val project: Project,
    private val vf: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private var modified = false
    private var saving = false
    private val saveTimer = Timer(2000) { scheduleSave() }.apply { isRepeats = false }
    private val rtfKit = RTFEditorKit()

    companion object {
        private val AVAILABLE_FONTS = arrayOf("Cochin", "Georgia", "Palatino", "Times New Roman", "Helvetica", "Arial", "SansSerif", "Menlo", "Courier")
        private val FONT_SIZES = arrayOf(10, 11, 12, 13, 14, 16, 18, 20, 24, 28, 36)
        private val ZOOM_LEVELS = doubleArrayOf(0.5, 0.67, 0.75, 0.8, 0.9, 1.0, 1.1, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0)
        private const val DEFAULT_FONT = "Cochin"
        private const val DEFAULT_SIZE = 14
    }

    private var zoomFactor = 1.0
    private var updatingToolbar = false

    private val editorPane = JEditorPane().apply {
        editorKit = rtfKit; contentType = "text/rtf"; isEditable = true; isOpaque = true
        background = Color.WHITE; foreground = Color.BLACK; caretColor = Color.BLACK
        font = Font(DEFAULT_FONT, Font.PLAIN, DEFAULT_SIZE)
    }

    private val fontCombo = JComboBox(AVAILABLE_FONTS).apply {
        selectedItem = DEFAULT_FONT; maximumSize = Dimension(160, 28); preferredSize = Dimension(160, 28)
        addActionListener { applyFontToSelection() }
    }
    private val sizeCombo = JComboBox(FONT_SIZES.map { it.toString() }.toTypedArray()).apply {
        selectedItem = DEFAULT_SIZE.toString(); maximumSize = Dimension(60, 28); preferredSize = Dimension(60, 28); isEditable = true
        addActionListener { applyFontToSelection() }
    }
    private val boldBtn = JToggleButton("B").apply {
        font = Font("SansSerif", Font.BOLD, 13); toolTipText = "Bold (⌘B)"; addActionListener { applyFontToSelection() }
    }
    private val italicBtn = JToggleButton("I").apply {
        font = Font("SansSerif", Font.ITALIC, 13); toolTipText = "Italic (⌘I)"; addActionListener { applyFontToSelection() }
    }
    private var currentColor: Color = Color.BLACK
    private val colorBtn = JButton("A").apply {
        font = Font("SansSerif", Font.BOLD, 13); toolTipText = "Text colour"; foreground = currentColor
        addActionListener { chooseColor() }
    }
    private val bulletBtn = JButton("•").apply {
        font = Font("SansSerif", Font.PLAIN, 16); toolTipText = "Bullet list"; addActionListener { insertListPrefix("• ") }
    }
    private val numberBtn = JButton("1.").apply {
        font = Font("SansSerif", Font.PLAIN, 12); toolTipText = "Numbered list"; addActionListener { insertNumberedList() }
    }
    private val zoomLabel = JLabel("100%").apply { preferredSize = Dimension(48, 28); horizontalAlignment = SwingConstants.CENTER }

    private val toolbar = JToolBar().apply {
        isFloatable = false
        add(JLabel(" Font: ")); add(fontCombo); add(JLabel("  Size: ")); add(sizeCombo)
        addSeparator(); add(boldBtn); add(italicBtn); add(colorBtn)
        addSeparator(); add(bulletBtn); add(numberBtn); addSeparator()
        add(JButton("−").apply { toolTipText = "Zoom out (⌘−)"; addActionListener { zoomOut() } })
        add(zoomLabel)
        add(JButton("+").apply { toolTipText = "Zoom in (⌘+)"; addActionListener { zoomIn() } })
    }

    private val editorWrapper = JPanel(BorderLayout()).apply {
        background = Color.WHITE; border = BorderFactory.createEmptyBorder(20, 40, 20, 40)
        add(editorPane, BorderLayout.CENTER)
    }

    private val mainPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(); background = Color.WHITE
        val scrollPane = JBScrollPane(editorWrapper); scrollPane.viewport.background = Color.WHITE; scrollPane.background = Color.WHITE
        add(toolbar, BorderLayout.NORTH); add(scrollPane, BorderLayout.CENTER)
    }

    private val docListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = onEdit()
        override fun removeUpdate(e: DocumentEvent) = onEdit()
        override fun changedUpdate(e: DocumentEvent) = onEdit()
    }

    init {
        loadFromVfs()
        attachListener()
        val savedZoom = SmSettings.getZoom(vf.path)
        if (savedZoom != 1.0) SwingUtilities.invokeLater { applyZoom(savedZoom) }

        val pasteAction = editorPane.actionMap.get("paste-from-clipboard")
        editorPane.actionMap.put("paste-from-clipboard", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                val kit = editorPane.editorKit as? StyledEditorKit; val inputAttrs = kit?.inputAttributes
                val savedFont = if (inputAttrs != null) SimpleAttributeSet(inputAttrs) else null
                pasteAction?.actionPerformed(e)
                if (savedFont != null) { inputAttrs?.removeAttributes(inputAttrs); inputAttrs?.addAttributes(savedFont) }
            }
        })

        val im = editorPane.getInputMap(JComponent.WHEN_FOCUSED)
        val am = editorPane.actionMap
        val meta = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        im.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_B, meta), "sm-bold")
        im.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_I, meta), "sm-italic")
        im.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_EQUALS, meta), "sm-zoom-in")
        im.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_MINUS, meta), "sm-zoom-out")
        im.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_0, meta), "sm-zoom-reset")
        am.put("sm-bold", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) { boldBtn.isSelected = !boldBtn.isSelected; applyFontToSelection() }
        })
        am.put("sm-italic", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) { italicBtn.isSelected = !italicBtn.isSelected; applyFontToSelection() }
        })
        am.put("sm-zoom-in", object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent) { zoomIn() } })
        am.put("sm-zoom-out", object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent) { zoomOut() } })
        am.put("sm-zoom-reset", object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent) { zoomReset() } })

        editorPane.addCaretListener(object : CaretListener { override fun caretUpdate(e: CaretEvent) { syncToolbarFromCaret() } })
    }

    private fun attachListener() { editorPane.document.addDocumentListener(docListener) }
    private fun detachListener() { editorPane.document.removeDocumentListener(docListener) }

    private fun syncToolbarFromCaret() {
        val doc = editorPane.document as? StyledDocument ?: return
        val pos = editorPane.selectionStart.coerceIn(0, doc.length.coerceAtLeast(0))
        if (doc.length == 0) return
        val attrs = doc.getCharacterElement(pos).attributes
        updatingToolbar = true
        try {
            val family = StyleConstants.getFontFamily(attrs); val size = StyleConstants.getFontSize(attrs)
            val displaySize = if (zoomFactor != 1.0) Math.round(size / zoomFactor).toInt() else size
            if (fontCombo.selectedItem != family) fontCombo.selectedItem = family
            val sizeStr = displaySize.toString(); if (sizeCombo.selectedItem != sizeStr) sizeCombo.selectedItem = sizeStr
            if (boldBtn.isSelected != StyleConstants.isBold(attrs)) boldBtn.isSelected = StyleConstants.isBold(attrs)
            if (italicBtn.isSelected != StyleConstants.isItalic(attrs)) italicBtn.isSelected = StyleConstants.isItalic(attrs)
            val fg = StyleConstants.getForeground(attrs); if (fg != null && fg != currentColor) { currentColor = fg; colorBtn.foreground = fg }
        } finally { updatingToolbar = false }
    }

    private fun applyFontToSelection() {
        if (updatingToolbar) return
        val doc = editorPane.document as? StyledDocument ?: return
        val start = editorPane.selectionStart; val end = editorPane.selectionEnd
        val fontName = fontCombo.selectedItem as? String ?: DEFAULT_FONT
        val baseFontSize = (sizeCombo.selectedItem?.toString())?.toIntOrNull() ?: DEFAULT_SIZE
        val fontSize = if (zoomFactor != 1.0) Math.round(baseFontSize * zoomFactor).toInt() else baseFontSize
        val attrs = SimpleAttributeSet()
        StyleConstants.setFontFamily(attrs, fontName); StyleConstants.setFontSize(attrs, fontSize)
        StyleConstants.setBold(attrs, boldBtn.isSelected); StyleConstants.setItalic(attrs, italicBtn.isSelected)
        StyleConstants.setForeground(attrs, currentColor)
        if (start != end) {
            doc.setCharacterAttributes(start, end - start, attrs, false)
            SwingUtilities.invokeLater { editorPane.select(start, end); editorPane.requestFocusInWindow() }; onEdit()
        }
        val kit = editorPane.editorKit as? StyledEditorKit
        kit?.inputAttributes?.removeAttributes(kit.inputAttributes); kit?.inputAttributes?.addAttributes(attrs)
    }

    private fun chooseColor() {
        val chosen = JColorChooser.showDialog(editorPane, "Text Colour", currentColor) ?: return
        currentColor = chosen; colorBtn.foreground = chosen
        val doc = editorPane.document as? StyledDocument ?: return
        val start = editorPane.selectionStart; val end = editorPane.selectionEnd
        val attrs = SimpleAttributeSet(); StyleConstants.setForeground(attrs, chosen)
        if (start != end) {
            doc.setCharacterAttributes(start, end - start, attrs, false)
            SwingUtilities.invokeLater { editorPane.select(start, end); editorPane.requestFocusInWindow() }; onEdit()
        }
        val kit = editorPane.editorKit as? StyledEditorKit; kit?.inputAttributes?.addAttributes(attrs)
    }

    private fun insertListPrefix(prefix: String) {
        val doc = editorPane.document as? StyledDocument ?: return
        val pos = editorPane.caretPosition; val text = doc.getText(0, doc.length)
        val lineStart = text.lastIndexOf('\n', pos - 1) + 1
        val lineEnd = text.indexOf('\n', pos).let { if (it < 0) doc.length else it }
        val lineText = text.substring(lineStart, lineEnd)
        if (lineText.startsWith(prefix)) doc.remove(lineStart, prefix.length) else doc.insertString(lineStart, prefix, null)
        onEdit(); editorPane.requestFocusInWindow()
    }

    private fun insertNumberedList() {
        val doc = editorPane.document as? StyledDocument ?: return
        val pos = editorPane.caretPosition; val text = doc.getText(0, doc.length)
        val lineStart = text.lastIndexOf('\n', pos - 1) + 1
        val lineEnd = text.indexOf('\n', pos).let { if (it < 0) doc.length else it }
        val lineText = text.substring(lineStart, lineEnd)
        val numPattern = Regex("""^\d+\.\s"""); val match = numPattern.find(lineText)
        if (match != null) { doc.remove(lineStart, match.value.length) }
        else {
            var num = 1
            if (lineStart > 0) {
                val prevLines = text.substring(0, lineStart).trimEnd('\n').split('\n')
                val lastNum = prevLines.lastOrNull()?.let { numPattern.find(it) }
                if (lastNum != null) num = lastNum.value.trim().removeSuffix(".").toIntOrNull()?.plus(1) ?: 1
            }
            doc.insertString(lineStart, "$num. ", null)
        }
        onEdit(); editorPane.requestFocusInWindow()
    }

    private fun zoomIn() { val idx = ZOOM_LEVELS.indexOfFirst { it > zoomFactor + 0.001 }; if (idx >= 0) applyZoom(ZOOM_LEVELS[idx]) }
    private fun zoomOut() { val idx = ZOOM_LEVELS.indexOfLast { it < zoomFactor - 0.001 }; if (idx >= 0) applyZoom(ZOOM_LEVELS[idx]) }
    private fun zoomReset() = applyZoom(1.0)

    private fun applyZoom(newFactor: Double) {
        val doc = editorPane.document as? StyledDocument
        if (doc != null && doc.length > 0) scaleDocFonts(newFactor / zoomFactor)
        zoomFactor = newFactor; zoomLabel.text = "${(newFactor * 100).toInt()}%"
        SmSettings.setZoom(vf.path, newFactor); editorPane.revalidate(); editorPane.repaint()
    }

    private fun loadFromVfs() {
        try {
            val ioFile = VfsUtilCore.virtualToIoFile(vf); val doc = rtfKit.createDefaultDocument()
            FileInputStream(ioFile).use { fis -> rtfKit.read(fis, doc, 0) }
            if (doc is StyledDocument) {
                val root = doc.defaultRootElement
                for (i in 0 until root.elementCount) {
                    val para = root.getElement(i); val leftIndent = StyleConstants.getLeftIndent(para.attributes)
                    if (leftIndent > 20f) {
                        val fix = SimpleAttributeSet(para.attributes)
                        StyleConstants.setLeftIndent(fix, 20f); StyleConstants.setFirstLineIndent(fix, -12f)
                        doc.setParagraphAttributes(para.startOffset, para.endOffset - para.startOffset, fix, false)
                    }
                }
            }
            detachListener(); editorPane.document = doc; attachListener(); modified = false
        } catch (ex: Exception) { editorPane.text = "(could not load RTF: ${ex.message})" }
    }

    private fun onEdit() { if (saving) return; modified = true; saveTimer.restart() }

    private fun scheduleSave() {
        if (!modified) return
        ApplicationManager.getApplication().invokeLater {
            if (!modified || !vf.isValid) return@invokeLater
            ApplicationManager.getApplication().runWriteAction { doSave() }
        }
    }

    private fun doSave() {
        saving = true
        try {
            val wasZoomed = zoomFactor != 1.0
            if (wasZoomed) scaleDocFonts(1.0 / zoomFactor)
            val baos = ByteArrayOutputStream()
            rtfKit.write(baos, editorPane.document, 0, editorPane.document.length)
            vf.setBinaryContent(baos.toByteArray())
            if (wasZoomed) scaleDocFonts(zoomFactor)
            modified = false
        } catch (ex: Exception) { System.err.println("SM RTF save failed for ${vf.path}: ${ex.message}") }
        finally { saving = false }
    }

    private fun scaleDocFonts(ratio: Double) {
        val doc = editorPane.document as? StyledDocument ?: return; if (doc.length == 0) return
        detachListener()
        val root = doc.defaultRootElement
        for (pi in 0 until root.elementCount) {
            val para = root.getElement(pi)
            for (ci in 0 until para.elementCount) {
                val run = para.getElement(ci)
                val oldSize = StyleConstants.getFontSize(run.attributes)
                val newSize = Math.round(oldSize * ratio).toInt().coerceIn(4, 200)
                val fix = SimpleAttributeSet(); StyleConstants.setFontSize(fix, newSize)
                doc.setCharacterAttributes(run.startOffset, run.endOffset - run.startOffset, fix, false)
            }
        }
        attachListener()
    }

    override fun getFile(): VirtualFile = vf
    override fun getComponent(): JComponent = mainPanel
    override fun getPreferredFocusedComponent(): JComponent = editorPane
    override fun getName(): String = "Rich Text"
    override fun isModified(): Boolean = modified
    override fun isValid(): Boolean = vf.isValid
    override fun setState(state: FileEditorState) {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {
        saveTimer.stop()
        if (modified && vf.isValid) ApplicationManager.getApplication().runWriteAction { doSave() }
    }
}


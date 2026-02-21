package sm.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import sm.model.SmSettings
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.beans.PropertyChangeListener
import java.io.File
import java.nio.charset.StandardCharsets
import javax.swing.*
import javax.swing.event.TableModelEvent
import javax.swing.event.TableModelListener
import javax.swing.table.DefaultTableModel

/**
 * Table editor for .csv files.
 * RFC 4180 compliant: quoted fields may contain newlines, commas, quotes.
 * Cell-level focus (no full-row highlight). Zoom via Cmd+/-.
 */
class SmCsvFileEditor(
    private val project: Project,
    private val vf: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private var modified = false
    private var loading = false
    private val saveTimer = Timer(1500) { saveBoth() }.apply { isRepeats = false }
    private val BASE_FONT_SIZE = 13
    private val BASE_FONT = Font("Menlo", Font.PLAIN, BASE_FONT_SIZE).let {
        if (it.family == "Menlo") it else Font(Font.MONOSPACED, Font.PLAIN, BASE_FONT_SIZE)
    }
    private var CODE_FONT = BASE_FONT
    private val ZOOM_LEVELS = doubleArrayOf(0.5, 0.67, 0.75, 0.8, 0.9, 1.0, 1.1, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0)
    private var zoomFactor = 1.0
    private val csvZoomLabel = JLabel("100%").apply {
        preferredSize = Dimension(48, 28); horizontalAlignment = SwingConstants.CENTER
    }

    private val tableModel = DefaultTableModel()

    private val table = object : JBTable(tableModel) {
        override fun prepareRenderer(renderer: javax.swing.table.TableCellRenderer, row: Int, column: Int): Component {
            return renderer.getTableCellRendererComponent(this, getValueAt(row, column),
                isCellSelected(row, column), hasFocus() && row == selectedRow && column == selectedColumn, row, column)
        }
    }.apply {
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        setShowGrid(true)
        gridColor = Color(200, 200, 200)
        rowHeight = 24
        font = CODE_FONT
        tableHeader.font = Font("SansSerif", Font.BOLD, 13)
        tableHeader.background = Color(240, 240, 240)
        tableHeader.foreground = Color.BLACK
        background = Color.WHITE
        foreground = Color.BLACK
        cellSelectionEnabled = true
        setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION)
        selectionBackground = Color.WHITE
        selectionForeground = Color.BLACK
    }

    private val cellRenderer = object : javax.swing.table.TableCellRenderer {
        private val textArea = JTextArea().apply {
            font = CODE_FONT; lineWrap = true; wrapStyleWord = true; isOpaque = true
        }
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            textArea.text = value?.toString() ?: ""
            textArea.font = CODE_FONT
            textArea.background = Color.WHITE
            textArea.foreground = Color.BLACK
            textArea.border = if (hasFocus)
                BorderFactory.createLineBorder(Color(100, 149, 237), 2)
            else
                BorderFactory.createEmptyBorder(2, 4, 2, 4)
            val colWidth = table.columnModel.getColumn(column).width
            textArea.setSize(colWidth, Short.MAX_VALUE.toInt())
            val prefH = textArea.preferredSize.height.coerceAtLeast(24)
            val currentRowH = table.getRowHeight(row)
            if (prefH > currentRowH) {
                SwingUtilities.invokeLater { if (table.getRowHeight(row) < prefH) table.setRowHeight(row, prefH) }
            }
            return textArea
        }
    }

    private val multilineEditor = object : DefaultCellEditor(JTextField()) {
        private val textArea = JTextArea().apply {
            font = CODE_FONT; lineWrap = true; wrapStyleWord = true
            border = BorderFactory.createLineBorder(Color(100, 149, 237), 2)
        }
        private val scroll = JBScrollPane(textArea)
        private var editingRow = -1; private var editingTable: JTable? = null
        init { clickCountToStart = 2 }
        override fun getTableCellEditorComponent(tbl: JTable, value: Any?, isSelected: Boolean, row: Int, col: Int): Component {
            textArea.text = value?.toString() ?: ""
            textArea.font = CODE_FONT
            editingRow = row; editingTable = tbl
            val colW = tbl.columnModel.getColumn(col).width
            scroll.preferredSize = Dimension(colW, 80.coerceAtLeast(tbl.getRowHeight(row)))
            textArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = growRow()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = growRow()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = growRow()
                private fun growRow() {
                    SwingUtilities.invokeLater {
                        textArea.setSize(colW, Short.MAX_VALUE.toInt())
                        val h = textArea.preferredSize.height.coerceAtLeast(24) + 8
                        if (tbl.getRowHeight(row) < h) tbl.setRowHeight(row, h)
                    }
                }
            })
            return scroll
        }
        override fun getCellEditorValue(): Any = textArea.text
        override fun stopCellEditing(): Boolean {
            val ok = super.stopCellEditing()
            if (ok && editingTable != null) recalcAllRowHeights(editingTable!!)
            return ok
        }
    }

    private val mainPanel: JPanel

    init {
        table.setDefaultRenderer(Any::class.java, cellRenderer)
        table.setDefaultEditor(Any::class.java, multilineEditor)
        table.componentPopupMenu = buildPopup()

        table.tableHeader.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) { headerPopup(e) }
            override fun mouseReleased(e: java.awt.event.MouseEvent) { headerPopup(e) }
            private fun headerPopup(e: java.awt.event.MouseEvent) {
                if (!e.isPopupTrigger) return
                val colIdx = table.columnAtPoint(e.point)
                if (colIdx < 0) return
                val popup = JPopupMenu()
                popup.add(JMenuItem("Rename Column\u2026").apply { addActionListener { renameColumnAt(colIdx) } })
                popup.add(JMenuItem("Insert Column Left").apply { addActionListener { insertColumnAt(colIdx, "NewCol") } })
                popup.add(JMenuItem("Insert Column Right").apply { addActionListener { insertColumnAt(colIdx + 1, "NewCol") } })
                popup.addSeparator()
                popup.add(JMenuItem("Delete Column").apply { addActionListener { deleteColumnAt(colIdx) } })
                popup.show(table.tableHeader, e.x, e.y)
            }
        })

        val im = table.getInputMap(JComponent.WHEN_FOCUSED)
        val am = table.actionMap
        val meta = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, meta), "sm-copy")
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, meta), "sm-paste")
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, meta), "sm-cut")
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, meta), "sm-zoom-in")
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, meta), "sm-zoom-out")
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_0, meta), "sm-zoom-reset")
        am.put("sm-copy", object : AbstractAction() { override fun actionPerformed(e: ActionEvent) { copyCells() } })
        am.put("sm-paste", object : AbstractAction() { override fun actionPerformed(e: ActionEvent) { pasteCells() } })
        am.put("sm-cut", object : AbstractAction() { override fun actionPerformed(e: ActionEvent) { cutCells() } })
        am.put("sm-zoom-in", object : AbstractAction() { override fun actionPerformed(e: ActionEvent) { csvZoomIn() } })
        am.put("sm-zoom-out", object : AbstractAction() { override fun actionPerformed(e: ActionEvent) { csvZoomOut() } })
        am.put("sm-zoom-reset", object : AbstractAction() { override fun actionPerformed(e: ActionEvent) { csvZoomReset() } })

        val zoomBar = JToolBar().apply {
            isFloatable = false; border = JBUI.Borders.empty(2)
            add(JButton("\u2212").apply { toolTipText = "Zoom out"; addActionListener { csvZoomOut() } })
            add(csvZoomLabel)
            add(JButton("+").apply { toolTipText = "Zoom in"; addActionListener { csvZoomIn() } })
        }

        mainPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty()
            background = Color.WHITE
            val scrollPane = JBScrollPane(table)
            scrollPane.viewport.background = Color.WHITE
            scrollPane.background = Color.WHITE
            add(zoomBar, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        loadFromVfs()
        loadColWidths()
        val savedZoom = SmSettings.getZoom(vf.path)
        if (savedZoom != 1.0) applyCsvZoom(savedZoom)
        SwingUtilities.invokeLater { recalcAllRowHeights(table) }

        tableModel.addTableModelListener(object : TableModelListener {
            override fun tableChanged(e: TableModelEvent) {
                if (!loading) { onEdit(); SwingUtilities.invokeLater { recalcAllRowHeights(table) } }
            }
        })
    }

    private fun recalcAllRowHeights(tbl: JTable) {
        val scratchArea = JTextArea().apply { font = CODE_FONT; lineWrap = true; wrapStyleWord = true }
        for (row in 0 until tbl.rowCount) {
            var maxH = 24
            for (col in 0 until tbl.columnCount) {
                val txt = tbl.getValueAt(row, col)?.toString() ?: ""
                val colW = tbl.columnModel.getColumn(col).width.coerceAtLeast(50)
                scratchArea.text = txt
                scratchArea.setSize(colW, Short.MAX_VALUE.toInt())
                maxH = maxOf(maxH, scratchArea.preferredSize.height + 4)
            }
            if (tbl.getRowHeight(row) != maxH) tbl.setRowHeight(row, maxH)
        }
    }

    private fun csvZoomIn() {
        val idx = ZOOM_LEVELS.indexOfFirst { it > zoomFactor + 0.001 }
        if (idx >= 0) applyCsvZoom(ZOOM_LEVELS[idx])
    }
    private fun csvZoomOut() {
        val idx = ZOOM_LEVELS.indexOfLast { it < zoomFactor - 0.001 }
        if (idx >= 0) applyCsvZoom(ZOOM_LEVELS[idx])
    }
    private fun csvZoomReset() = applyCsvZoom(1.0)

    private fun applyCsvZoom(factor: Double) {
        val ratio = factor / zoomFactor
        zoomFactor = factor
        csvZoomLabel.text = "${(factor * 100).toInt()}%"
        val newSize = (BASE_FONT_SIZE * factor).toInt().coerceIn(8, 72)
        CODE_FONT = BASE_FONT.deriveFont(newSize.toFloat())
        table.font = CODE_FONT
        table.tableHeader.font = Font("SansSerif", Font.BOLD, newSize)
        table.rowHeight = (24 * factor).toInt().coerceAtLeast(16)
        val cm = table.columnModel
        for (i in 0 until cm.columnCount) {
            val col = cm.getColumn(i)
            col.preferredWidth = (col.preferredWidth * ratio).toInt().coerceAtLeast(30)
            col.width = col.preferredWidth
        }
        SmSettings.setZoom(vf.path, factor)
        SwingUtilities.invokeLater { recalcAllRowHeights(table) }
    }

    private fun buildPopup(): JPopupMenu = JPopupMenu().apply {
        add(JMenuItem("Copy").apply { addActionListener { copyCells() } })
        add(JMenuItem("Cut").apply { addActionListener { cutCells() } })
        add(JMenuItem("Paste").apply { addActionListener { pasteCells() } })
        addSeparator()
        add(JMenuItem("Insert Row Above").apply { addActionListener { insertRowAbove() } })
        add(JMenuItem("Insert Row Below").apply { addActionListener { insertRowBelow() } })
        add(JMenuItem("Add Row at End").apply { addActionListener { tableModel.addRow(Array(tableModel.columnCount) { "" }); onEdit() } })
        addSeparator()
        add(JMenuItem("Delete Row(s)").apply { addActionListener { deleteSelectedRows() } })
        addSeparator()
        add(JMenuItem("Insert Column Left").apply { addActionListener { insertColumnLeft() } })
        add(JMenuItem("Insert Column Right").apply { addActionListener { insertColumnRight() } })
        add(JMenuItem("Add Column\u2026").apply { addActionListener { addColumn() } })
        add(JMenuItem("Rename Column\u2026").apply { addActionListener { renameColumn() } })
        add(JMenuItem("Delete Column").apply { addActionListener { deleteColumn() } })
    }

    private fun copyCells() {
        val rows = table.selectedRows; val cols = table.selectedColumns
        if (rows.isEmpty() || cols.isEmpty()) return
        val sb = StringBuilder()
        for (r in rows) {
            for ((ci, c) in cols.withIndex()) { if (ci > 0) sb.append('\t'); sb.append(tableModel.getValueAt(r, c)?.toString() ?: "") }
            sb.append('\n')
        }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(sb.toString()), null)
    }

    private fun cutCells() { copyCells(); for (r in table.selectedRows) for (c in table.selectedColumns) tableModel.setValueAt("", r, c); onEdit() }

    private fun pasteCells() {
        try {
            val text = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String ?: return
            val lines = text.trimEnd('\n').split('\n')
            val startRow = table.selectedRow.coerceAtLeast(0); val startCol = table.selectedColumn.coerceAtLeast(0)
            loading = true
            for ((ri, line) in lines.withIndex()) {
                val cells = line.split('\t'); val row = startRow + ri
                while (row >= tableModel.rowCount) tableModel.addRow(Array(tableModel.columnCount) { "" })
                for ((ci, cell) in cells.withIndex()) { val col = startCol + ci; if (col < tableModel.columnCount) tableModel.setValueAt(cell, row, col) }
            }
            loading = false; onEdit()
        } catch (_: Exception) { loading = false }
    }

    private fun insertRowAbove() { val r = table.selectedRow; if (r >= 0) { tableModel.insertRow(r, Array(tableModel.columnCount) { "" }); onEdit() } }
    private fun insertRowBelow() { val r = table.selectedRow; tableModel.insertRow(if (r < 0) tableModel.rowCount else r + 1, Array(tableModel.columnCount) { "" }); onEdit() }
    private fun deleteSelectedRows() { for (r in table.selectedRows.sortedDescending()) if (r in 0 until tableModel.rowCount) tableModel.removeRow(r); onEdit() }

    private fun addColumn() { val n = JOptionPane.showInputDialog(table, "Column name:", "Add Column", JOptionPane.PLAIN_MESSAGE); if (!n.isNullOrBlank()) { tableModel.addColumn(n); onEdit() } }
    private fun insertColumnLeft() { val c = table.selectedColumn; if (c >= 0) insertColumnAt(c, "NewCol") }
    private fun insertColumnRight() { val c = table.selectedColumn; insertColumnAt(if (c < 0) tableModel.columnCount else c + 1, "NewCol") }
    private fun insertColumnAt(idx: Int, name: String) {
        val ids = (0 until tableModel.columnCount).map { tableModel.getColumnName(it) }.toMutableList(); ids.add(idx, name)
        val data = Array(tableModel.rowCount) { row ->
            val nr = Array(ids.size) { "" as Any }; for (c in 0 until tableModel.columnCount) { nr[if (c < idx) c else c + 1] = tableModel.getValueAt(row, c) ?: "" }; nr
        }
        loading = true; tableModel.setRowCount(0); tableModel.setColumnCount(0); ids.forEach { tableModel.addColumn(it) }; data.forEach { tableModel.addRow(it) }; loading = false; onEdit()
    }
    private fun renameColumn() { val col = table.selectedColumn; if (col >= 0) renameColumnAt(col) }
    private fun renameColumnAt(col: Int) {
        val old = tableModel.getColumnName(col)
        val name = JOptionPane.showInputDialog(table, "Rename '$old' to:", "Rename Column", JOptionPane.PLAIN_MESSAGE, null, null, old) as? String
        if (!name.isNullOrBlank() && name != old) {
            val ids = Array(tableModel.columnCount) { tableModel.getColumnName(it) }; ids[col] = name
            val data = Array(tableModel.rowCount) { r -> Array(tableModel.columnCount) { c -> tableModel.getValueAt(r, c) } }
            loading = true; tableModel.setColumnIdentifiers(ids); for (r in data.indices) for (c in data[r].indices) tableModel.setValueAt(data[r][c], r, c); loading = false; onEdit()
        }
    }
    private fun deleteColumn() { val col = table.selectedColumn; if (col >= 0) deleteColumnAt(col) }
    private fun deleteColumnAt(col: Int) {
        if (col < 0 || tableModel.columnCount <= 1) return
        val ids = (0 until tableModel.columnCount).filter { it != col }.map { tableModel.getColumnName(it) }
        val data = Array(tableModel.rowCount) { r -> (0 until tableModel.columnCount).filter { it != col }.map { tableModel.getValueAt(r, it) }.toTypedArray() }
        loading = true; tableModel.setRowCount(0); tableModel.setColumnCount(0); ids.forEach { tableModel.addColumn(it) }; data.forEach { tableModel.addRow(it) }; loading = false; onEdit()
    }

    private fun loadFromVfs() {
        loading = true
        try {
            val text = String(vf.contentsToByteArray(), StandardCharsets.UTF_8)
            val records = parseCsv(text)
            tableModel.setRowCount(0); tableModel.setColumnCount(0)
            if (records.isEmpty()) { tableModel.addColumn("A"); tableModel.addRow(arrayOf("")) }
            else {
                records[0].forEach { tableModel.addColumn(it) }
                for (i in 1 until records.size) {
                    val padded = Array(records[0].size) { idx -> if (idx < records[i].size) records[i][idx] else "" }
                    tableModel.addRow(padded)
                }
            }
            modified = false
        } catch (_: Exception) {
            tableModel.setColumnCount(1); tableModel.setColumnIdentifiers(arrayOf("Error"))
            tableModel.addRow(arrayOf("Could not load CSV"))
        } finally { loading = false }
    }

    private fun onEdit() { modified = true; saveTimer.restart() }

    private fun saveBoth() {
        ApplicationManager.getApplication().invokeLater {
            if (!modified || !vf.isValid) return@invokeLater
            ApplicationManager.getApplication().runWriteAction { saveToVfs(); saveColWidths() }
        }
    }

    private fun saveToVfs() {
        try {
            val sb = StringBuilder()
            val cols = (0 until tableModel.columnCount).map { tableModel.getColumnName(it) }
            sb.append(cols.joinToString(",") { escapeCsv(it) }).append("\r\n")
            for (row in 0 until tableModel.rowCount) {
                val cells = (0 until tableModel.columnCount).map { escapeCsv(tableModel.getValueAt(row, it)?.toString() ?: "") }
                sb.append(cells.joinToString(",")).append("\r\n")
            }
            vf.setBinaryContent(sb.toString().toByteArray(StandardCharsets.UTF_8))
            modified = false
        } catch (ex: Exception) { System.err.println("SM CSV save failed for ${vf.path}: ${ex.message}") }
    }

    private fun metaFile(): File { val f = VfsUtilCore.virtualToIoFile(vf); return File(f.parentFile, f.name + ".sm") }
    private fun loadColWidths() {
        try {
            var f = metaFile(); if (!f.exists()) { f = File(f.parentFile, vf.name + ".joe"); if (!f.exists()) { setDefaultColWidths(); return } }
            val txt = f.readText(Charsets.UTF_8)
            val s = txt.indexOf('['); val e = txt.indexOf(']')
            if (s < 0 || e < 0) { setDefaultColWidths(); return }
            val widths = txt.substring(s + 1, e).split(',').mapNotNull { it.trim().toIntOrNull() }
            val cm = table.columnModel; for (i in widths.indices) if (i < cm.columnCount) cm.getColumn(i).preferredWidth = widths[i]
        } catch (_: Exception) { setDefaultColWidths() }
    }
    private fun setDefaultColWidths() { val cm = table.columnModel; for (i in 0 until cm.columnCount) cm.getColumn(i).preferredWidth = 150 }
    private fun saveColWidths() {
        try {
            val cm = table.columnModel; val w = (0 until cm.columnCount).map { cm.getColumn(it).width }
            metaFile().writeText("{\n  \"colWidths\": [${w.joinToString(", ")}]\n}\n", Charsets.UTF_8)
        } catch (_: Exception) { }
    }

    private fun parseCsv(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0; var inQuotes = false
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') { if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i += 2 } else { inQuotes = false; i++ } }
                else { sb.append(ch); i++ }
            } else {
                when (ch) {
                    '"' -> { inQuotes = true; i++ }
                    ',' -> { fields.add(sb.toString()); sb.clear(); i++ }
                    '\r' -> { fields.add(sb.toString()); sb.clear(); records.add(fields.toList()); fields.clear(); i++; if (i < text.length && text[i] == '\n') i++ }
                    '\n' -> { fields.add(sb.toString()); sb.clear(); records.add(fields.toList()); fields.clear(); i++ }
                    else -> { sb.append(ch); i++ }
                }
            }
        }
        if (sb.isNotEmpty() || fields.isNotEmpty()) { fields.add(sb.toString()); records.add(fields.toList()) }
        if (records.isNotEmpty() && records.last().size == 1 && records.last()[0].isEmpty()) records.removeAt(records.size - 1)
        return records
    }

    private fun escapeCsv(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    override fun getFile(): VirtualFile = vf
    override fun getComponent(): JComponent = mainPanel
    override fun getPreferredFocusedComponent(): JComponent = table
    override fun getName(): String = "Table"
    override fun isModified(): Boolean = modified
    override fun isValid(): Boolean = vf.isValid
    override fun setState(state: FileEditorState) {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() { saveTimer.stop(); if (modified) { saveToVfs(); saveColWidths() } }
}


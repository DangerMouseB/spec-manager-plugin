package sm.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileMoveEvent
import com.intellij.openapi.vfs.VirtualFilePropertyEvent
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.ui.JBUI
import sm.SmIcons
import sm.model.SmMeta
import sm.model.SmSettings
import sm.model.SmTreeBuilder
import sm.model.SmTreeTransferHandler
import sm.model.NodeData
import sm.model.VfNode
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.prefs.Preferences
import javax.swing.*
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.event.TreeSelectionEvent
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * SM tool window: tree panel with multiple roots, persistent config, right-click context menu.
 * Cmd+Shift+. toggles hidden folders. Expanded state persisted across restarts.
 */
class SmToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val tree = SimpleTree()
    private val hiddenRoot = DefaultMutableTreeNode("SM")
    private val treeModel = object : DefaultTreeModel(hiddenRoot) {
        override fun isLeaf(node: Any?): Boolean {
            val data = (node as? DefaultMutableTreeNode)?.userObject as? NodeData ?: return super.isLeaf(node)
            val vf = data.vf ?: return true
            return !vf.isDirectory
        }
    }
    private val roots = mutableListOf<VirtualFile>()
    private var showHidden = SmSettings.showHiddenFolders()
    private var syncing = false
    private val expandSaveTimer = Timer(800) { persistExpandedPaths() }.apply { isRepeats = false }
    private val vfsRefreshTimer = Timer(500) { SwingUtilities.invokeLater { refreshAll() } }.apply { isRepeats = false }

    init {
        border = JBUI.Borders.empty()
        tree.model = treeModel; tree.isRootVisible = false; tree.showsRootHandles = true

        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            private val HIDDEN_COLOR = Color(140, 140, 140)
            private val BROKEN_COLOR = Color(200, 80, 80)
            private val CIRCULAR_COLOR = Color(160, 160, 160)
            override fun getTreeCellRendererComponent(
                tree: JTree, value: Any?, sel: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
            ): Component {
                val c = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                val node = value as? DefaultMutableTreeNode; val data = node?.userObject as? NodeData
                if (data != null) {
                    val base = c.font ?: UIManager.getFont("Tree.font") ?: Font("SansSerif", Font.PLAIN, 12)
                    when {
                        data.isCircularLink -> { c.font = base.deriveFont(Font.ITALIC); if (!sel) foreground = CIRCULAR_COLOR; toolTipText = "Circular link: ${data.linkTarget}" }
                        data.isBrokenLink -> { c.font = base.deriveFont(Font.ITALIC); if (!sel) foreground = BROKEN_COLOR; icon = SmIcons.LINK_BROKEN; toolTipText = "Broken link → ${data.linkTarget}" }
                        data.isLink -> { c.font = base.deriveFont(Font.PLAIN); toolTipText = "Link → ${data.linkTarget}"; val baseIcon = icon; if (baseIcon != null) { icon = com.intellij.ui.LayeredIcon(2).apply { setIcon(baseIcon, 0); setIcon(SmIcons.LINK_OVERLAY, 1) } } }
                        data.isHiddenFolder -> { c.font = base.deriveFont(Font.ITALIC); if (!sel) foreground = HIDDEN_COLOR; toolTipText = null }
                        else -> { c.font = base.deriveFont(Font.PLAIN); toolTipText = null }
                    }
                }
                return c
            }
        }
        tree.toolTipText = ""

        tree.addTreeSelectionListener(object : TreeSelectionListener {
            override fun valueChanged(e: TreeSelectionEvent) {
                if (syncing) return
                val path = e.newLeadSelectionPath ?: return; val node = path.lastPathComponent as? VfNode ?: return
                val data = node.userObject as? NodeData ?: return; val vf = data.vf ?: return
                if (!vf.isDirectory) { syncing = true; openInEditor(vf, requestFocus = false); syncing = false }
            }
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && !e.isPopupTrigger) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return; val node = path.lastPathComponent as? VfNode ?: return
                    val data = node.userObject as? NodeData ?: return; val vf = data.vf ?: return
                    if (!vf.isDirectory) openInEditor(vf, requestFocus = true)
                }
            }
        })
        tree.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER) {
                    val path = tree.selectionPath ?: return; val node = path.lastPathComponent as? VfNode ?: return
                    val data = node.userObject as? NodeData ?: return; val vf = data.vf ?: return
                    if (!vf.isDirectory) openInEditor(vf, requestFocus = true)
                }
            }
        })

        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(e: TreeExpansionEvent) { expandSaveTimer.restart() }
            override fun treeCollapsed(e: TreeExpansionEvent) { expandSaveTimer.restart() }
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { showPopupIfNeeded(e) }
            override fun mouseReleased(e: MouseEvent) { showPopupIfNeeded(e) }
            private fun showPopupIfNeeded(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    val path = tree.getPathForLocation(e.x, e.y); if (path != null) tree.selectionPath = path
                    buildTreePopup().show(tree, e.x, e.y); e.consume()
                }
            }
        })

        val im = tree.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW); val am = tree.actionMap
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, KeyEvent.META_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK), "sm-toggle-hidden")
        am.put("sm-toggle-hidden", object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent) { toggleHiddenFolders() } })

        tree.dragEnabled = true; tree.dropMode = DropMode.INSERT; tree.transferHandler = SmTreeTransferHandler(project)
        add(JBScrollPane(tree), BorderLayout.CENTER)

        project.messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) { val vf = event.newFile ?: return; if (!syncing) selectNodeForFile(vf) }
        })

        LocalFileSystem.getInstance().addVirtualFileListener(object : VirtualFileListener {
            override fun fileCreated(event: VirtualFileEvent) { onVfsChange(event.file) }
            override fun fileDeleted(event: VirtualFileEvent) { onVfsChange(event.file) }
            override fun fileMoved(event: VirtualFileMoveEvent) { onVfsChange(event.file) }
            override fun propertyChanged(event: VirtualFilePropertyEvent) { if (event.propertyName == VirtualFile.PROP_NAME) onVfsChange(event.file) }
            private fun onVfsChange(vf: VirtualFile) { if (roots.any { vf.path.startsWith(it.path) }) vfsRefreshTimer.restart() }
        })

        loadPersistedRoots()
    }

    private fun openInEditor(vf: VirtualFile, requestFocus: Boolean = false) { FileEditorManager.getInstance(project).openFile(vf, requestFocus) }

    private fun selectNodeForFile(vf: VirtualFile) {
        val node = findNodeByPath(hiddenRoot, vf.path) ?: return; val tp = TreePath(node.path)
        syncing = true; tree.selectionPath = tp; tree.scrollPathToVisible(tp); syncing = false
    }

    private fun findNodeByPath(root: DefaultMutableTreeNode, path: String): DefaultMutableTreeNode? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as DefaultMutableTreeNode; val data = child.userObject as? NodeData
            if (data?.vf != null && data.vf.path == path) return child
            if (child.childCount > 0) { val found = findNodeByPath(child, path); if (found != null) return found }
        }
        return null
    }

    fun toggleHiddenFolders() { showHidden = !showHidden; SmSettings.setShowHiddenFolders(showHidden); refreshAll() }

    private fun buildTreePopup(): JPopupMenu {
        val popup = JPopupMenu()
        popup.add(JMenuItem("Add Root…").apply { addActionListener { addRoot() } })
        popup.add(JMenuItem("Remove This Root").apply { addActionListener { removeSelectedRoot() } })
        popup.addSeparator()

        val selPath = tree.selectionPath; val selNode = selPath?.lastPathComponent as? VfNode
        val selData = selNode?.userObject as? NodeData; val selVf = selData?.vf

        if (selData != null && selData.isLink) {
            if (selData.isBrokenLink) {
                popup.add(JMenuItem("Edit Link…").apply { addActionListener { editLink(selNode!!, selData) } })
                popup.add(JMenuItem("Remove Link").apply { addActionListener { removeLink(selNode!!, selData) } })
            } else {
                popup.add(JMenuItem("Edit Link…").apply { addActionListener { editLink(selNode!!, selData) } })
                popup.add(JMenuItem("Remove Link").apply { addActionListener { removeLink(selNode!!, selData) } })
                if (selVf != null) {
                    popup.add(JMenuItem("Open Link Target in Finder").apply { addActionListener {
                        if (selVf.isDirectory) Desktop.getDesktop().open(java.io.File(selVf.path)) else Runtime.getRuntime().exec(arrayOf("open", "-R", selVf.path))
                    } })
                }
            }
            popup.addSeparator()
        }

        if (selData != null && !selData.isLink && selVf != null && !selVf.isDirectory && selVf.extension != null) {
            val showing = selData.effectiveShowExtension()
            popup.add(JMenuItem(if (showing) "Hide Extension" else "Show Extension").apply { addActionListener { selData.showExtension = !showing; treeModel.nodeChanged(selNode) } })
            popup.addSeparator()
        }

        if (selVf != null) {
            val copyPathMenu = JMenu("Copy Path/Reference…")
            copyPathMenu.add(JMenuItem("Absolute Path").apply { addActionListener { copyToClipboard(selVf.path) } })
            val relPath = roots.firstOrNull { selVf.path.startsWith(it.path) }?.let { selVf.path.removePrefix(it.path).removePrefix("/") }
            if (relPath != null) copyPathMenu.add(JMenuItem("Path From Root").apply { addActionListener { copyToClipboard(relPath) } })
            copyPathMenu.add(JMenuItem("Filename").apply { addActionListener { copyToClipboard(selVf.name) } })
            popup.add(copyPathMenu)
        }

        if (selVf != null && !selData!!.isLink) {
            val target = if (selVf.isDirectory) selVf else selVf.parent
            if (target != null) {
                popup.add(JMenuItem("Open in Finder").apply { addActionListener {
                    if (selVf.isDirectory) Desktop.getDesktop().open(java.io.File(selVf.path)) else Runtime.getRuntime().exec(arrayOf("open", "-R", selVf.path))
                } })
                popup.add(JMenuItem("Open in Terminal").apply { addActionListener { Runtime.getRuntime().exec(arrayOf("open", "-a", "Terminal", target.path)) } })
            }
            popup.addSeparator()
        }

        if (selVf != null && !selVf.isDirectory && !selData!!.isLink) {
            val parentVf = selVf.parent
            if (parentVf != null) popup.add(JMenuItem("New File…").apply { addActionListener { createFile(parentVf) } })
            popup.add(JMenuItem("Rename…").apply { addActionListener { renameFile(selNode!!, selData) } })
            popup.addSeparator()
        }

        if (selVf != null && selVf.isDirectory && !selData!!.isLink) {
            popup.add(JMenuItem("New File…").apply { addActionListener { createFile(selVf) } })
            popup.add(JMenuItem("New Folder…").apply { addActionListener { createSubfolder(selNode!!, selData) } })
            popup.addSeparator()
            popup.add(JMenuItem("Rename…").apply { addActionListener { renameFolder(selNode!!, selData) } })
            popup.add(JMenuItem("Delete Folder").apply { addActionListener { deleteFolder(selNode!!, selData) } })
            popup.addSeparator()
            popup.add(JMenuItem("Add Link…").apply { addActionListener { addLink(selNode!!, selData) } })
            popup.addSeparator()
            popup.add(JMenuItem("Folder Alias…").apply { addActionListener { setAliasForNode(selNode!!, selData) } })
            popup.addSeparator()
        }

        val hiddenLabel = if (showHidden) "Hide Hidden Folders" else "Show Hidden Folders"
        popup.add(JMenuItem("$hiddenLabel  (⌘⇧.)").apply { addActionListener { toggleHiddenFolders() } })
        popup.addSeparator()
        popup.add(JMenuItem("Settings…").apply { addActionListener { openSettings() } })
        popup.addSeparator()
        popup.add(JMenuItem("Refresh").apply { addActionListener { refreshAll() } })
        return popup
    }

    fun openSettings() { SmSettingsDialog(roots.toList()) { refreshAll() }.show() }

    private fun setAliasForNode(node: VfNode, data: NodeData) {
        val vf = data.vf ?: return; val suggestion = data.alias ?: vf.path
        val input = JOptionPane.showInputDialog(tree, "Folder alias for '${vf.path}':\n(leave empty to reset to folder name)",
            "Folder Alias", JOptionPane.PLAIN_MESSAGE, null, null, suggestion) as? String
        if (input == null) return; val newAlias = input.trim()
        if (newAlias.isEmpty() || newAlias == vf.name || newAlias == vf.path) { data.alias = null; SmSettings.setAlias(vf.path, null) }
        else { data.alias = newAlias; SmSettings.setAlias(vf.path, newAlias) }
        treeModel.nodeChanged(node)
    }

    private fun createSubfolder(parentNode: VfNode, parentData: NodeData) {
        val parentVf = parentData.vf ?: return
        val name = JOptionPane.showInputDialog(tree, "New folder name:", "New Folder", JOptionPane.PLAIN_MESSAGE)
        if (name.isNullOrBlank()) return; val trimmed = name.trim()
        if (trimmed.contains('/') || trimmed.contains('\\')) { JOptionPane.showMessageDialog(tree, "Folder name cannot contain / or \\", "Invalid Name", JOptionPane.ERROR_MESSAGE); return }
        ApplicationManager.getApplication().runWriteAction {
            try { parentVf.createChildDirectory(this, trimmed) }
            catch (ex: Exception) { SwingUtilities.invokeLater { JOptionPane.showMessageDialog(tree, "Could not create folder: ${ex.message}", "Error", JOptionPane.ERROR_MESSAGE) } }
        }
        refreshAll()
    }

    private fun createFile(parentVf: VirtualFile) {
        val name = JOptionPane.showInputDialog(tree, "New file name (include extension):", "New File", JOptionPane.PLAIN_MESSAGE)
        if (name.isNullOrBlank()) return; val trimmed = name.trim()
        if (trimmed.contains('/') || trimmed.contains('\\')) { JOptionPane.showMessageDialog(tree, "Filename cannot contain / or \\", "Invalid Name", JOptionPane.ERROR_MESSAGE); return }
        if (parentVf.findChild(trimmed) != null) { JOptionPane.showMessageDialog(tree, "A file named '$trimmed' already exists.", "Cannot Create", JOptionPane.ERROR_MESSAGE); return }
        ApplicationManager.getApplication().runWriteAction {
            try {
                val newFile = parentVf.createChildData(this, trimmed)
                SwingUtilities.invokeLater { refreshAll(); openInEditor(newFile, requestFocus = true) }
            } catch (ex: Exception) { SwingUtilities.invokeLater { JOptionPane.showMessageDialog(tree, "Could not create file: ${ex.message}", "Error", JOptionPane.ERROR_MESSAGE) } }
        }
    }

    private fun renameFolder(node: VfNode, data: NodeData) {
        val vf = data.vf ?: return; val oldName = vf.name
        val input = JOptionPane.showInputDialog(tree, "Rename folder '$oldName' to:", "Rename", JOptionPane.PLAIN_MESSAGE, null, null, oldName) as? String
        if (input.isNullOrBlank()) return; val newName = input.trim(); if (newName == oldName) return
        if (newName.contains('/') || newName.contains('\\')) { JOptionPane.showMessageDialog(tree, "Folder name cannot contain / or \\", "Invalid Name", JOptionPane.ERROR_MESSAGE); return }
        val oldPath = vf.path; val parentVf = vf.parent
        ApplicationManager.getApplication().runWriteAction {
            try {
                vf.rename(this, newName)
                val alias = SmSettings.getAlias(oldPath); if (alias != null) { SmSettings.setAlias(oldPath, null); SmSettings.setAlias(vf.path, alias) }
                if (parentVf != null) { val meta = SmMeta.read(parentVf); val idx = meta.order.indexOf(oldName); if (idx >= 0) { meta.order[idx] = newName; SmMeta.write(parentVf, meta) } }
            } catch (ex: Exception) { SwingUtilities.invokeLater { JOptionPane.showMessageDialog(tree, "Could not rename folder: ${ex.message}", "Error", JOptionPane.ERROR_MESSAGE) } }
        }
        refreshAll()
    }

    private fun renameFile(node: VfNode, data: NodeData) {
        val vf = data.vf ?: return; val oldName = vf.name
        val input = JOptionPane.showInputDialog(tree, "Rename '$oldName' to:", "Rename", JOptionPane.PLAIN_MESSAGE, null, null, oldName) as? String
        if (input.isNullOrBlank()) return; val newName = input.trim(); if (newName == oldName) return
        if (newName.contains('/') || newName.contains('\\')) { JOptionPane.showMessageDialog(tree, "Filename cannot contain / or \\", "Invalid Name", JOptionPane.ERROR_MESSAGE); return }
        val parentVf = vf.parent
        if (parentVf != null && parentVf.findChild(newName) != null) { JOptionPane.showMessageDialog(tree, "A file named '$newName' already exists.", "Cannot Rename", JOptionPane.ERROR_MESSAGE); return }
        ApplicationManager.getApplication().runWriteAction {
            try {
                vf.rename(this, newName)
                if (parentVf != null) { val meta = SmMeta.read(parentVf); val idx = meta.order.indexOf(oldName); if (idx >= 0) { meta.order[idx] = newName; SmMeta.write(parentVf, meta) } }
            } catch (ex: Exception) { SwingUtilities.invokeLater { JOptionPane.showMessageDialog(tree, "Could not rename file: ${ex.message}", "Error", JOptionPane.ERROR_MESSAGE) } }
        }
        refreshAll()
    }

    private fun deleteFolder(node: VfNode, data: NodeData) {
        val folder = data.vf ?: return
        val children = folder.children.filter { it.name != ".sm" && !it.name.endsWith(".sm") && it.name != ".joe" && !it.name.endsWith(".joe") }
        if (children.isNotEmpty()) {
            val confirm = JOptionPane.showConfirmDialog(tree, "Folder '${folder.name}' is not empty (${children.size} items). Delete anyway?",
                "Delete Folder", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
            if (confirm != JOptionPane.YES_OPTION) return
        }
        ApplicationManager.getApplication().runWriteAction {
            try { folder.delete(this); SmSettings.setAlias(folder.path, null) }
            catch (ex: Exception) { SwingUtilities.invokeLater { JOptionPane.showMessageDialog(tree, "Could not delete folder: ${ex.message}", "Error", JOptionPane.ERROR_MESSAGE) } }
        }
        refreshAll()
    }

    fun getRoots(): List<VirtualFile> = roots.toList()
    private fun copyToClipboard(text: String) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }

    private fun addLink(folderNode: VfNode, folderData: NodeData) {
        val folder = folderData.vf ?: return; val dialog = SmLinkDialog(folder); dialog.isVisible = true; if (!dialog.confirmed) return
        val key = dialog.resultKey ?: return; val target = dialog.resultTarget ?: return
        val type = dialog.resultType ?: "file"; val displayName = dialog.resultDisplayName
        ApplicationManager.getApplication().runWriteAction {
            val meta = SmMeta.read(folder)
            if (meta.links.containsKey(key)) { SwingUtilities.invokeLater { JOptionPane.showMessageDialog(tree, "A link named '$key' already exists in this folder.", "Duplicate Link Name", JOptionPane.ERROR_MESSAGE) }; return@runWriteAction }
            meta.links[key] = SmMeta.LinkEntry(target, type, displayName); meta.order.add("link:$key"); SmMeta.write(folder, meta)
        }
        refreshAll()
    }

    private fun editLink(node: VfNode, data: NodeData) {
        val ownerFolder = data.linkOwnerFolder ?: return; val key = data.linkKey ?: return
        val dialog = SmLinkDialog(ownerFolder, key, data.linkTarget, data.linkDisplayName, data.linkType); dialog.isVisible = true; if (!dialog.confirmed) return
        val newTarget = dialog.resultTarget ?: return; val newType = dialog.resultType ?: data.linkType ?: "file"; val newDisplayName = dialog.resultDisplayName
        ApplicationManager.getApplication().runWriteAction { val meta = SmMeta.read(ownerFolder); meta.links[key] = SmMeta.LinkEntry(newTarget, newType, newDisplayName); SmMeta.write(ownerFolder, meta) }
        refreshAll()
    }

    private fun removeLink(node: VfNode, data: NodeData) {
        val ownerFolder = data.linkOwnerFolder ?: return; val key = data.linkKey ?: return
        val confirm = JOptionPane.showConfirmDialog(tree, "Remove link '$key'?\n(The linked file/folder will not be deleted.)", "Remove Link", JOptionPane.YES_NO_OPTION)
        if (confirm != JOptionPane.YES_OPTION) return
        ApplicationManager.getApplication().runWriteAction { val meta = SmMeta.read(ownerFolder); meta.links.remove(key); meta.order.remove("link:$key"); SmMeta.write(ownerFolder, meta) }
        refreshAll()
    }

    fun addRoot() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor(); descriptor.title = "Choose SM document root"
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        if (!chosen.isDirectory) return; if (roots.any { it.path == chosen.path }) return
        roots.add(chosen); rebuildTree(); persistRoots()
    }

    fun removeSelectedRoot() {
        val path = tree.selectionPath ?: return; if (path.pathCount < 2) return
        val rootNode = path.getPathComponent(1) as? VfNode ?: return; val data = rootNode.userObject as? NodeData ?: return
        roots.removeAll { it.path == data.vf?.path }; rebuildTree(); persistRoots()
    }

    fun refreshAll() {
        val expanded = saveExpandedPaths(); val selKey = tree.selectionPath?.let { treePathToKey(it) }
        doRebuild(); restoreExpandedPaths(expanded); if (selKey != null) restoreSelection(selKey); persistExpandedPaths()
    }

    private fun rebuildTree() {
        doRebuild()
        for (i in 0 until tree.rowCount) { val path = tree.getPathForRow(i) ?: continue; if (path.pathCount == 2) tree.expandRow(i) }
    }

    private fun doRebuild() { hiddenRoot.removeAllChildren(); for (rootVf in roots) hiddenRoot.add(SmTreeBuilder.buildNode(rootVf, showHidden)); treeModel.reload() }

    private fun saveExpandedPaths(): Set<String> {
        val paths = mutableSetOf<String>()
        for (i in 0 until tree.rowCount) { if (tree.isExpanded(i)) { val tp = tree.getPathForRow(i) ?: continue; paths.add(treePathToKey(tp)) } }
        return paths
    }

    private fun restoreExpandedPaths(expandedKeys: Set<String>) { var i = 0; while (i < tree.rowCount) { val tp = tree.getPathForRow(i); if (tp != null && treePathToKey(tp) in expandedKeys) tree.expandRow(i); i++ } }
    private fun restoreSelection(selKey: String) { for (i in 0 until tree.rowCount) { val tp = tree.getPathForRow(i) ?: continue; if (treePathToKey(tp) == selKey) { tree.selectionPath = tp; break } } }

    private fun treePathToKey(tp: TreePath): String {
        return (0 until tp.pathCount).mapNotNull { idx -> val node = tp.getPathComponent(idx) as? DefaultMutableTreeNode; val data = node?.userObject as? NodeData; data?.vf?.path ?: node?.userObject?.toString() }.joinToString("/")
    }

    private fun projectHash(): Int = project.basePath?.hashCode() ?: 0
    private fun prefsKey(): String = "sm.roots.${projectHash()}"

    private fun persistRoots() { val prefs = Preferences.userNodeForPackage(SmToolWindowPanel::class.java); prefs.put(prefsKey(), roots.joinToString("\n") { it.path }); prefs.flush() }
    private fun persistExpandedPaths() { SmSettings.setExpandedPaths(projectHash(), saveExpandedPaths()) }

    private fun loadPersistedRoots() {
        val prefs = Preferences.userNodeForPackage(SmToolWindowPanel::class.java)
        val paths = prefs.get(prefsKey(), ""); if (paths.isBlank()) return
        val fs = LocalFileSystem.getInstance()
        for (p in paths.split("\n")) { val vf = fs.findFileByPath(p); if (vf != null && vf.isDirectory) roots.add(vf) }
        if (roots.isNotEmpty()) {
            doRebuild()
            val saved = SmSettings.getExpandedPaths(projectHash())
            if (saved.isNotEmpty()) restoreExpandedPaths(saved)
            else { for (i in 0 until tree.rowCount) { val path = tree.getPathForRow(i) ?: continue; if (path.pathCount == 2) tree.expandRow(i) } }
        }
    }
}


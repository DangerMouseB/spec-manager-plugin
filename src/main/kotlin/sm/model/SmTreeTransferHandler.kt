package sm.model

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.TransferHandler
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Drag/drop reordering of nodes in the SM tree.
 * Supports both same-folder reorder and cross-folder moves (physically moves file on disk).
 */
class SmTreeTransferHandler(private val project: Project) : TransferHandler() {

    private val nodeFlavor = DataFlavor(
        DataFlavor.javaJVMLocalObjectMimeType + ";class=" + DefaultMutableTreeNode::class.java.name, "TreeNode"
    )

    override fun getSourceActions(c: JComponent): Int = MOVE

    override fun createTransferable(c: JComponent): Transferable {
        val path = (c as JTree).selectionPath ?: return SimpleTransferable(null, nodeFlavor)
        return SimpleTransferable(path.lastPathComponent as? DefaultMutableTreeNode, nodeFlavor)
    }

    override fun canImport(support: TransferSupport): Boolean {
        if (!support.isDrop) return false
        if (!support.isDataFlavorSupported(nodeFlavor)) return false
        val dl = support.dropLocation as? JTree.DropLocation ?: return false
        val path = dl.path ?: return false
        val targetNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return false
        val targetData = targetNode.userObject as? NodeData ?: return false
        val targetVf = targetData.vf ?: return false
        if (!targetVf.isDirectory) return false
        val dropped = try { support.transferable.getTransferData(nodeFlavor) as? DefaultMutableTreeNode } catch (_: Exception) { null }
        if (dropped != null && dropped === targetNode) return false
        if (dropped != null && isAncestor(dropped, targetNode)) return false
        val droppedData = dropped?.userObject as? NodeData
        if (droppedData != null && (droppedData.isBrokenLink || droppedData.isCircularLink)) return false
        return true
    }

    override fun importData(support: TransferSupport): Boolean {
        if (!canImport(support)) return false
        val tree = support.component as JTree; val model = tree.model as? DefaultTreeModel ?: return false
        val dl = support.dropLocation as JTree.DropLocation
        val targetParent = dl.path.lastPathComponent as DefaultMutableTreeNode
        val childIndex = dl.childIndex
        val dropped = support.transferable.getTransferData(nodeFlavor) as? DefaultMutableTreeNode ?: return false
        val droppedData = dropped.userObject as? NodeData ?: return false
        val sourceParent = dropped.parent as? DefaultMutableTreeNode ?: return false
        val targetData = targetParent.userObject as? NodeData ?: return false
        val sameParent = sourceParent === targetParent

        if (sameParent) {
            val oldIndex = targetParent.getIndex(dropped); if (oldIndex < 0) return false
            var newIndex = if (childIndex < 0) targetParent.childCount else childIndex
            if (newIndex > oldIndex) newIndex--; if (newIndex == oldIndex) return false
            model.removeNodeFromParent(dropped)
            model.insertNodeInto(dropped, targetParent, newIndex.coerceIn(0, targetParent.childCount))
            ApplicationManager.getApplication().runWriteAction { SmTreeBuilder.writeOrderForFolderNode(targetParent as VfNode) }
        } else {
            if (droppedData.isLink) {
                javax.swing.JOptionPane.showMessageDialog(tree,
                    "Links can only be reordered within the same folder.\nUse Edit Link\u2026 to change the target.",
                    "Cannot Move Link", javax.swing.JOptionPane.INFORMATION_MESSAGE)
                return false
            }
            val sourceVf = droppedData.vf ?: return false; val targetFolder = targetData.vf ?: return false
            if (targetFolder.findChild(sourceVf.name) != null) {
                javax.swing.JOptionPane.showMessageDialog(tree,
                    "A file or folder named '${sourceVf.name}' already exists in '${targetFolder.name}'.",
                    "Cannot Move", javax.swing.JOptionPane.ERROR_MESSAGE)
                return false
            }
            try { ApplicationManager.getApplication().runWriteAction { sourceVf.move(this, targetFolder) } }
            catch (ex: Exception) {
                javax.swing.JOptionPane.showMessageDialog(tree, "Move failed: ${ex.message}", "Error", javax.swing.JOptionPane.ERROR_MESSAGE)
                return false
            }
            model.removeNodeFromParent(dropped)
            val insertAt = if (childIndex < 0) targetParent.childCount else childIndex.coerceIn(0, targetParent.childCount)
            model.insertNodeInto(dropped, targetParent, insertAt)
            ApplicationManager.getApplication().runWriteAction {
                SmTreeBuilder.writeOrderForFolderNode(sourceParent as VfNode)
                SmTreeBuilder.writeOrderForFolderNode(targetParent as VfNode)
            }
        }
        val newPath = TreePath(dropped.path); tree.selectionPath = newPath; tree.scrollPathToVisible(newPath)
        return true
    }

    private fun isAncestor(ancestor: DefaultMutableTreeNode, node: DefaultMutableTreeNode): Boolean {
        var current = node.parent; while (current != null) { if (current === ancestor) return true; current = current.parent }; return false
    }

    private class SimpleTransferable(private val data: Any?, private val flavor: DataFlavor) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(flavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == this.flavor
        override fun getTransferData(flavor: DataFlavor): Any? = data
    }
}


package sm.model

import com.intellij.openapi.vfs.VirtualFile
import javax.swing.tree.DefaultMutableTreeNode

data class NodeData(
    val vf: VirtualFile?,
    var showExtension: Boolean? = null,
    /** Root path this node belongs to (for per-root settings lookup). Set by SmTreeBuilder. */
    var rootPath: String? = null,
    var alias: String? = null,
    var isHiddenFolder: Boolean = false,
    var linkKey: String? = null,
    var linkTarget: String? = null,
    var linkType: String? = null,
    var linkDisplayName: String? = null,
    var isBrokenLink: Boolean = false,
    var isCircularLink: Boolean = false,
    var linkOwnerFolder: VirtualFile? = null,
) {
    val isLink get() = linkKey != null

    fun effectiveShowExtension(): Boolean {
        val f = vf ?: return false; if (f.isDirectory) return false
        showExtension?.let { return it }
        val ext = f.extension?.lowercase() ?: return false
        return SmSettings.shouldShowExtension(ext, rootPath)
    }

    override fun toString(): String {
        if (isCircularLink) return "(circular)"
        if (isBrokenLink) return linkDisplayName ?: linkKey ?: "broken link"
        if (isLink) {
            val name = linkDisplayName ?: linkKey ?: ""
            val f = vf ?: return name
            if (f.isDirectory) return alias ?: name
            return if (effectiveShowExtension()) f.name else (f.nameWithoutExtension.ifEmpty { f.name })
        }
        val f = vf ?: return "?"
        if (f.isDirectory) return alias ?: f.name
        return if (effectiveShowExtension()) f.name else (f.nameWithoutExtension.ifEmpty { f.name })
    }
}

typealias VfNode = DefaultMutableTreeNode


package sm.model

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

object SmTreeBuilder {

    private const val MAX_LINK_DEPTH = 10
    private const val LINK_PREFIX = "link:"

    fun buildNode(folder: VirtualFile, showHiddenFolders: Boolean = false): VfNode {
        return buildNodeInner(folder, folder.path, showHiddenFolders, isRoot = true, ancestorPaths = emptySet(), linkDepth = 0)
    }

    private fun buildNodeInner(
        file: VirtualFile, rootPath: String, showHidden: Boolean,
        isRoot: Boolean = false, ancestorPaths: Set<String>, linkDepth: Int
    ): VfNode {
        val isHidden = file.isDirectory && file.name.startsWith(".") && !isRoot
        val alias = if (file.isDirectory) SmSettings.getAlias(file.path) else null
        val node = VfNode(NodeData(file, rootPath = rootPath, alias = alias, isHiddenFolder = isHidden))
        if (!file.isDirectory) return node

        val meta = SmMeta.read(file)
        val children = file.children.filter {
            val name = it.name
            when {
                name == SmMeta.META_FILE_NAME -> false
                name == ".joe" -> false              // hide legacy meta too
                name.endsWith(".sm") -> false
                name.endsWith(".joe") -> false        // hide legacy csv meta
                name.startsWith(".") -> showHidden && it.isDirectory
                else -> true
            }
        }

        val byName = children.associateBy { it.name }
        val ordered = mutableListOf<Any>()

        for (entry in meta.order) {
            if (entry.startsWith(LINK_PREFIX)) {
                val key = entry.removePrefix(LINK_PREFIX); val linkEntry = meta.links[key]
                if (linkEntry != null) ordered.add(Pair(key, linkEntry))
            } else { val vf = byName[entry]; if (vf != null) ordered.add(vf) }
        }

        val orderedNames = meta.order.filter { !it.startsWith(LINK_PREFIX) }.toSet()
        val remaining = children.filter { it.name !in orderedNames }.sortedBy { it.name.lowercase() }
        ordered.addAll(remaining)

        val orderedLinkKeys = meta.order.filter { it.startsWith(LINK_PREFIX) }.map { it.removePrefix(LINK_PREFIX) }.toSet()
        for ((key, entry) in meta.links) { if (key !in orderedLinkKeys) ordered.add(Pair(key, entry)) }

        val currentPath = file.canonicalPath ?: file.path
        val newAncestors = ancestorPaths + currentPath

        for (item in ordered) {
            when (item) {
                is VirtualFile -> node.add(buildNodeInner(item, rootPath, showHidden, ancestorPaths = newAncestors, linkDepth = linkDepth))
                is Pair<*, *> -> {
                    val key = item.first as String; val linkEntry = item.second as SmMeta.LinkEntry
                    node.add(buildLinkNode(key, linkEntry, file, rootPath, showHidden, newAncestors, linkDepth))
                }
            }
        }
        return node
    }

    private fun buildLinkNode(
        key: String, entry: SmMeta.LinkEntry, ownerFolder: VirtualFile,
        rootPath: String, showHidden: Boolean, ancestorPaths: Set<String>, linkDepth: Int
    ): VfNode {
        val resolvedVf = resolveTarget(entry.target, ownerFolder)
        if (resolvedVf == null) {
            return VfNode(NodeData(vf = null, rootPath = rootPath, linkKey = key, linkTarget = entry.target,
                linkType = entry.type, linkDisplayName = entry.displayName, isBrokenLink = true, linkOwnerFolder = ownerFolder))
        }
        val resolvedPath = resolvedVf.canonicalPath ?: resolvedVf.path
        if (resolvedPath in ancestorPaths || linkDepth >= MAX_LINK_DEPTH) {
            return VfNode(NodeData(vf = resolvedVf, rootPath = rootPath, linkKey = key, linkTarget = entry.target,
                linkType = entry.type, linkDisplayName = entry.displayName, isCircularLink = true, linkOwnerFolder = ownerFolder))
        }
        val alias = entry.displayName ?: key
        val nodeAlias = SmSettings.getAlias(resolvedVf.path) ?: alias
        if (entry.type == "folder" && resolvedVf.isDirectory) {
            val linkNode = VfNode(NodeData(vf = resolvedVf, rootPath = rootPath, alias = nodeAlias, linkKey = key,
                linkTarget = entry.target, linkType = "folder", linkDisplayName = entry.displayName, linkOwnerFolder = ownerFolder))
            val innerNode = buildNodeInner(resolvedVf, rootPath, showHidden, ancestorPaths = ancestorPaths + resolvedPath, linkDepth = linkDepth + 1)
            while (innerNode.childCount > 0) { val child = innerNode.getFirstChild() as VfNode; innerNode.remove(child); linkNode.add(child) }
            return linkNode
        } else {
            return VfNode(NodeData(vf = resolvedVf, rootPath = rootPath, linkKey = key, linkTarget = entry.target,
                linkType = "file", linkDisplayName = entry.displayName, linkOwnerFolder = ownerFolder))
        }
    }

    private fun resolveTarget(target: String, ownerFolder: VirtualFile): VirtualFile? {
        val expanded = if (target.startsWith("~/")) System.getProperty("user.home") + target.substring(1) else target
        val path = if (File(expanded).isAbsolute) expanded else File(ownerFolder.path, expanded).canonicalPath
        return LocalFileSystem.getInstance().findFileByPath(path)
    }

    /** Write the child ordering for a folder based on the current node order. Preserves link entries. */
    fun writeOrderForFolderNode(folderNode: VfNode) {
        val data = folderNode.userObject as? NodeData ?: return
        val folder = data.vf ?: return; if (!folder.isDirectory) return
        val existingMeta = SmMeta.read(folder)
        val order = mutableListOf<String>()
        for (i in 0 until folderNode.childCount) {
            val c = folderNode.getChildAt(i) as VfNode; val cd = c.userObject as? NodeData ?: continue
            if (cd.isLink && cd.linkKey != null) order.add("$LINK_PREFIX${cd.linkKey}")
            else if (cd.vf != null) order.add(cd.vf.name)
        }
        SmMeta.write(folder, SmMeta.Meta(order, existingMeta.links))
    }
}


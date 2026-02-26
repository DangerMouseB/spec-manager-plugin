package sm.model

import java.util.prefs.Preferences

/**
 * Persisted settings for SM (Spec Manager).
 *
 * Two levels:
 *   - Global (plugin-wide): default hidden extensions
 *   - Per-root: override hidden extensions for a specific doc root
 */
object SmSettings {

    private val prefs = Preferences.userNodeForPackage(SmSettings::class.java)
    private const val GLOBAL_HIDE_KEY = "sm.global.hideExtensions"
    private var _globalHiddenExts: MutableSet<String>? = null
    private val _rootHiddenExts = mutableMapOf<String, MutableSet<String>?>()
    private val FACTORY_HIDDEN = setOf("txt")

    fun globalHiddenExtensions(): MutableSet<String> {
        if (_globalHiddenExts == null) {
            val stored = prefs.get(GLOBAL_HIDE_KEY, null)
            _globalHiddenExts = if (stored != null) parseSet(stored) else FACTORY_HIDDEN.toMutableSet()
        }
        return _globalHiddenExts!!
    }

    fun setGlobalHiddenExtensions(exts: Set<String>) {
        _globalHiddenExts = exts.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.toMutableSet()
        prefs.put(GLOBAL_HIDE_KEY, _globalHiddenExts!!.joinToString(",")); prefs.flush()
    }

    private fun rootKey(rootPath: String) = "sm.root.hideExts.${rootPath.hashCode()}"

    fun rootHiddenExtensions(rootPath: String): Set<String>? {
        if (!_rootHiddenExts.containsKey(rootPath)) {
            val stored = prefs.get(rootKey(rootPath), null)
            _rootHiddenExts[rootPath] = if (stored != null && stored != "USE_GLOBAL") parseSet(stored) else null
        }
        return _rootHiddenExts[rootPath]
    }

    fun setRootHiddenExtensions(rootPath: String, exts: Set<String>?) {
        if (exts == null) { _rootHiddenExts[rootPath] = null; prefs.put(rootKey(rootPath), "USE_GLOBAL") }
        else {
            _rootHiddenExts[rootPath] = exts.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.toMutableSet()
            prefs.put(rootKey(rootPath), _rootHiddenExts[rootPath]!!.joinToString(","))
        }
        prefs.flush()
    }

    fun effectiveHiddenExtensions(rootPath: String): Set<String> = rootHiddenExtensions(rootPath) ?: globalHiddenExtensions()

    fun shouldShowExtension(ext: String, rootPath: String?): Boolean {
        val hidden = if (rootPath != null) effectiveHiddenExtensions(rootPath) else globalHiddenExtensions()
        return ext.lowercase() !in hidden
    }

    private fun aliasKey(folderPath: String) = "sm.alias.${folderPath.hashCode()}"

    fun getAlias(folderPath: String): String? {
        val stored = prefs.get(aliasKey(folderPath), null)
        return if (stored.isNullOrBlank()) null else stored
    }

    fun setAlias(folderPath: String, alias: String?) {
        if (alias.isNullOrBlank()) prefs.remove(aliasKey(folderPath)) else prefs.put(aliasKey(folderPath), alias)
        prefs.flush()
    }

    private const val SHOW_HIDDEN_KEY = "sm.showHiddenFolders"
    fun showHiddenFolders(): Boolean = prefs.getBoolean(SHOW_HIDDEN_KEY, false)
    fun setShowHiddenFolders(show: Boolean) { prefs.putBoolean(SHOW_HIDDEN_KEY, show); prefs.flush() }

    private fun expandedKey(projectHash: Int) = "sm.expanded.${projectHash}"
    fun getExpandedPaths(projectHash: Int): Set<String> {
        val stored = prefs.get(expandedKey(projectHash), null) ?: return emptySet()
        return stored.split("\n").filter { it.isNotBlank() }.toSet()
    }
    fun setExpandedPaths(projectHash: Int, paths: Set<String>) { prefs.put(expandedKey(projectHash), paths.joinToString("\n")); prefs.flush() }

    private fun parseSet(s: String): MutableSet<String> = s.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toMutableSet()

    fun formatExts(exts: Set<String>): String = exts.sorted().joinToString(", ") { "*.${it}" }

    // CSV editor: which file extensions open in the table editor
    private const val CSV_EXTS_KEY = "sm.csvExtensions"
    private val FACTORY_CSV_EXTS = setOf("csv", "csvai")
    private var _csvExts: MutableSet<String>? = null

    fun csvExtensions(): Set<String> {
        if (_csvExts == null) {
            val stored = prefs.get(CSV_EXTS_KEY, null)
            _csvExts = if (stored != null) parseSet(stored) else FACTORY_CSV_EXTS.toMutableSet()
        }
        return _csvExts!!
    }

    fun setCsvExtensions(exts: Set<String>) {
        _csvExts = exts.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.toMutableSet()
        prefs.put(CSV_EXTS_KEY, _csvExts!!.joinToString(",")); prefs.flush()
    }

    fun isCsvExtension(ext: String): Boolean = ext.lowercase() in csvExtensions()

    private fun zoomKey(filePath: String) = "sm.zoom.${filePath.hashCode()}"
    fun getZoom(filePath: String): Double = prefs.get(zoomKey(filePath), null)?.toDoubleOrNull() ?: 1.0
    fun setZoom(filePath: String, zoom: Double) {
        if (zoom == 1.0) prefs.remove(zoomKey(filePath)) else prefs.put(zoomKey(filePath), "%.4f".format(zoom))
        prefs.flush()
    }
}


package sm.model

import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets

/**
 * Per-folder metadata stored as `.sm` JSON.
 *
 * Stores explicit child ordering (visual order) and link definitions.
 * Falls back to reading `.joe` if `.sm` is not found (backward compatibility).
 */
object SmMeta {
    const val META_FILE_NAME = ".sm"
    private const val LEGACY_META_FILE_NAME = ".joe"

    data class LinkEntry(val target: String, val type: String, val displayName: String? = null)

    data class Meta(
        val order: MutableList<String> = mutableListOf(),
        val links: MutableMap<String, LinkEntry> = mutableMapOf()
    )

    fun read(folder: VirtualFile): Meta {
        val metaVf = folder.findChild(META_FILE_NAME)
            ?: folder.findChild(LEGACY_META_FILE_NAME)
            ?: return Meta()
        return try {
            val txt = String(metaVf.contentsToByteArray(), StandardCharsets.UTF_8)
            parse(txt)
        } catch (_: Throwable) { Meta() }
    }

    fun write(folder: VirtualFile, meta: Meta) {
        val json = toJson(meta)
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val metaVf = folder.findChild(META_FILE_NAME) ?: folder.createChildData(this, META_FILE_NAME)
        metaVf.setBinaryContent(bytes)
    }

    private fun parse(txt: String): Meta {
        val order = mutableListOf<String>()
        val links = mutableMapOf<String, LinkEntry>()
        val orderIdx = txt.indexOf("\"order\"")
        if (orderIdx >= 0) {
            val startArr = txt.indexOf('[', orderIdx)
            val endArr = if (startArr >= 0) findMatchingBracket(txt, startArr, '[', ']') else -1
            if (startArr >= 0 && endArr >= 0) {
                val arr = txt.substring(startArr + 1, endArr)
                arr.split(',').map { it.trim() }.forEach { token ->
                    val t = token.trim()
                    if (t.length >= 2 && t.first() == '"' && t.last() == '"') order.add(t.substring(1, t.length - 1))
                }
            }
        }
        val linksIdx = txt.indexOf("\"links\"")
        if (linksIdx >= 0) {
            val outerBrace = txt.indexOf('{', linksIdx + 7)
            if (outerBrace >= 0) {
                val outerEnd = findMatchingBracket(txt, outerBrace, '{', '}')
                if (outerEnd > outerBrace) parseLinkEntries(txt.substring(outerBrace + 1, outerEnd), links)
            }
        }
        return Meta(order, links)
    }

    private fun parseLinkEntries(block: String, out: MutableMap<String, LinkEntry>) {
        var pos = 0
        while (pos < block.length) {
            val keyStart = block.indexOf('"', pos); if (keyStart < 0) break
            val keyEnd = block.indexOf('"', keyStart + 1); if (keyEnd < 0) break
            val key = block.substring(keyStart + 1, keyEnd)
            val braceStart = block.indexOf('{', keyEnd); if (braceStart < 0) break
            val braceEnd = findMatchingBracket(block, braceStart, '{', '}'); if (braceEnd < 0) break
            val entryBlock = block.substring(braceStart + 1, braceEnd)
            val target = extractStringField(entryBlock, "target") ?: ""
            val type = extractStringField(entryBlock, "type") ?: "file"
            val displayName = extractStringField(entryBlock, "displayName")
            if (target.isNotEmpty()) out[key] = LinkEntry(target, type, displayName)
            pos = braceEnd + 1
        }
    }

    private fun extractStringField(block: String, field: String): String? {
        val keyPattern = "\"$field\""; val idx = block.indexOf(keyPattern); if (idx < 0) return null
        val colonIdx = block.indexOf(':', idx + keyPattern.length); if (colonIdx < 0) return null
        val valStart = block.indexOf('"', colonIdx + 1); if (valStart < 0) return null
        val valEnd = block.indexOf('"', valStart + 1); if (valEnd < 0) return null
        return block.substring(valStart + 1, valEnd).replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun findMatchingBracket(s: String, openPos: Int, open: Char, close: Char): Int {
        var depth = 0; var inString = false; var i = openPos
        while (i < s.length) {
            val c = s[i]
            if (c == '"' && (i == 0 || s[i - 1] != '\\')) inString = !inString
            if (!inString) { if (c == open) depth++; if (c == close) { depth--; if (depth == 0) return i } }
            i++
        }
        return -1
    }

    private fun toJson(meta: Meta): String {
        val sb = StringBuilder(); sb.append("{\n")
        val items = meta.order.joinToString(",") { "\"${escape(it)}\"" }
        sb.append("  \"order\": [$items]")
        if (meta.links.isNotEmpty()) {
            sb.append(",\n  \"links\": {\n")
            val entries = meta.links.entries.toList()
            entries.forEachIndexed { idx, (key, entry) ->
                sb.append("    \"${escape(key)}\": {\"target\":\"${escape(entry.target)}\",\"type\":\"${escape(entry.type)}\"")
                if (entry.displayName != null) sb.append(",\"displayName\":\"${escape(entry.displayName)}\"")
                sb.append("}"); if (idx < entries.size - 1) sb.append(","); sb.append("\n")
            }
            sb.append("  }")
        }
        sb.append("\n}\n"); return sb.toString()
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}


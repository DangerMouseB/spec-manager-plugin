# Spec Manager (SM) — JetBrains Plugin

A JetBrains IDE plugin for organising specs, plans, notes, and test scenarios alongside your code.

## Why SM?

IDEs are optimised for code — but projects also contain specs, plans, notes, test scenarios, and design journals
that are meant to be **read by humans**. These get buried in the project tree alongside build files, configs, and
source directories.

SM declutters the IDE experience by giving human-readable content its own **curated outline** — a separate,
drag-orderable tree of documents with WYSIWYG rich text, table editing, and folder aliases. You choose what appears,
in what order, with what names. The result is a focused view for thinking, writing, and organising — right next to
your code, in the same tool where Copilot and other AI assistants can read and edit the files directly.

No context switching. No separate app. Just the content that matters, presented the way you want it.

## What's implemented (prototype)

- **SM Tool Window** (left sidebar) with document icon
- **SM top-level menu** in the main menu bar (Add Root, Remove Root, Refresh, Settings, **About SM**)
- Multiple document roots, persisted across IDE restarts
- Right-click context menu (Add Root, Remove Root, Show/Hide Extension, Folder Alias, Refresh)
- **Folder management** — right-click a folder → New Folder…, Rename Folder…, Delete Folder (confirms if not empty)
- Visual ordering stored in per-folder **`.sm`** metadata — files and folders freely intermixed
- `.sm` metadata files (`.sm`, `*.csv.sm`) are **hidden from the tree**
- Drag/drop reorder within a folder or move between folders (inserts at position, moves file on disk, writes `.sm`)
- **Up/down arrow keys** in the tree navigate and open the selected file
- File extensions **shown by default** — only `*.txt` hidden (configurable in **SM → Settings**)
- **Settings dialog** (SM menu or right-click): global hidden extensions + per-root overrides
- Right-click a file → Show/Hide Extension (per-file override)
- **Folder aliases** — right-click a folder → Folder Alias… to set a display name
- **Click file → opens in IDE's main editor area** (syntax highlighting, autosave, AI)
- **WYSIWYG rich text editor** for `.rtf` files — Cochin font, margins, font/size/bold/italic toolbar
- **Table editor** for `.csv` files — RFC 4180 compliant (multiline cells), monospace font, cell-level focus
  (no row highlighting), right-click menu on cells and headers, **Cmd+C/V/X** clipboard
- **Hidden folders** (`.templates`, `.style` etc.) — toggle with **⌘⇧.** (macOS convention) or right-click →
  Show/Hide Hidden Folders. Hidden folders render in *light italic* when shown. Root folders with `.` prefix are
  always shown regardless of toggle.
- **Expanded/collapsed state persisted** across IDE restarts
- **Drag-drop order saved reliably** via debounced VFS write
- **Links** — right-click a folder → **Add Link…** to link to external files or folders. Linked folders
  appear as expandable subtrees. Cross-platform (no OS symlinks needed — works on Windows too). Edit or
  remove links via right-click. Broken links shown with red X icon. Circular link detection (max depth 10).

## Run locally

### Prerequisites

- **Gradle** plugin installed in your IDE: **Settings → Plugins → search "Gradle" → Enable**
- CLion and/or PyCharm installed at `/Applications/CLion.app` or `/Applications/PyCharm.app`

### From terminal (launches IDE sandbox with plugin)

```bash
cd ~/arwen/spec-manager-plugin
./run.sh              # launches CLion (default)
./run.sh clion        # launches CLion
./run.sh pycharm      # launches PyCharm
```

> **Note:** The built-in `./gradlew runIde` is disabled — it crashes on JetBrains 2025.3 IDEs due to a
> known `gradle-intellij-plugin` 1.x bug ("Index: 1, Size: 1"). The `run.sh` script builds the plugin zip,
> sets up a sandbox, and launches the IDE directly.

### Install into your real PyCharm / CLion (2025.3)

Pre-built zips are in the `releases/` folder. Pick the latest one, then:

1. **Settings → Plugins → ⚙️ → Install Plugin from Disk…**
2. Select the `.zip` from `releases/` (e.g. `spec-manager-2026.02.23.1.zip`)
3. Restart IDE

To build from source instead: `./gradlew clean buildPlugin -x buildSearchableOptions`
→ zip appears in `build/distributions/`

## How to use SM

1. Open the **SM** tool window (document icon in left sidebar)
2. Use the **SM** menu in the menu bar → **Add Root…** (or right-click in the tree)
3. Choose a document root folder — repeat for multiple roots
4. **Click or arrow-key** to files to open them in the main editor
5. `.rtf` → WYSIWYG rich text editor with formatting toolbar
6. `.csv` → table editor (right-click cells or headers, Cmd+C/V/X for clipboard)
7. Right-click a file → **Show Extension** / **Hide Extension** (per-file override)
8. Right-click a folder → **Folder Alias…** to set a display name
9. **SM → Settings…** to configure which extensions are hidden globally or per-root
10. Drag items in the tree to reorder or move between folders (persisted to `.sm`, files moved on disk)
11. **⌘⇧.** to toggle hidden folders (`.templates`, `.style`, etc.)
12. Right-click a folder → **Add Link…** to link to an external file or folder (relative paths auto-computed)
13. Right-click a link → **Edit Link…** or **Remove Link** (removing a link does NOT delete the target)
14. Expanded/collapsed tree state and roots are remembered across IDE restarts

### Current limitations

- RTF: Swing RTFEditorKit (basic formatting, no images)
- CSV multiline cells: double-click to edit, Enter inserts newline, Tab or click away to commit

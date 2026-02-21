# Copilot Instructions for Spec Manager (SM)

## Project Structure

- **Repo:** https://github.com/DangerMouseB/Joe.git (will be renamed to SpecManager)
- **Local clone:** `~/arwen/spec-manager-plugin/`
- **Specs & docs:** `~/arwen/coppertop-bones/.specs/projects/spec-manager`  

### Key directories
```
spec-manager-plugin/        ← repo root (Gradle project)
  .github/                  ← this file
  src/main/kotlin/sm/
    actions/                ← menu actions
    editor/                 ← CSV and RTF file editors
    model/                  ← SmMeta, SmSettings, SmTreeBuilder, TreeNodes, TransferHandler
    toolwindow/             ← SmToolWindowPanel, SmToolWindowFactory, dialogs
    viewer/                 ← RichTextPanel, TextPanel
  src/main/resources/
    META-INF/plugin.xml
    icons/                  ← SVG icons (sm_13, link_overlay, link_broken)
  build.gradle.kts
  settings.gradle.kts
  run.sh                    ← launches CLion/PyCharm sandbox (bypasses broken runIde)
  README.md
```

## Build & Run

```bash
cd ~/arwen/spec-manager-plugin
./gradlew build              # produces build/distributions/spec-manager-*.zip
./run.sh                     # launches CLion sandbox (default)
./run.sh clion               # launches CLion sandbox
./run.sh pycharm             # launches PyCharm sandbox
```

### Install into real CLion / PyCharm (2025.3)
```bash
./gradlew clean build
# Settings → Plugins → ⚙️ → Install Plugin from Disk… → select build/distributions/*.zip
```

### Why not `./gradlew runIde`?
The built-in `runIde` task crashes on JetBrains 2025.3 IDEs with "Index: 1, Size: 1"
(`gradle-intellij-plugin` 1.x can't parse the new `product-info.json` layout). The `run.sh`
script builds the plugin, sets up a sandbox, and launches the IDE directly via `open`.

## Naming Convention

- **Project name**: Spec Manager
- **UI abbreviation**: SM (used in menu titles, tool window ID, action IDs)
- **Plugin ID**: `sm.spec.manager`
- **Meta file extension**: `.sm`
- **Kotlin package**: `sm.*`
- **Class prefix**: `Sm` (e.g. `SmToolWindowPanel`, `SmMeta`, `SmSettings`)

## Kotlin Style

- **Line width**: 120 chars soft limit — don't wrap prose/comments before 120
- **Compactness**: vertical space is premium. Combine simple statements on one line where readable.
  Use `if (x) return` on one line. Chain `val`/`var` with `;` when trivially related.
- **Naming**: camelCase for functions/variables, PascalCase for classes, UPPER_SNAKE_CASE for constants
- **README wrapping**: hard-wrap at exactly 120 — fill lines up to 120, never wrap earlier
- **Version bump**: bump `version` in `build.gradle.kts` on every user-facing build
  Format: `YYYY.MM.DD.N` where N is the build count for that date (e.g. `2026.02.22.1`)
- **No unnecessary blank lines** between closely related declarations
- **Always `./gradlew compileKotlin`** after edits to verify — check for `^e:` errors
- **Always `runWriteAction`** when modifying VFS (creating/renaming/deleting files or folders)
- Prefer IntelliJ platform APIs (`VirtualFile`, `OpenFileDescriptor`, etc.) over raw `java.io.File`

## Common Gotchas

1. **Heredocs in terminal**: Do NOT use `cat << 'EOF'` or similar heredoc syntax to create files — it can
   get stuck waiting for input. Use the `insert_edit_into_file` tool instead.
2. **No `__init__.py`**: This is Kotlin/Gradle, not Python.
3. **runIde is disabled**: Use `./run.sh` instead — see above.
4. **VFS writes**: Always wrap `VirtualFile.rename()`, `.delete()`, `.createChildDirectory()` etc.
   in `ApplicationManager.getApplication().runWriteAction { ... }`.
5. **Tree model**: `isLeaf()` is overridden so directories are never leaves (even when empty).
   This is critical for correct folder icons and drag-drop.
6. **`.sm` ordering**: The `.sm` file in each folder stores child ordering as a JSON array of names.
   When renaming files/folders, update the `.sm` order entry too.
7. **Symlinks**: The workspace may have symlinks — use `os` commands not `git mv` for file operations.
   Terminal `rm -rf` via symlinked paths may silently fail — use Python `shutil.rmtree(os.path.realpath(p))`
   or background terminal processes to verify deletion.

package sm.editor

import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Registers SM's WYSIWYG editor for rich text files (.rtf).
 * Opens in the main editor tab area — same place as .c, .h, .py etc.
 */
class SmRichTextEditorProvider : FileEditorProvider, DumbAware {

    override fun getEditorTypeId(): String = "sm-rich-text-editor"

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file.extension?.lowercase() == "rtf"
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return SmRichTextFileEditor(project, file)
    }

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}


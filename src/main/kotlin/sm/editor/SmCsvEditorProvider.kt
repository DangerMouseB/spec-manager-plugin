package sm.editor

import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Registers SM's table editor for .csv files.
 * Opens as a WYSIWYG table in the main editor tab area.
 */
class SmCsvEditorProvider : FileEditorProvider, DumbAware {

    override fun getEditorTypeId(): String = "sm-csv-table-editor"

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file.extension?.lowercase() == "csv"
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return SmCsvFileEditor(project, file)
    }

    // PLACE_BEFORE_DEFAULT_EDITOR gives a "Table" tab AND keeps the normal text tab
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}


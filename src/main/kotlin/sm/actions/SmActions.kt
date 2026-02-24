package sm.actions

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import sm.toolwindow.SmToolWindowPanel
import javax.swing.JOptionPane

class SmAddRootAction : AnAction("Add Root…"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) { findPanel(e)?.addRoot() }
}

class SmRemoveRootAction : AnAction("Remove Selected Root"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) { findPanel(e)?.removeSelectedRoot() }
}

class SmRefreshAction : AnAction("Refresh All"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) { findPanel(e)?.refreshAll() }
}

class SmSettingsAction : AnAction("Settings…"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) { findPanel(e)?.openSettings() }
}

class SmCloseRootFilesAction : AnAction("Close All Files Under Root"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) { findPanel(e)?.closeFilesUnderSelectedRoot() }
}

class SmAboutAction : AnAction("About SM"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val version = PluginManagerCore.getPlugin(PluginId.getId("sm.spec.manager"))?.version ?: "dev"
        JOptionPane.showMessageDialog(
            null,
            "Spec Manager\n\nVersion: $version\n\nA JetBrains IDE plugin for organising\ndocuments, notes and test specs.",
            "About SM",
            JOptionPane.INFORMATION_MESSAGE
        )
    }
}

private fun findPanel(e: AnActionEvent): SmToolWindowPanel? {
    val project = e.project ?: return null
    val tw = ToolWindowManager.getInstance(project).getToolWindow("SM") ?: return null
    tw.show()
    val content = tw.contentManager.selectedContent ?: return null
    return content.component as? SmToolWindowPanel
}

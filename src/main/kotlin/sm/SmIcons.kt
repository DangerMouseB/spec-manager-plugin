package sm

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object SmIcons {
    @JvmField val TOOL_WINDOW: Icon = IconLoader.getIcon("/icons/sm_13.svg", SmIcons::class.java)
    @JvmField val LINK_OVERLAY: Icon = IconLoader.getIcon("/icons/link_overlay.svg", SmIcons::class.java)
    @JvmField val LINK_BROKEN: Icon = IconLoader.getIcon("/icons/link_broken.svg", SmIcons::class.java)
}

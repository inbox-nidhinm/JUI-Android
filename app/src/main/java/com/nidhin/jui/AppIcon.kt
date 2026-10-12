package com.nidhin.jui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

// The launcher icon follows the mood, as the Mac's Dock icon and the iPhone's
// home-screen icon do: six icons from the Figma system (node 331:539), each in
// its theme's own sticky and open-dot colours. Android has no alternate icons;
// each mood is an activity alias with its own icon, and exactly one is enabled.
//
// Switching an alias makes the launcher redraw its icons, so — as on the
// iPhone, where each switch shows an alert — the icon is not switched on every
// tap of a mood dot. It is brought into step once, when settings or onboarding
// is left, and whenever the app comes back to the foreground, which catches a
// change made just before it was closed.
object AppIcon {
    private fun alias(context: Context, theme: Theme) =
        ComponentName(context.packageName, "com.nidhin.jui.Icon_${theme.id}")

    fun sync(context: Context, theme: Theme = Style.theme) {
        val pm = context.packageManager
        val wanted = alias(context, theme)
        if (pm.getComponentEnabledSetting(wanted) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return
        // Cream is enabled in the manifest; "default" means whatever that says.
        val alreadyDefault = theme == Themes.cream &&
            pm.getComponentEnabledSetting(wanted) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT &&
            Themes.all.filter { it != Themes.cream }.none {
                pm.getComponentEnabledSetting(alias(context, it)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
        if (alreadyDefault) return
        // The new one on first, so there is never a moment with no launcher entry.
        pm.setComponentEnabledSetting(wanted, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        for (other in Themes.all) {
            if (other == theme) continue
            pm.setComponentEnabledSetting(alias(context, other),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}

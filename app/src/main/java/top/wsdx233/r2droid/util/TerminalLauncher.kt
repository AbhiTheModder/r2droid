package top.wsdx233.r2droid.util

import android.content.Context
import android.content.Intent
import top.wsdx233.r2droid.activity.TerminalActivity
import top.wsdx233.r2droid.core.data.prefs.SettingsManager

object TerminalLauncher {
    const val EXTRA_TERMINAL_MODE = "terminal_mode"
    const val EXTRA_STARTUP_COMMAND = "startup_command"

    fun buildIntent(
        context: Context,
        mode: String? = null,
        startupCommand: String? = null
    ): Intent {
        return Intent(context, TerminalActivity::class.java).apply {
            mode?.let { putExtra(EXTRA_TERMINAL_MODE, SettingsManager.sanitizeTerminalLaunchMode(it)) }
            if (!startupCommand.isNullOrBlank()) {
                putExtra(EXTRA_STARTUP_COMMAND, startupCommand)
            }
        }
    }

    fun start(
        context: Context,
        mode: String? = null,
        startupCommand: String? = null
    ) {
        context.startActivity(buildIntent(context, mode, startupCommand))
    }
}

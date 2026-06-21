package com.gst.latestpack

import com.formdev.flatlaf.FlatLightLaf
import kotlinx.coroutines.*
import javax.swing.SwingUtilities

fun main() {
    val config = AppConfig.load()

    FlatLightLaf.setup()

    SwingUtilities.invokeLater {
        val dialog = DownloadDialog(config)
        dialog.isVisible = true

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val service = UpdateService(config)
            service.runUpdate(dialog)
        }
    }
}

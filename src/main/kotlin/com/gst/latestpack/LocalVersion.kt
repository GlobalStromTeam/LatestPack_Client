package com.gst.latestpack

import java.io.File

object LocalVersion {
    private const val VERSION_FILE = "version.txt"

    fun read(targetDir: String): String? {
        val file = File(targetDir, VERSION_FILE)
        return if (file.exists()) file.readText().trim().takeIf { it.isNotEmpty() } else null
    }

    fun write(targetDir: String, version: String) {
        val file = File(targetDir, VERSION_FILE)
        file.writeText(version)
    }
}

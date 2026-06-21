package com.gst.latestpack

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File

data class AppConfig(
    val title: String = "LatestPack_Client",
    val serverUrl: String = "http://localhost:8080",
    val targetDir: String = ".",
    val httpTimeout: Int = 30,
    val retries: Int = 3
) {
    companion object {
        private val mapper = ObjectMapper(YAMLFactory())

        fun load(): AppConfig {
            val file = File("config.yaml")
            return if (file.exists()) {
                mapper.readValue(file, AppConfig::class.java)
            } else {
                val config = AppConfig()
                mapper.writeValue(file, config)
                config
            }
        }
    }
}

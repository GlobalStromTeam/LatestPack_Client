package com.gst.latestpack

import java.awt.*
import javax.swing.*

class DownloadDialog(private val config: AppConfig) : JDialog() {

    private val progressBar = JProgressBar(0, 100)
    private val fileNameLabel = JLabel(" ")
    private val versionLabel = JLabel(" ")
    private val statusLabel = JLabel("0 B / 0 B")
    private val speedLabel = JLabel("0 B/s")
    private val updateStatusLabel = JLabel(" ")

    init {
        setupWindow()
        setupContent()
        finalizeLayout()
    }

    private fun setupWindow() {
        title = config.title
        isUndecorated = false
        isModal = false
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isResizable = false
    }

    private fun setupContent() {
        val panel = JPanel().apply {
            layout = BorderLayout(0, 12)
            border = BorderFactory.createEmptyBorder(20, 30, 20, 30)
            background = UIManager.getColor("Panel.background")
        }

        val titleLabel = JLabel(config.title).apply {
            horizontalAlignment = SwingConstants.CENTER
            font = font.deriveFont(Font.BOLD, 18f)
        }

        fileNameLabel.apply {
            horizontalAlignment = SwingConstants.CENTER
            font = font.deriveFont(Font.PLAIN, 13f)
        }

        versionLabel.apply {
            horizontalAlignment = SwingConstants.CENTER
            font = font.deriveFont(Font.PLAIN, 13f)
        }

        progressBar.apply {
            value = 0
            isStringPainted = false
            preferredSize = Dimension(360, 24)
        }

        val statusPanel = JPanel(GridLayout(1, 2)).apply {
            background = UIManager.getColor("Panel.background")
        }
        statusLabel.apply { horizontalAlignment = SwingConstants.LEFT }
        speedLabel.apply { horizontalAlignment = SwingConstants.RIGHT }
        statusPanel.add(statusLabel)
        statusPanel.add(speedLabel)

        updateStatusLabel.apply {
            horizontalAlignment = SwingConstants.CENTER
            font = font.deriveFont(Font.PLAIN, 12f)
        }

        val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIManager.getColor("Panel.background")
        }
        centerPanel.add(fileNameLabel)
        centerPanel.add(Box.createVerticalStrut(4))
        centerPanel.add(versionLabel)
        centerPanel.add(Box.createVerticalStrut(8))
        centerPanel.add(progressBar)
        centerPanel.add(Box.createVerticalStrut(4))
        centerPanel.add(statusPanel)
        centerPanel.add(Box.createVerticalStrut(4))
        centerPanel.add(updateStatusLabel)

        panel.add(titleLabel, BorderLayout.NORTH)
        panel.add(centerPanel, BorderLayout.CENTER)

        contentPane = panel
    }

    private fun finalizeLayout() {
        pack()
        setLocationRelativeTo(null)
    }

    fun setFileName(name: String) {
        SwingUtilities.invokeLater { fileNameLabel.text = name }
    }

    fun setVersion(version: String) {
        SwingUtilities.invokeLater { versionLabel.text = version }
    }

    fun setProgress(downloaded: Long, total: Long, speedBytesPerSec: Long) {
        SwingUtilities.invokeLater {
            val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
            progressBar.value = percent.coerceIn(0, 100)
            statusLabel.text = "${formatSize(downloaded)} / ${formatSize(total)}"
            speedLabel.text = "${formatSize(speedBytesPerSec)}/s"
        }
    }

    fun setStatus(text: String) {
        SwingUtilities.invokeLater { updateStatusLabel.text = text }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}

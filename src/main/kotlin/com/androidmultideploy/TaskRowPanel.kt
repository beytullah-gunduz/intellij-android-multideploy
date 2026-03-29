package com.androidmultideploy

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.border.CompoundBorder

class TaskRowPanel(
    initialDeviceResult: DeviceResult,
    modules: List<String>,
    private val onRemove: (TaskRowPanel) -> Unit,
    private val onRetryDeviceCheck: () -> Unit,
    private val onRun: (TaskRowPanel) -> Unit,
    private val onStop: (TaskRowPanel) -> Unit
) : JPanel(BorderLayout()) {

    private enum class RunState { IDLE, RUNNING, ERROR }

    private var runState = RunState.IDLE
    private var isDeviceAvailable = true

    // Maps between serial numbers and display names (e.g. "Pixel 7 (emulator-5554)")
    private var serialToDisplay = mutableMapOf<String, String>()
    private var displayToSerial = mutableMapOf<String, String>()

    private val progressBar = JProgressBar().apply {
        isIndeterminate = true
        preferredSize = Dimension(0, 3)
        maximumSize = Dimension(Int.MAX_VALUE, 3)
        border = BorderFactory.createEmptyBorder()
        isVisible = false
    }

    private val contentPanel = JPanel(GridBagLayout()).apply {
        isOpaque = false
    }

    private val deviceCombo = object : JComboBox<String>() {
        override fun getPreferredSize() = Dimension(0, super.getPreferredSize().height)
        override fun getMinimumSize() = Dimension(0, super.getMinimumSize().height)
    }

    private val deviceErrorLabel = object : JBLabel() {
        override fun getPreferredSize() = Dimension(0, super.getPreferredSize().height)
        override fun getMinimumSize() = Dimension(0, 0)
    }.apply {
        foreground = JBColor.RED
        font = font.deriveFont(font.size2D - 1f)
        isVisible = false
    }

    private val deviceWarningLabel = object : JBLabel() {
        override fun getPreferredSize() = Dimension(0, super.getPreferredSize().height)
        override fun getMinimumSize() = Dimension(0, 0)
    }.apply {
        icon = AllIcons.General.Error
        foreground = JBColor.RED
        font = font.deriveFont(font.size2D - 1f)
        isVisible = false
    }

    private val moduleCombo = object : JComboBox<String>(modules.toTypedArray()) {
        override fun getPreferredSize() = Dimension(0, super.getPreferredSize().height)
        override fun getMinimumSize() = Dimension(0, super.getMinimumSize().height)
    }.apply {
        addActionListener { updateGradleTask() }
    }

    private val gradleTaskField = object : JBTextField() {
        override fun getPreferredSize() = Dimension(0, super.getPreferredSize().height)
        override fun getMinimumSize() = Dimension(0, super.getMinimumSize().height)
    }.apply {
        toolTipText = "Gradle task (e.g. :mobile:installDebug)"
    }

    private val playBtn = JButton(AllIcons.Actions.Execute).apply {
        toolTipText = "Run this task"
        isBorderPainted = false
        isContentAreaFilled = false
        preferredSize = Dimension(24, 24)
        addActionListener { onRun(this@TaskRowPanel) }
    }

    private val removeBtn = JButton(AllIcons.Actions.Close).apply {
        toolTipText = "Remove this task"
        isBorderPainted = false
        isContentAreaFilled = false
        preferredSize = Dimension(24, 24)
        addActionListener { onRemove(this@TaskRowPanel) }
    }

    private val stopBtn = JButton(AllIcons.Actions.Suspend).apply {
        toolTipText = "Stop this task"
        isBorderPainted = false
        isContentAreaFilled = false
        preferredSize = Dimension(24, 24)
        addActionListener { onStop(this@TaskRowPanel) }
    }

    private val retryBtn = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Retry device check"
        isBorderPainted = false
        isContentAreaFilled = false
        preferredSize = Dimension(24, 24)
        addActionListener { onRetryDeviceCheck() }
    }

    private val actionsPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(playBtn)
        add(removeBtn)
    }

    private val statusCardLayout = CardLayout()
    private val statusPanel = JPanel(statusCardLayout).apply {
        isOpaque = false
        add(actionsPanel, CARD_ACTIONS)
        add(stopBtn, CARD_STOP)
        add(retryBtn, CARD_RETRY)
    }

    private val normalBorder = CompoundBorder(
        BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
        BorderFactory.createEmptyBorder(8, 8, 8, 8)
    )

    private val errorBorder = CompoundBorder(
        BorderFactory.createLineBorder(JBColor.RED, 2),
        BorderFactory.createEmptyBorder(6, 6, 6, 6)
    )

    init {
        border = normalBorder
        add(progressBar, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)

        val gbc = GridBagConstraints().apply {
            insets = Insets(2, 4, 2, 4)
            anchor = GridBagConstraints.WEST
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        contentPanel.add(JBLabel("Device:"), gbc)

        val devicePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(deviceCombo)
            add(deviceErrorLabel)
            add(deviceWarningLabel)
        }
        gbc.gridx = 1; gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        contentPanel.add(devicePanel, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        contentPanel.add(JBLabel("Module:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        contentPanel.add(moduleCombo, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        contentPanel.add(JBLabel("Task:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        contentPanel.add(gradleTaskField, gbc)

        gbc.gridx = 2; gbc.gridy = 0; gbc.gridheight = 3
        gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.NORTH
        contentPanel.add(statusPanel, gbc)

        applyDeviceResult(initialDeviceResult)
        updateGradleTask()
    }

    // --- State management ---

    fun setRunning() {
        runState = RunState.RUNNING
        updateVisuals()
    }

    fun setError() {
        runState = RunState.ERROR
        updateVisuals()
    }

    fun setIdle() {
        runState = RunState.IDLE
        updateVisuals()
    }

    fun setDeviceUnavailable(message: String) {
        isDeviceAvailable = false
        deviceWarningLabel.text = message
        deviceWarningLabel.toolTipText = message
        deviceWarningLabel.isVisible = true
        updateVisuals()
    }

    fun setDeviceAvailable() {
        if (isDeviceAvailable) return
        isDeviceAvailable = true
        deviceWarningLabel.isVisible = false
        updateVisuals()
    }

    private fun updateVisuals() {
        when (runState) {
            RunState.RUNNING -> {
                setInputsEnabled(false)
                progressBar.isVisible = true
                statusCardLayout.show(statusPanel, CARD_STOP)
                border = normalBorder
            }
            RunState.ERROR -> {
                setInputsEnabled(true)
                progressBar.isVisible = false
                statusCardLayout.show(statusPanel, CARD_ACTIONS)
                border = errorBorder
            }
            RunState.IDLE -> {
                setInputsEnabled(true)
                progressBar.isVisible = false
                if (!isDeviceAvailable) {
                    statusCardLayout.show(statusPanel, CARD_RETRY)
                    border = errorBorder
                } else {
                    statusCardLayout.show(statusPanel, CARD_ACTIONS)
                    border = normalBorder
                }
            }
        }
        revalidate()
        repaint()
    }

    private fun setInputsEnabled(enabled: Boolean) {
        deviceCombo.isEnabled = enabled
        moduleCombo.isEnabled = enabled
        gradleTaskField.isEnabled = enabled
        playBtn.isEnabled = enabled
        removeBtn.isEnabled = enabled
    }

    // --- Device display name mapping ---

    private fun buildDisplayName(serial: String, model: String?): String {
        return if (model != null) "$model ($serial)" else serial
    }

    private fun getSelectedSerial(): String? {
        val display = deviceCombo.selectedItem?.toString() ?: return null
        return displayToSerial[display] ?: display
    }

    private fun applyDeviceResult(result: DeviceResult) {
        deviceCombo.removeAllItems()
        serialToDisplay.clear()
        displayToSerial.clear()
        when (result) {
            is DeviceResult.Success -> {
                result.devices.forEach { serial ->
                    val display = buildDisplayName(serial, result.deviceNames[serial])
                    serialToDisplay[serial] = display
                    displayToSerial[display] = serial
                    deviceCombo.addItem(display)
                }
                deviceCombo.putClientProperty("JComponent.outline", null)
                deviceErrorLabel.isVisible = false
            }
            is DeviceResult.Error -> {
                deviceCombo.putClientProperty("JComponent.outline", "error")
                deviceErrorLabel.text = result.message
                deviceErrorLabel.toolTipText = result.message
                deviceErrorLabel.isVisible = true
            }
        }
        revalidate()
        repaint()
    }

    private fun updateGradleTask() {
        val module = moduleCombo.selectedItem?.toString() ?: return
        gradleTaskField.text = ":${module}:installDebug"
    }

    fun updateDevices(result: DeviceResult) {
        val currentSerial = getSelectedSerial()
        applyDeviceResult(result)
        if (!currentSerial.isNullOrEmpty()) {
            val display = serialToDisplay[currentSerial] ?: currentSerial
            ensureDeviceInCombo(display)
            deviceCombo.selectedItem = display
        }
    }

    fun getDeployTask(): DeployTask {
        val display = deviceCombo.selectedItem?.toString() ?: ""
        val serial = displayToSerial[display] ?: display
        return DeployTask(
            device = serial,
            module = moduleCombo.selectedItem?.toString() ?: "",
            gradleTask = gradleTaskField.text,
            deviceDisplayName = display
        )
    }

    fun selectDevice(serial: String) {
        val display = serialToDisplay[serial] ?: serial
        ensureDeviceInCombo(display)
        deviceCombo.selectedItem = display
    }

    fun restoreFrom(task: DeployTask) {
        if (task.device.isNotEmpty()) {
            val display = serialToDisplay[task.device] ?: task.device
            ensureDeviceInCombo(display)
            deviceCombo.selectedItem = display
        }
        for (i in 0 until moduleCombo.itemCount) {
            if (moduleCombo.getItemAt(i) == task.module) {
                moduleCombo.selectedIndex = i
                break
            }
        }
        gradleTaskField.text = task.gradleTask
    }

    private fun ensureDeviceInCombo(display: String) {
        for (i in 0 until deviceCombo.itemCount) {
            if (deviceCombo.getItemAt(i) == display) return
        }
        deviceCombo.addItem(display)
    }

    companion object {
        private const val CARD_ACTIONS = "actions"
        private const val CARD_STOP = "stop"
        private const val CARD_RETRY = "retry"
    }
}

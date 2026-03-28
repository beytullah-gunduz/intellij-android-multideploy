package com.androidmultideploy

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ItemEvent
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JPanel
import javax.swing.Timer

class MultiDeployPanel(
    private val project: Project,
    private val toolWindow: ToolWindow
) : JPanel(BorderLayout()) {

    private val tasksPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val taskRows = mutableListOf<TaskRowPanel>()
    private var deviceResult: DeviceResult = DeviceResult.Error("Not initialized")
    private val runner = DeployRunner(project)
    private val actionToolbar: ActionToolbar
    private val healthCheckTimer: Timer
    private val configState = MultiDeployConfigState.getInstance(project)
    private val configComboModel = DefaultComboBoxModel<String>()
    private val configCombo = ComboBox(configComboModel).apply {
        isEditable = false
        toolTipText = "Select configuration"
        minimumSize = Dimension(0, preferredSize.height)
    }
    private var isUpdatingCombo = false

    init {
        refreshDevices()

        val actionGroup = DefaultActionGroup().apply {
            add(RunAllAction())
            add(StopAllAction())
            addSeparator()
            add(AddTaskAction())
            add(RefreshDevicesAction())
            addSeparator()
            add(SaveConfigAction())
            add(SaveAsConfigAction())
            add(DeleteConfigAction())
        }

        actionToolbar = ActionManager.getInstance()
            .createActionToolbar("AndroidMultiDeployToolbar", actionGroup, true)
        actionToolbar.targetComponent = this

        val topBar = JPanel(BorderLayout()).apply {
            add(configCombo, BorderLayout.WEST)
            add(actionToolbar.component, BorderLayout.CENTER)
        }
        add(topBar, BorderLayout.NORTH)

        val wrapper = JPanel(BorderLayout())
        wrapper.add(tasksPanel, BorderLayout.NORTH)
        add(JBScrollPane(wrapper), BorderLayout.CENTER)

        // Load saved configurations
        refreshConfigCombo()
        restoreActiveConfig()

        // Initial device health check
        updateDeviceHealth(deviceResult)

        // Periodic device health check every 10s
        healthCheckTimer = Timer(10_000) {
            if (!runner.isRunning() && taskRows.isNotEmpty()) checkDeviceHealth()
        }
        healthCheckTimer.start()

        configCombo.addItemListener { e ->
            if (!isUpdatingCombo && e.stateChange == ItemEvent.SELECTED) {
                saveCurrentTasksToActiveConfig()
                configState.setActiveConfigName(e.item as String)
                restoreActiveConfig()
            }
        }
    }

    // --- Individual task run/stop ---

    private fun runSingleTask(row: TaskRowPanel) {
        saveCurrentTasksToActiveConfig()
        val task = row.getDeployTask()
        row.setRunning()
        runner.runTask(task, row, toolWindow) { isError ->
            if (isError) row.setError() else row.setIdle()
        }
    }

    private fun stopSingleTask(row: TaskRowPanel) {
        runner.stopTask(row)
        row.setIdle()
    }

    // --- Device health ---

    private fun checkDeviceHealth() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = AdbHelper.getDevices()
            ApplicationManager.getApplication().invokeLater {
                deviceResult = result
                updateDeviceHealth(result)
            }
        }
    }

    private fun updateDeviceHealth(result: DeviceResult) {
        val connectedDevices = when (result) {
            is DeviceResult.Success -> result.devices.toSet()
            is DeviceResult.Error -> emptySet()
        }
        for (row in taskRows) {
            val device = row.getDeployTask().device
            if (device.isNotEmpty() && device !in connectedDevices) {
                row.setDeviceUnavailable("Device disconnected")
            } else {
                row.setDeviceAvailable()
            }
        }
    }

    // --- Config management ---

    private fun refreshConfigCombo() {
        isUpdatingCombo = true
        try {
            val names = configState.getConfigurationNames()
            configComboModel.removeAllElements()
            names.forEach { configComboModel.addElement(it) }
            configComboModel.selectedItem = configState.getActiveConfig().name
        } finally {
            isUpdatingCombo = false
        }
    }

    private fun saveCurrentTasksToActiveConfig() {
        val entries = taskRows.map {
            val t = it.getDeployTask()
            MultiDeployConfigState.TaskEntry(t.device, t.module, t.gradleTask)
        }
        configState.saveConfiguration(configState.getActiveConfig().name, entries)
    }

    private fun restoreActiveConfig() {
        taskRows.clear()
        tasksPanel.removeAll()

        val config = configState.getActiveConfig()
        val modules = getModules()
        for (entry in config.tasks) {
            val task = DeployTask(entry.device, entry.module, entry.gradleTask)
            val row = createRow(modules)
            row.restoreFrom(task)
            taskRows.add(row)
            tasksPanel.add(row)
        }

        tasksPanel.revalidate()
        tasksPanel.repaint()
    }

    private fun createRow(modules: List<String>): TaskRowPanel {
        return TaskRowPanel(
            deviceResult, modules,
            onRemove = { removeTask(it) },
            onRetryDeviceCheck = { checkDeviceHealth() },
            onRun = { runSingleTask(it) },
            onStop = { stopSingleTask(it) }
        )
    }

    private fun getModules(): List<String> {
        val rootName = project.name
        return ModuleManager.getInstance(project).modules
            .map { it.name }
            .filter { name ->
                name != rootName &&
                !name.endsWith(".main") &&
                !name.endsWith(".test") &&
                !name.endsWith(".unitTest") &&
                !name.endsWith(".androidTest")
            }
            .map { name ->
                if (name.startsWith("$rootName.")) name.removePrefix("$rootName.")
                else name
            }
            .distinct()
            .sorted()
    }

    private fun refreshDevices() {
        deviceResult = AdbHelper.getDevices()
    }

    private fun addTask() {
        val modules = getModules()
        val row = createRow(modules)

        // Pick a device not already used by existing rows
        if (deviceResult is DeviceResult.Success) {
            val usedDevices = taskRows.map { it.getDeployTask().device }.toSet()
            val available = (deviceResult as DeviceResult.Success).devices
            val pick = available.firstOrNull { it !in usedDevices }
            if (pick != null) row.selectDevice(pick)
        }

        taskRows.add(row)
        tasksPanel.add(row)
        tasksPanel.revalidate()
        tasksPanel.repaint()
        saveCurrentTasksToActiveConfig()
    }

    private fun removeTask(row: TaskRowPanel) {
        runner.stopTask(row)
        taskRows.remove(row)
        tasksPanel.remove(row)
        tasksPanel.revalidate()
        tasksPanel.repaint()
        saveCurrentTasksToActiveConfig()
    }

    // --- Actions ---

    private inner class RunAllAction : AnAction(
        "Run All", "Deploy all tasks simultaneously", AllIcons.Actions.Execute
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            saveCurrentTasksToActiveConfig()
            val tasks = taskRows.map { it.getDeployTask() }
            val keys = taskRows.toList<Any>()
            runner.runAll(tasks, keys, toolWindow,
                onTaskStarted = { index ->
                    if (index < taskRows.size) taskRows[index].setRunning()
                },
                onTaskFinished = { index, isError ->
                    if (index < taskRows.size) {
                        if (isError) taskRows[index].setError()
                        else taskRows[index].setIdle()
                    }
                }
            )
        }

        override fun update(e: AnActionEvent) {
            val running = runner.isRunning()
            e.presentation.isEnabled = taskRows.isNotEmpty() && !running
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class StopAllAction : AnAction(
        "Stop All", "Stop all running deployments", AllIcons.Actions.Suspend
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            runner.stopAll()
            taskRows.forEach { it.setIdle() }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = runner.isRunning()
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class AddTaskAction : AnAction(
        "Add Task", "Add a new deploy task", AllIcons.General.Add
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            addTask()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !runner.isRunning()
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class RefreshDevicesAction : AnAction(
        "Refresh Devices", "Refresh ADB device list", AllIcons.Actions.Refresh
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            refreshDevices()
            taskRows.forEach { it.updateDevices(deviceResult) }
            updateDeviceHealth(deviceResult)
        }
    }

    private inner class SaveConfigAction : AnAction(
        "Save", "Save current configuration", AllIcons.Actions.MenuSaveall
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            saveCurrentTasksToActiveConfig()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !runner.isRunning()
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class SaveAsConfigAction : AnAction(
        "Save As...", "Save as a new named configuration", AllIcons.Actions.Copy
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val name = Messages.showInputDialog(
                project,
                "Configuration name:",
                "Save Configuration As",
                null
            ) ?: return

            val trimmed = name.trim()
            if (trimmed.isEmpty()) return

            val entries = taskRows.map {
                val t = it.getDeployTask()
                MultiDeployConfigState.TaskEntry(t.device, t.module, t.gradleTask)
            }
            configState.saveConfiguration(trimmed, entries)
            configState.setActiveConfigName(trimmed)
            refreshConfigCombo()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !runner.isRunning()
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class DeleteConfigAction : AnAction(
        "Delete", "Delete current configuration", AllIcons.Actions.GC
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val activeName = configState.getActiveConfig().name
            if (activeName == MultiDeployConfigState.DEFAULT_CONFIG_NAME) return

            val confirm = Messages.showYesNoDialog(
                project,
                "Delete configuration '$activeName'?",
                "Delete Configuration",
                Messages.getQuestionIcon()
            )
            if (confirm != Messages.YES) return

            configState.deleteConfiguration(activeName)
            refreshConfigCombo()
            restoreActiveConfig()
        }

        override fun update(e: AnActionEvent) {
            val activeName = configState.getActiveConfig().name
            e.presentation.isEnabled =
                !runner.isRunning() && activeName != MultiDeployConfigState.DEFAULT_CONFIG_NAME
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }
}

package com.androidmultideploy

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.ContentFactory
import java.io.File

class DeployRunner(private val project: Project) {

    private val processHandlers = mutableMapOf<Any, OSProcessHandler>()

    fun runTask(
        task: DeployTask,
        key: Any,
        toolWindow: ToolWindow,
        onFinished: (Boolean) -> Unit
    ) {
        stopTask(key)
        startTask(task, key, toolWindow, onFinished)
    }

    fun runAll(
        tasks: List<DeployTask>,
        keys: List<Any>,
        toolWindow: ToolWindow,
        onTaskStarted: (Int) -> Unit,
        onTaskFinished: (Int, Boolean) -> Unit
    ) {
        if (tasks.isEmpty()) return

        stopAll()

        toolWindow.contentManager.contents
            .filter { it.displayName != "Tasks" }
            .forEach { toolWindow.contentManager.removeContent(it, true) }

        tasks.forEachIndexed { index, task ->
            onTaskStarted(index)
            startTask(task, keys[index], toolWindow) { isError ->
                onTaskFinished(index, isError)
            }
        }
    }

    fun stopTask(key: Any) {
        processHandlers.remove(key)?.let {
            if (!it.isProcessTerminated) it.destroyProcess()
        }
    }

    fun stopAll() {
        processHandlers.values.forEach {
            if (!it.isProcessTerminated) it.destroyProcess()
        }
        processHandlers.clear()
    }

    fun isRunning(): Boolean = processHandlers.values.any { !it.isProcessTerminated }

    fun isTaskRunning(key: Any): Boolean {
        val handler = processHandlers[key] ?: return false
        return !handler.isProcessTerminated
    }

    private fun startTask(
        task: DeployTask,
        key: Any,
        toolWindow: ToolWindow,
        onFinished: (Boolean) -> Unit
    ) {
        val basePath = project.basePath ?: return
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val gradlewFile = File(basePath, if (isWindows) "gradlew.bat" else "gradlew")

        val commandLine = GeneralCommandLine(gradlewFile.absolutePath, task.gradleTask)
            .withWorkDirectory(basePath)
            .withEnvironment("ANDROID_SERIAL", task.device)
            .withCharset(Charsets.UTF_8)

        try {
            val output = StringBuilder()
            val handler = ProcessHandlerFactory.getInstance()
                .createColoredProcessHandler(commandLine)

            processHandlers[key] = handler

            handler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    output.append(event.text)
                }

                override fun processTerminated(event: ProcessEvent) {
                    ApplicationManager.getApplication().invokeLater {
                        // Stale check: if this handler was replaced or removed, ignore
                        if (processHandlers[key] !== handler) return@invokeLater
                        processHandlers.remove(key)
                        val isError = event.exitCode != 0
                        if (isError) {
                            showErrorTab(task, output.toString(), toolWindow)
                        }
                        notifyTaskResult(task, isError)
                        onFinished(isError)
                    }
                }
            })

            handler.startNotify()
        } catch (e: Exception) {
            ApplicationManager.getApplication().invokeLater {
                showErrorTab(task, "Error: ${e.message}\n", toolWindow)
                notifyTaskResult(task, true)
                onFinished(true)
            }
        }
    }

    private fun showErrorTab(task: DeployTask, output: String, toolWindow: ToolWindow) {
        val console = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project).console
        console.print(output, ConsoleViewContentType.LOG_ERROR_OUTPUT)
        val label = "${task.module} \u2192 ${shortenDevice(task.device)} [FAILED]"
        val content = ContentFactory.getInstance()
            .createContent(console.component, label, false)
        content.isCloseable = true
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content)
    }

    private fun notifyTaskResult(task: DeployTask, isError: Boolean) {
        val label = "${task.module} \u2192 ${shortenDevice(task.device)}"
        val (title, type) = if (isError) {
            "Deploy failed: $label" to NotificationType.ERROR
        } else {
            "Deploy succeeded: $label" to NotificationType.INFORMATION
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Android MultiDeploy")
            .createNotification(title, type)
            .notify(project)
    }

    private fun shortenDevice(device: String): String {
        return if (device.length > 12) ".." + device.takeLast(10) else device
    }
}

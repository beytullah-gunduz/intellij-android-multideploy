package com.androidmultideploy

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class MultiDeployToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MultiDeployPanel(project, toolWindow)
        val content = ContentFactory.getInstance().createContent(panel, "Tasks", false)
        toolWindow.contentManager.addContent(content)
    }
}

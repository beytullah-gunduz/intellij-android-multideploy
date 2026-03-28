package com.androidmultideploy

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "MultiDeployConfigurations",
    storages = [Storage("multiDeployConfigs.xml")]
)
class MultiDeployConfigState : PersistentStateComponent<MultiDeployConfigState.ConfigData> {

    data class TaskEntry(
        var device: String = "",
        var module: String = "",
        var gradleTask: String = ""
    )

    data class Configuration(
        var name: String = DEFAULT_CONFIG_NAME,
        var tasks: MutableList<TaskEntry> = mutableListOf()
    )

    data class ConfigData(
        var configurations: MutableList<Configuration> = mutableListOf(),
        var activeConfigName: String = DEFAULT_CONFIG_NAME
    )

    private var myState = ConfigData()

    override fun getState(): ConfigData = myState

    override fun loadState(state: ConfigData) {
        myState = state
        ensureDefaultExists()
    }

    override fun initializeComponent() {
        ensureDefaultExists()
    }

    private fun ensureDefaultExists() {
        if (myState.configurations.none { it.name == DEFAULT_CONFIG_NAME }) {
            myState.configurations.add(0, Configuration(name = DEFAULT_CONFIG_NAME))
        }
    }

    fun getConfigurations(): List<Configuration> {
        ensureDefaultExists()
        return myState.configurations
    }

    fun getActiveConfig(): Configuration {
        ensureDefaultExists()
        return myState.configurations.find { it.name == myState.activeConfigName }
            ?: myState.configurations.first()
    }

    fun setActiveConfigName(name: String) {
        myState.activeConfigName = name
    }

    fun saveConfiguration(name: String, tasks: List<TaskEntry>) {
        val existing = myState.configurations.find { it.name == name }
        if (existing != null) {
            existing.tasks = tasks.toMutableList()
        } else {
            myState.configurations.add(Configuration(name = name, tasks = tasks.toMutableList()))
        }
    }

    fun deleteConfiguration(name: String) {
        if (name == DEFAULT_CONFIG_NAME) return
        myState.configurations.removeAll { it.name == name }
        if (myState.activeConfigName == name) {
            myState.activeConfigName = DEFAULT_CONFIG_NAME
        }
    }

    fun getConfigurationNames(): List<String> {
        ensureDefaultExists()
        return myState.configurations.map { it.name }
    }

    companion object {
        const val DEFAULT_CONFIG_NAME = "Default"

        fun getInstance(project: Project): MultiDeployConfigState {
            return project.getService(MultiDeployConfigState::class.java)
        }
    }
}

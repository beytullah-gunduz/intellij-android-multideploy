package com.androidmultideploy

import java.io.File

sealed class DeviceResult {
    data class Success(val devices: List<String>) : DeviceResult()
    data class Error(val message: String) : DeviceResult()
}

object AdbHelper {

    fun findAdb(): String? {
        // Check ANDROID_HOME / ANDROID_SDK_ROOT
        val sdkDir = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
        if (sdkDir != null) {
            val adb = File(sdkDir, "platform-tools/adb")
            if (adb.exists() && adb.canExecute()) return adb.absolutePath
        }

        // Common SDK locations
        val home = System.getProperty("user.home")
        val candidates = listOf(
            "$home/Library/Android/sdk/platform-tools/adb",
            "$home/Android/Sdk/platform-tools/adb",
            "/opt/homebrew/bin/adb",
            "/usr/local/bin/adb"
        )
        for (path in candidates) {
            val file = File(path)
            if (file.exists() && file.canExecute()) return file.absolutePath
        }

        // Fallback: ask the shell
        return try {
            val process = ProcessBuilder("which", "adb").start()
            val result = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (result.isNotEmpty() && File(result).exists()) result else null
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDevices(adbPath: String): List<String>? {
        val process = ProcessBuilder(adbPath, "devices")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) return null

        val devices = output.lines()
            .filter { it.contains("\tdevice") }
            .map { it.split("\t").first().trim() }

        return devices.ifEmpty { null }
    }

    fun getDevices(): DeviceResult {
        val adbPath = findAdb()
            ?: return DeviceResult.Error("adb not found. Set ANDROID_HOME or install platform-tools.")

        return try {
            val devices = queryDevices(adbPath)

            // If adb daemon was just starting, retry once to give it time
            if (devices == null) {
                Thread.sleep(1500)
                val retry = queryDevices(adbPath)
                    ?: return DeviceResult.Error("No devices connected. Plug in via USB or use adb connect.")
                DeviceResult.Success(retry)
            } else {
                DeviceResult.Success(devices)
            }
        } catch (e: Exception) {
            DeviceResult.Error("Failed to run adb: ${e.message}")
        }
    }
}

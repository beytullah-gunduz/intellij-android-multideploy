package com.androidmultideploy

import java.io.File
import java.util.concurrent.TimeUnit

sealed class DeviceResult {
    data class Success(
        val devices: List<String>,
        val deviceNames: Map<String, String> = emptyMap()
    ) : DeviceResult()
    data class Error(val message: String) : DeviceResult()
}

object AdbHelper {

    fun findAdb(): String? {
        val sdkDir = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
        if (sdkDir != null) {
            val adb = File(sdkDir, "platform-tools/adb")
            if (adb.exists() && adb.canExecute()) return adb.absolutePath
        }

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

        return try {
            val process = ProcessBuilder("which", "adb").start()
            val result = process.inputStream.bufferedReader().readText().trim()
            process.waitFor(3, TimeUnit.SECONDS)
            if (result.isNotEmpty() && File(result).exists()) result else null
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDevices(adbPath: String): List<String>? {
        val process = ProcessBuilder(adbPath, "devices")
            .redirectErrorStream(true)
            .start()
        val completed = process.waitFor(5, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return null
        }
        val output = process.inputStream.bufferedReader().readText()
        if (process.exitValue() != 0) return null

        val devices = output.lines()
            .filter { it.contains("\tdevice") }
            .map { it.split("\t").first().trim() }

        return devices.ifEmpty { null }
    }

    private fun getDeviceModel(adbPath: String, serial: String): String? {
        return try {
            val process = ProcessBuilder(adbPath, "-s", serial, "shell", "getprop", "ro.product.model")
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(3, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return null
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.exitValue() == 0 && output.isNotEmpty() && !output.startsWith("error")) output else null
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveDeviceNames(adbPath: String, serials: List<String>): Map<String, String> {
        return serials.mapNotNull { serial ->
            getDeviceModel(adbPath, serial)?.let { serial to it }
        }.toMap()
    }

    /**
     * Fast check — only runs `adb devices`, no model queries.
     * Used for periodic health checks.
     */
    fun getConnectedSerials(): Set<String> {
        val adbPath = findAdb() ?: return emptySet()
        return try {
            queryDevices(adbPath)?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * Full device info — runs `adb devices` then queries each device's model name.
     * Used for initial load and explicit refresh.
     */
    fun getDevices(): DeviceResult {
        val adbPath = findAdb()
            ?: return DeviceResult.Error("adb not found. Set ANDROID_HOME or install platform-tools.")

        return try {
            val serials = queryDevices(adbPath)

            if (serials == null) {
                Thread.sleep(1500)
                val retry = queryDevices(adbPath)
                    ?: return DeviceResult.Error("No devices connected. Plug in via USB or use adb connect.")
                DeviceResult.Success(retry, resolveDeviceNames(adbPath, retry))
            } else {
                DeviceResult.Success(serials, resolveDeviceNames(adbPath, serials))
            }
        } catch (e: Exception) {
            DeviceResult.Error("Failed to run adb: ${e.message}")
        }
    }
}

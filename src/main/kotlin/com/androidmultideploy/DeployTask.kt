package com.androidmultideploy

data class DeployTask(
    val device: String,
    val module: String,
    val gradleTask: String
)

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.androidmultideploy"
version = "1.0.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3.2")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    instrumentCode {
        enabled = false
    }
    patchPluginXml {
        sinceBuild.set("243")
        untilBuild.set("263.*")
    }
}

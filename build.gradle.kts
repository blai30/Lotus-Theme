plugins {
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    // IntelliJ Platform Gradle Plugin Dependencies Extension: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
    }
}

// IntelliJ Platform Gradle Plugin Extension: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    // Resource-only plugin: no bytecode to instrument and no settings to index.
    instrumentCode = false
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }
}

// Gradle Changelog Plugin: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    // Accept historical two-part versions such as 1.12 alongside SemVer.
    headerParserRegex = """(\d+(?:\.\d+)+)""".toRegex()
}

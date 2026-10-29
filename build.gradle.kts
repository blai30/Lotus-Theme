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

// Theme validation: the IDE silently ignores unknown color references, so catch them at build time.
abstract class VerifyThemesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourcesDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val resources = resourcesDirectory.get().asFile
        val hexColor = Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")
        val identifier = Regex("[A-Za-z][A-Za-z0-9]*")
        val problems = mutableListOf<String>()

        fun parseXml(file: File) {
            try {
                javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            } catch (exception: Exception) {
                problems += "${file.name}: invalid XML (${exception.message})"
            }
        }

        resources.resolve("colors").listFiles { file -> file.extension == "xml" }.orEmpty().forEach(::parseXml)

        val themeFiles = resources.resolve("themes").listFiles { file -> file.name.endsWith(".theme.json") }.orEmpty()
        if (themeFiles.isEmpty()) problems += "No *.theme.json files found"

        for (themeFile in themeFiles) {
            val theme = try {
                groovy.json.JsonSlurper().parse(themeFile) as Map<*, *>
            } catch (exception: Exception) {
                problems += "${themeFile.name}: invalid JSON (${exception.message})"
                continue
            }

            val colors = theme["colors"] as? Map<*, *> ?: emptyMap<Any, Any>()
            for ((name, value) in colors) {
                if (value !is String || !(hexColor.matches(value) || value in colors.keys)) {
                    problems += "${themeFile.name}: colors.$name has invalid value '$value'"
                }
            }

            // Every bare identifier in "ui" and "icons" must name a color defined in "colors".
            fun checkReferences(node: Any?, path: String) {
                when (node) {
                    is Map<*, *> -> node.forEach { (key, value) -> checkReferences(value, "$path.$key") }
                    is String -> if (identifier.matches(node) && node !in colors.keys) {
                        problems += "${themeFile.name}: $path references undefined color '$node'"
                    }
                }
            }
            checkReferences(theme["ui"], "ui")
            checkReferences(theme["icons"], "icons")

            val editorScheme = theme["editorScheme"] as? String
            if (editorScheme != null && !resources.resolve(editorScheme.removePrefix("/")).isFile) {
                problems += "${themeFile.name}: editorScheme '$editorScheme' does not exist"
            }
        }

        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n", "Theme verification failed:\n"))
    }
}

val verifyThemes by tasks.registering(VerifyThemesTask::class) {
    group = "verification"
    description = "Checks that theme JSON and color scheme XML files parse and that every color reference resolves."
    resourcesDirectory = layout.projectDirectory.dir("src/main/resources")
}

tasks.check {
    dependsOn(verifyThemes)
}

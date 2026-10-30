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
        // Color names, including platform scale steps such as Gray0.25.
        val identifier = Regex("[A-Za-z][A-Za-z0-9]*(\\.[0-9]+)?")
        // Platform themes a plugin theme may extend.
        val bundledThemes = setOf("Darcula", "IntelliJ", "ExperimentalDark", "ExperimentalLight", "Islands Dark", "Islands Light")
        val problems = mutableListOf<String>()

        fun parseXml(file: File): org.w3c.dom.Document? = try {
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        } catch (exception: Exception) {
            problems += "${file.name}: invalid XML (${exception.message})"
            null
        }

        resources.resolve("colors").listFiles { file -> file.extension == "xml" }.orEmpty().forEach { parseXml(it) }

        // Theme provider ids declared by this plugin, mapped to their theme files.
        val providers = mutableMapOf<String, File>()
        parseXml(resources.resolve("META-INF/plugin.xml"))?.getElementsByTagName("themeProvider")?.let { nodes ->
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as org.w3c.dom.Element
                val id = element.getAttribute("id")
                val file = resources.resolve(element.getAttribute("path").removePrefix("/"))
                if (!file.isFile) problems += "plugin.xml: themeProvider '$id' points to missing file '${element.getAttribute("path")}'"
                providers[id] = file
            }
        }

        val themeFiles = resources.resolve("themes").listFiles { file -> file.name.endsWith(".theme.json") }.orEmpty()
        if (themeFiles.isEmpty()) problems += "No *.theme.json files found"

        val themes = themeFiles.associateWith { themeFile ->
            try {
                groovy.json.JsonSlurper().parse(themeFile) as Map<*, *>
            } catch (exception: Exception) {
                problems += "${themeFile.name}: invalid JSON (${exception.message})"
                null
            }
        }

        for ((themeFile, theme) in themes) {
            if (theme == null) continue
            val ownColors = theme["colors"] as? Map<*, *> ?: emptyMap<Any, Any>()

            // A theme extending another theme in this plugin inherits its colors and may only override existing ones.
            val parentId = theme["parentTheme"] as? String
            val parentTheme = providers[parentId]?.let { themes[it] }
            val parentColors = when {
                parentId == null || parentId in bundledThemes -> emptyMap<Any, Any>()
                parentId in providers -> parentTheme?.get("colors") as? Map<*, *> ?: emptyMap<Any, Any>()
                else -> {
                    problems += "${themeFile.name}: parentTheme '$parentId' is neither a bundled theme nor a themeProvider in plugin.xml"
                    emptyMap<Any, Any>()
                }
            }
            if (parentId in providers) {
                if (parentTheme == null) {
                    problems += "${themeFile.name}: parent theme '$parentId' could not be loaded"
                } else {
                    for (name in ownColors.keys - parentColors.keys) {
                        problems += "${themeFile.name}: colors.$name overrides a color that parent '$parentId' does not define"
                    }
                }
            }
            val colors = parentColors + ownColors

            for ((name, value) in ownColors) {
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
                    } else if (node.startsWith("#") && !hexColor.matches(node)) {
                        problems += "${themeFile.name}: $path has invalid color '$node'"
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

val verifyThemes = tasks.register<VerifyThemesTask>("verifyThemes") {
    group = "verification"
    description = "Checks that theme JSON and color scheme XML files parse and that every color reference resolves, including colors inherited from parent themes in this plugin."
    resourcesDirectory = layout.projectDirectory.dir("src/main/resources")
}

tasks.check {
    dependsOn(verifyThemes)
}

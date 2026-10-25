// Writes Lich version profiles (version JSON + libraries) into a launcher-style directory, or adds Lich
// to a Prism instance. Phase 1 uses it for devlaunch and Prism testing; the user-facing installer
// builds on it later. PLAN.md §4.5, §4.6.
plugins {
    id("lich.java")
    application
}

dependencies {
    implementation(libs.gson)
}

application {
    mainClass = "dev.lich.installer.ProfileInstaller"
}

// The jars a Lich profile puts on the game's class path: Lich's modules, ASM 9, Nashorn and LWJGL 3 with its
// natives, without other dependencies, because the game's own libraries (log4j, jopt-simple, ...) come from
// the version the profile inherits from.
val lich = configurations.dependencyScope("lich")
val lichRuntime = configurations.resolvable("lichRuntime") {
    extendsFrom(lich.get())
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

dependencies {
    add("lich", project(":boot"))
    add("lich", project(":launchwrapper"))
    add("lich", project(":transform"))
    add("lich", project(":remap"))
    add("lich", "dev.lich:remap-core")
    add("lich", project(":loader"))
    add("lich", project(":mixin-provider"))
    add("lich", project(":api"))
    add("lich", project(":v1_7_10-compat-forge"))
    add("lich", project(":v1_8_9-compat-forge"))
    // Lich mods' Mixin. The system class loader holds both back unless Lich mods are installed (PLAN.md §2.4).
    add("lich", libs.mixin)
    add("lich", libs.mixinextras)
    add("lich", project(":compat-jdk"))
    add("lich", project(":compat-asm"))
    add("lich", project(":shared-compat-mc"))
    add("lich", project(":v1_7_10-compat-mc"))
    add("lich", project(":v1_8_9-compat-mc"))
    add("lich", libs.asm)
    add("lich", libs.asm.tree)
    add("lich", libs.asm.analysis)
    add("lich", libs.asm.commons)
    add("lich", libs.asm.util)
    // For scripting mods (jdk.nashorn → org.openjdk.nashorn); it runs on the ASM 9 above.
    add("lich", libs.nashorn.core)
    // LWJGL 3 replaces the game's LWJGL 2 (compat-lwjgl2 has LWJGL 2's API on it). Every platform's natives go
    // on the class path, where LWJGL 3 finds its own; the system class loader drops LWJGL 2 (PLAN.md §2.5).
    add("lich", project(":compat-lwjgl2"))
    for (module in listOf(libs.lwjgl3.asProvider(), libs.lwjgl3.glfw, libs.lwjgl3.opengl, libs.lwjgl3.openal,
            libs.lwjgl3.stb, libs.lwjgl3.tinyfd, libs.lwjgl3.tinyexr, libs.lwjgl3.nanovg)) {
        add("lich", module)
        for (platform in listOf("windows", "windows-arm64", "windows-x86", "linux", "linux-arm64", "macos", "macos-arm64")) {
            add("lich", variantOf(module) { classifier("natives-$platform") })
        }
    }
}

// MCP's SRG mappings for the -Plich.mc version: the vanilla profile remaps the game to SRG names with them
// (PLAN.md §3.1). Downloaded from Forge's Maven onto the user's machine, like the game itself; never shipped.
val mcpMappings = configurations.dependencyScope("mcpMappings")
val mcpMappingsFiles = configurations.resolvable("mcpMappingsFiles") {
    extendsFrom(mcpMappings.get())
    isTransitive = false
}
dependencies {
    addProvider("mcpMappings", providers.gradleProperty("lich.mc").orElse("1.8.9").map { "de.oceanlabs.mcp:mcp:$it:srg@zip" })
    // The API of that version only: both versions' API jars have the same class names.
    addProvider("lich", providers.gradleProperty("lich.mc").orElse("1.8.9").map {
        project.dependencies.project(mapOf("path" to ":v${it.replace('.', '_')}-api"))
    })
}

/** Runs ProfileInstaller with the Lich jars, the JVM flags and [target] (--root or --prism-instance). */
fun JavaExec.runInstaller(target: Provider<List<String>>) {
    val mcVersion = providers.gradleProperty("lich.mc").orElse("1.8.9")
    val mappings: FileCollection = mcpMappingsFiles.get()
    val flags = rootProject.layout.projectDirectory.file("core/boot/src/main/resources/dev/lich/boot/jvm-flags.txt").asFile
    val jars: FileCollection = lichRuntime.get()
    val version = project.version.toString()
    // group:artifact:version[:classifier]=jar for each library; Lich's own modules are dev.lich:lich-<name>.
    val libraries = lichRuntime.get().incoming.artifacts.resolvedArtifacts.map { artifacts ->
        artifacts.map { artifact ->
            val coordinates = when (val id = artifact.id.componentIdentifier) {
                is ModuleComponentIdentifier -> {
                    // The classifier (LWJGL's natives-<platform>) is only in the file name.
                    val classifier = artifact.file.name.removeSuffix(".jar").removePrefix("${id.module}-${id.version}").removePrefix("-")
                    "${id.group}:${id.module}:${id.version}" + if (classifier.isEmpty()) "" else ":$classifier"
                }
                is ProjectComponentIdentifier -> "dev.lich:lich-${id.projectName}:$version"
                else -> throw GradleException("Unexpected Lich library $id")
            }
            "$coordinates=${artifact.file.absolutePath}"
        }
    }

    group = "lich"
    inputs.files(jars).withPropertyName("lichJars")
    inputs.files(mappings).withPropertyName("mcpMappings")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--mc", mcVersion.get(), "--flags", flags.absolutePath) + target.get() +
            libraries.get().flatMap { listOf("--library", it) } +
            listOf("--library", "de.oceanlabs.mcp:mcp:${mcVersion.get()}:srg@zip=${mappings.singleFile.absolutePath}")
    })
}

// ./gradlew :installer:installDevProfile [-Plich.mc=1.8.9]
tasks.register<JavaExec>("installDevProfile") {
    description = "Writes the Lich version profile and its libraries into run/cache for devlaunch."
    val root = rootProject.layout.projectDirectory.dir("run/cache").asFile
    runInstaller(provider { listOf("--root", root.absolutePath) })
}

// ./gradlew :installer:installPrism [-Plich.mc=1.8.9] [-Plich.prismInstance=<dir>]
// Default instance: %APPDATA%/PrismLauncher/instances/lich-forge-<mc>. It must exist already; this only adds or
// refreshes the Lich component, so run it after every build you want to test in Prism.
tasks.register<JavaExec>("installPrism") {
    description = "Adds or refreshes the Lich component in a Prism instance."
    val instance = providers.gradleProperty("lich.prismInstance").orElse(
        providers.gradleProperty("lich.mc").orElse("1.8.9").map { mc ->
            "${System.getenv("APPDATA")}/PrismLauncher/instances/lich-forge-$mc"
        })
    runInstaller(instance.map { listOf("--prism-instance", it) })
}

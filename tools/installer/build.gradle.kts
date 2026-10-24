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

// The jars a Lich profile puts on the game's class path: Lich's modules, ASM 9 and Nashorn, without other
// dependencies, because the game's own libraries (log4j, jopt-simple, ...) come from the version the
// profile inherits from.
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
}

/** Runs ProfileInstaller with the Lich jars, the JVM flags and [target] (--root or --prism-instance). */
fun JavaExec.runInstaller(target: Provider<List<String>>) {
    val mcVersion = providers.gradleProperty("lich.mc").orElse("1.8.9")
    val flags = rootProject.layout.projectDirectory.file("core/boot/src/main/resources/dev/lich/boot/jvm-flags.txt").asFile
    val jars: FileCollection = lichRuntime.get()
    val version = project.version.toString()
    // group:artifact:version=jar for each library; Lich's own modules are dev.lich:lich-<name>.
    val libraries = lichRuntime.get().incoming.artifacts.resolvedArtifacts.map { artifacts ->
        artifacts.map { artifact ->
            val coordinates = when (val id = artifact.id.componentIdentifier) {
                is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
                is ProjectComponentIdentifier -> "dev.lich:lich-${id.projectName}:$version"
                else -> throw GradleException("Unexpected Lich library $id")
            }
            "$coordinates=${artifact.file.absolutePath}"
        }
    }

    group = "lich"
    inputs.files(jars).withPropertyName("lichJars")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--mc", mcVersion.get(), "--flags", flags.absolutePath) + target.get() +
            libraries.get().flatMap { listOf("--library", it) }
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

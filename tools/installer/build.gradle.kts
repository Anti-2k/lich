// Writes Lich version profiles (version JSON + libraries) into a launcher-style directory, or adds Lich
// to a Prism instance: for devlaunch and Prism testing (Phase 1), and the player-facing Prism installer jar
// (installerJar, Phase 7). DOCS.md §4.5, §4.6.
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
    // Lich mods' Mixin. The system class loader holds both back unless Lich mods are installed (DOCS.md §2.4).
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
    // JAXB (javax.xml.bind), which Java 8 had and some mods use (DOCS.md §2.3). Not transitive here, so all of it.
    for (module in listOf(libs.jaxb.api, libs.jaxb.runtime, libs.jaxb.txw2, libs.istack.commons.runtime, libs.activation)) {
        add("lich", module)
    }
    // LWJGL 3 replaces the game's LWJGL 2 (compat-lwjgl2 has LWJGL 2's API on it). Every platform's natives go
    // on the class path, where LWJGL 3 finds its own; the system class loader drops LWJGL 2 (DOCS.md §2.5).
    add("lich", project(":compat-lwjgl2"))
    for (module in listOf(libs.lwjgl3.asProvider(), libs.lwjgl3.glfw, libs.lwjgl3.opengl, libs.lwjgl3.openal,
            libs.lwjgl3.stb, libs.lwjgl3.tinyfd, libs.lwjgl3.tinyexr, libs.lwjgl3.nanovg)) {
        add("lich", module)
        for (platform in listOf("windows", "windows-arm64", "windows-x86", "linux", "linux-arm64", "macos", "macos-arm64")) {
            add("lich", variantOf(module) { classifier("natives-$platform") })
        }
    }
}

// Per Minecraft version: the jars above plus that version's API (both versions' API jars have the same class names, so
// a profile gets one), and MCP's SRG mappings, which the vanilla profile remaps the game to SRG names with (DOCS.md
// §3.1). The mappings come from Forge's Maven onto the user's machine, like the game itself; never shipped.
class VersionJars(val runtime: Configuration, val mappings: Configuration)

val versionJars = listOf("1.7.10", "1.8.9").associateWith { mc ->
    val suffix = mc.replace('.', '_')
    val api = configurations.dependencyScope("lichApi_$suffix")
    val mcp = configurations.dependencyScope("mcpMappings_$suffix")
    dependencies {
        add(api.name, project(":v$suffix-api"))
        add(mcp.name, "de.oceanlabs.mcp:mcp:$mc:srg@zip")
    }
    VersionJars(
        configurations.resolvable("lichRuntime_$suffix") {
            extendsFrom(lich.get(), api.get())
            isTransitive = false
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
                attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            }
        }.get(),
        configurations.resolvable("mcpMappingsFiles_$suffix") {
            extendsFrom(mcp.get())
            isTransitive = false
        }.get())
}

/** The -Plich.mc version (default 1.8.9). */
val selectedMc = providers.gradleProperty("lich.mc").getOrElse("1.8.9")

/** Runs ProfileInstaller for [mcVersion] with the Lich jars, the JVM flags and [target] (--root or --prism-instance). */
fun JavaExec.runInstaller(mcVersion: String, target: Provider<List<String>>) {
    val selected = versionJars[mcVersion] ?: throw GradleException("No Lich profile for Minecraft $mcVersion")
    val mappings: FileCollection = selected.mappings
    val flags = rootProject.layout.projectDirectory.file("core/boot/src/main/resources/dev/lich/boot/jvm-flags.txt").asFile
    val jars: FileCollection = selected.runtime
    val version = project.version.toString()
    // group:artifact:version[:classifier]=jar for each library; Lich's own modules are dev.lich:lich-<name>.
    val libraries = selected.runtime.incoming.artifacts.resolvedArtifacts.map { artifacts ->
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
        listOf("--mc", mcVersion, "--flags", flags.absolutePath) + target.get() +
            libraries.get().flatMap { listOf("--library", it) } +
            listOf("--library", "de.oceanlabs.mcp:mcp:$mcVersion:srg@zip=${mappings.singleFile.absolutePath}")
    })
}

// ./gradlew :installer:installDevProfile [-Plich.mc=1.8.9]
tasks.register<JavaExec>("installDevProfile") {
    description = "Writes the Lich version profile and its libraries into run/cache for devlaunch."
    val root = rootProject.layout.projectDirectory.dir("run/cache").asFile
    runInstaller(selectedMc, provider { listOf("--root", root.absolutePath) })
}

// ./gradlew :installer:installDevProfiles: the same for every version (the test harness runs packs of both).
val perVersion = versionJars.keys.map { mc ->
    tasks.register<JavaExec>("installDevProfile_${mc.replace('.', '_')}") {
        description = "Writes the Lich $mc version profile and its libraries into run/cache for devlaunch."
        val root = rootProject.layout.projectDirectory.dir("run/cache").asFile
        runInstaller(mc, provider { listOf("--root", root.absolutePath) })
    }
}
// They copy the same shared libraries into run/cache, so one at a time.
perVersion.zipWithNext { first, second -> second.configure { mustRunAfter(first) } }
tasks.register("installDevProfiles") {
    group = "lich"
    description = "Writes the Lich version profiles of every Minecraft version into run/cache for devlaunch."
    dependsOn(perVersion)
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
    runInstaller(selectedMc, instance.map { listOf("--prism-instance", it) })
}

// The player-facing installer: one jar with the installer, Gson, Lich's own jars and, per Minecraft version, the list
// of libraries Prism downloads (Payload). Its main class is a Java 8 stub, so a too-old Java gets a message, not
// silence (src/start).
val start = sourceSets.create("start")
tasks.named<JavaCompile>(start.compileJavaTaskName) {
    options.release = 8
    // "source value 8 is obsolete": it's deliberate.
    options.compilerArgs.add("-Xlint:-options")
}

val payloads = versionJars.keys.map { mc ->
    tasks.register<JavaExec>("installerPayload_${mc.replace('.', '_')}") {
        description = "Writes what the installer jar installs for Minecraft $mc."
        val dir = layout.buildDirectory.dir("payload/$mc").get().asFile
        outputs.dir(dir).withPropertyName("payload")
        doFirst { dir.deleteRecursively() }
        runInstaller(mc, provider { listOf("--payload", dir.absolutePath) })
    }
}

// ./gradlew :installer:installerJar → tools/installer/build/installer/lich-installer-<version>.jar (its own folder:
// the module's plain jar in build/libs has the same name)
val installerJar = tasks.register<Jar>("installerJar") {
    group = "lich"
    description = "Builds the player-facing Prism installer jar."
    archiveFileName = "lich-installer-${project.version}.jar"
    destinationDirectory = layout.buildDirectory.dir("installer")
    manifest.attributes("Main-Class" to "dev.lich.installer.start.Start")
    from(sourceSets.main.get().output)
    from(start.output)
    val runtime = configurations.runtimeClasspath
    from(runtime.map { jars -> jars.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF", "module-info.class", "META-INF/versions/*/module-info.class")
    }
    for (payload in payloads) {
        from(payload) { into("lich") }
    }
    // Both versions' payloads carry the same Lich jars.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.assemble {
    dependsOn(installerJar)
}

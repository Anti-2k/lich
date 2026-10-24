// Writes Lich version profiles (version JSON + libraries) into a launcher-style directory. Phase 1 uses
// it for devlaunch (run/cache); the user-facing installer builds on it later. PLAN.md §4.5, §4.6.
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

// The Lich jars a profile puts on the game's class path, without their dependencies: the game's own
// libraries (log4j, jopt-simple, ...) come from the vanilla version the profile inherits from.
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
}

// ./gradlew :installer:installDevProfile [-Plich.mc=1.8.9]
tasks.register<JavaExec>("installDevProfile") {
    group = "lich"
    description = "Writes the Lich version profile and its libraries into run/cache for devlaunch."

    val mcVersion = providers.gradleProperty("lich.mc").orElse("1.8.9")
    val root = rootProject.layout.projectDirectory.dir("run/cache").asFile
    val flags = rootProject.layout.projectDirectory.file("core/boot/src/main/resources/dev/lich/boot/jvm-flags.txt").asFile
    val jars: FileCollection = lichRuntime.get()
    val version = project.version.toString()

    inputs.files(jars).withPropertyName("lichJars")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--mc", mcVersion.get(), "--root", root.absolutePath, "--flags", flags.absolutePath) +
            jars.files.flatMap { jar ->
                val artifact = jar.name.removeSuffix("-$version.jar")
                listOf("--library", "dev.lich:$artifact:$version=${jar.absolutePath}")
            }
    })
}

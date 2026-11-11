// Dev launcher: resolves a version JSON, downloads everything into run/cache and starts the game
// with any JDK. Used for the Java 8 baselines now, and for Lich profiles and the test harness later.
plugins {
    id("lich.java")
    application
}

dependencies {
    implementation(libs.gson)
}

application {
    mainClass = "dev.lich.devlaunch.Main"
}

// ./gradlew :devlaunch:runGame [-Plich.mc=1.8.9] [-Plich.profile=vanilla|lich|forge|lich-forge] [-Plich.gameJava=8] [-Plich.gameDir=<dir>] [-Plich.args="--skip-assets --dry-run"]
// Every profile but vanilla installs the dev profiles into run/cache first (:installer:installDevProfile):
// lich-<mc> (Lich, no Forge), forge-<mc> (plain Forge, for Java 8 baselines) and lich-forge-<mc>.
// The game's JDK comes from Gradle's toolchain detection, or from -Plich.gameJavaHome=<jdk dir> for JDKs
// Gradle can't find (unzipped ones have no registry entry on Windows).
// Everything the task's lambdas use is a local of the configure block: lambdas that reach script-level
// vals capture the script object, which the configuration cache can't store.
tasks.register<JavaExec>("runGame") {
    group = "lich"
    description = "Downloads and launches Minecraft through devlaunch."

    val mcVersion = providers.gradleProperty("lich.mc").orElse("1.8.9").get()
    val profile = providers.gradleProperty("lich.profile").orElse("vanilla").get()
    if (profile !in listOf("vanilla", "lich", "forge", "lich-forge")) {
        throw GradleException("lich.profile must be vanilla, lich, forge or lich-forge, not $profile")
    }
    if (profile != "vanilla") {
        dependsOn(":installer:installDevProfile")
    }
    val versionId = if (profile == "vanilla") mcVersion else "$profile-$mcVersion"
    val javaExe = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
    // A plain string: the configuration cache can't store the toolchain provider chain.
    val gameJava = providers.gradleProperty("lich.gameJavaHome").orNull?.let { File(it, "bin/$javaExe").absolutePath }
        ?: javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(providers.gradleProperty("lich.gameJava").orElse("8").get().toInt())
        }.get().executablePath.asFile.absolutePath
    val extraArgs = providers.gradleProperty("lich.args").orElse("")
    val runDir = rootProject.layout.projectDirectory.dir("run").asFile
    // -Plich.gameDir: the game directory (mods, saves, logs) somewhere else, e.g. in the repository of a mod being
    // tested, while downloads stay in run/cache. Its own property rather than --game-dir in lich.args, which is
    // split on spaces. A relative path is relative to this checkout.
    val gameDir = providers.gradleProperty("lich.gameDir").orNull
        ?.let { rootProject.layout.projectDirectory.file(it).asFile.absolutePath }

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    workingDir = runDir
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--version", versionId,
            "--java", gameJava,
            "--run-dir", runDir.absolutePath,
            "--jvm-arg", "-Xmx2G",
        ) + (if (gameDir != null) listOf("--game-dir", gameDir) else listOf()) +
            extraArgs.get().split(' ').filter { it.isNotBlank() }
    })
    doFirst { runDir.mkdirs() }
}

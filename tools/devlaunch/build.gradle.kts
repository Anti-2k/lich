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

// ./gradlew :devlaunch:runGame [-Plich.mc=1.8.9] [-Plich.gameJava=8] [-Plich.args="--skip-assets --dry-run"]
// The game's JDK comes from Gradle's toolchain detection, or from -Plich.gameJavaHome=<jdk dir> for JDKs
// Gradle can't find (unzipped ones have no registry entry on Windows).
// Everything the task's lambdas use is a local of the configure block: lambdas that reach script-level
// vals capture the script object, which the configuration cache can't store.
tasks.register<JavaExec>("runGame") {
    group = "lich"
    description = "Downloads and launches Minecraft through devlaunch."

    val mcVersion = providers.gradleProperty("lich.mc").orElse("1.8.9")
    val javaExe = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
    // A plain string: the configuration cache can't store the toolchain provider chain.
    val gameJava = providers.gradleProperty("lich.gameJavaHome").orNull?.let { File(it, "bin/$javaExe").absolutePath }
        ?: javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(providers.gradleProperty("lich.gameJava").orElse("8").get().toInt())
        }.get().executablePath.asFile.absolutePath
    val extraArgs = providers.gradleProperty("lich.args").orElse("")
    val runDir = rootProject.layout.projectDirectory.dir("run").asFile

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    workingDir = runDir
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--version", mcVersion.get(),
            "--java", gameJava,
            "--run-dir", runDir.absolutePath,
            "--jvm-arg", "-Xmx2G",
        ) + extraArgs.get().split(' ').filter { it.isNotBlank() }
    })
    doFirst { runDir.mkdirs() }
}

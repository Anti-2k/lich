// Phase 4: automated launch tests (PLAN.md §7). So far the modpack harness: a pinned modpack is fetched into
// run/packs, then each profile (Forge on Java 8, Lich on Java 25) starts it through devlaunch, and either joins the
// local test server and stays, or plays a singleplayer world (the driver below); then the logs are compared. Later:
// determinism replay, anticheat smoke test (§6.4).
plugins {
    id("lich.java")
    application
}

dependencies {
    // The harness starts the game through devlaunch, in a child JVM on this module's class path.
    implementation(project(":devlaunch"))
    implementation(libs.gson)
    implementation(libs.asm)
}

application {
    mainClass = "dev.lich.harness.Main"
}

// The world driver: a LaunchWrapper tweaker that plays a singleplayer world inside the game. It runs on Java 8 too,
// so it's compiled for Java 8, against nothing but LaunchWrapper's API and the game's log4j, and goes into the
// harness's resources as a jar, which GameRun puts on the game's class path.
val driver = sourceSets.create("driver")

dependencies {
    "driverCompileOnly"(project(":launchwrapper"))
    "driverCompileOnly"(libs.log4j.api.legacy)
}

// Lich's LaunchWrapper is Java 25 bytecode, but the driver only compiles against ITweaker's signatures, which are
// LaunchWrapper 1.12's: at run time it gets whichever LaunchWrapper the game has.
configurations.named(driver.compileClasspathConfigurationName) {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
}

tasks.named<JavaCompile>(driver.compileJavaTaskName) {
    options.release = 8
    // "source value 8 is obsolete": it's deliberate.
    options.compilerArgs.add("-Xlint:-options")
}

val driverJar = tasks.register<Jar>("driverJar") {
    archiveFileName = "harness-driver.jar"
    destinationDirectory = layout.buildDirectory.dir("driver")
    from(driver.output)
}

tasks.processResources {
    from(driverJar) { into("dev/lich/harness") }
}

// ./gradlew :harness:fetchPack "-Plich.pack=lost-era"
//   Downloads the pinned pack and every mod it lists (hashes checked) into run/packs/<pack>/minecraft.
// ./gradlew :harness:prismPack "-Plich.pack=lost-era"
//   Creates the Prism instances <pack>-forge-<mc> (Java 8) and <pack>-lich-<mc> (Java 25) from it. Then add Lich to
//   the second one: ./gradlew :installer:installPrism "-Plich.mc=<mc>" "-Plich.prismInstance=<its dir>".
// ./gradlew :harness:runPack "-Plich.pack=lost-era" "-Plich.mc=1.7.10" ["-Plich.profiles=forge,lich-forge"] ["-Plich.hold=60"]
//   Starts the local test server, runs each profile into it and writes run/harness/<pack>/report.md.
// ./gradlew :harness:worldPack "-Plich.pack=lost-era" "-Plich.mc=1.7.10" ["-Plich.profiles=..."] ["-Plich.hold=60"]
//   Plays a singleplayer world in each profile (create, tour, hold, save, reload) and writes world-report.md.
// -Plich.java8Home=<jdk dir> picks the Java 8 the Forge baseline runs on. Everything the tasks' lambdas use is a
// local of the configure block (configuration cache).
fun JavaExec.harness(command: String, vararg extra: Provider<List<String>>) {
    val pack = providers.gradleProperty("lich.pack")
    val java8Home = providers.gradleProperty("lich.java8Home").orElse("C:/Program Files/Java/jdk-8")
    val runDir = rootProject.layout.projectDirectory.dir("run").asFile
    val extras = extra.toList()

    group = "lich"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    workingDir = runDir
    argumentProviders.add(CommandLineArgumentProvider {
        val id = pack.orNull ?: throw GradleException("Pass -Plich.pack=<pack> (see test/harness/src/main/resources/dev/lich/harness/packs.json)")
        listOf(command, id, "--run-dir", runDir.absolutePath, "--java8-home", java8Home.get()) + extras.flatMap { it.get() }
    })
    doFirst { runDir.mkdirs() }
}

tasks.register<JavaExec>("fetchPack") {
    description = "Downloads a pinned modpack into run/packs."
    harness("fetch")
}

tasks.register<JavaExec>("prismPack") {
    description = "Creates Forge and Lich Prism instances of a pinned modpack."
    val instances = providers.gradleProperty("lich.prismInstances")
        .orElse("${System.getenv("APPDATA")}/PrismLauncher/instances")
    harness("prism", instances.map { listOf("--instances", it) })
}

tasks.register<JavaExec>("runPack") {
    description = "Runs a modpack in Forge on Java 8 and Lich on Java 25, joins the test server and compares the logs."
    // The lich-forge-<mc> and forge-<mc> version JSONs devlaunch starts.
    dependsOn(":installer:installDevProfile")
    harness("run", gameOptions())
}

tasks.register<JavaExec>("worldPack") {
    description = "Plays a singleplayer world of a modpack in Forge on Java 8 and Lich on Java 25 and compares the logs."
    dependsOn(":installer:installDevProfile")
    harness("world", gameOptions())
}

// Options shared by runPack and worldPack. The lambdas capture only their parameters (configuration cache).
fun gameOptions(): Provider<List<String>> {
    val mc = providers.gradleProperty("lich.mc").orElse("1.8.9")
    val profiles = providers.gradleProperty("lich.profiles").orElse("forge,lich-forge")
    val hold = providers.gradleProperty("lich.hold").orElse("60")
    return mc.zip(profiles.zip(hold) { p, h -> p to h }) { m, (p, h) ->
        listOf("--mc", m, "--profiles", p, "--hold", h)
    }
}

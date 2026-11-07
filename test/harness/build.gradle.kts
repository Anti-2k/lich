// Phase 4: automated launch tests (DOCS.md §7). So far the modpack harness: a pinned modpack is fetched into
// run/packs, then each profile (Forge on Java 8, Lich on Java 25) plays the same scenario through the driver below
// (boot, mod list, the test server, a singleplayer world, save and reload), and the two runs are compared.
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

// ./gradlew :harness:fetchPack "-Plich.pack=rlcraft"
//   Downloads the pinned pack and every mod it lists (hashes checked) into run/packs/<pack>/minecraft.
// ./gradlew :harness:prismPack "-Plich.pack=rlcraft"
//   Creates the Prism instances <pack>-forge-<mc> (Java 8) and <pack>-lich-<mc> (Java 25) from it. Then add Lich to
//   the second one: ./gradlew :installer:installPrism "-Plich.mc=<mc>" "-Plich.prismInstance=<its dir>".
// ./gradlew :harness:runPack "-Plich.pack=<packs>" ["-Plich.baseline=auto|rerun|skip|forge"] ["-Plich.until=<step>"]
//         ["-Plich.skip=tour,inventory"] ["-Plich.hold=20"] ["-Plich.gallery=on|off"] ["-Plich.server=on|off"]
//         ["-Plich.mods=on"]
//   Plays the scenario on Forge (Java 8; reused while nothing it depends on changed) and Lich (Java 25), and writes
//   run/harness/<pack>/report.md and run/harness/summary.md. <packs>: ids, comma-separated, all, @quick or @<mc>.
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
        val id = pack.orNull ?: throw GradleException("Pass -Plich.pack=<packs> (see test/harness/src/main/resources/dev/lich/harness/packs.json)")
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
    description = "Plays modpacks on Forge (Java 8) and Lich (Java 25) and compares the runs step by step."
    // The lich-forge-<mc> and forge-<mc> version JSONs devlaunch starts, for both versions (a suite may mix them).
    dependsOn(":installer:installDevProfiles")
    harness("run", option("baseline"), option("until"), option("skip"), option("hold"), option("gallery"), option("server"), option("mods"))
}

/** --<name> <value> for -Plich.<name>, or nothing. The lambda captures only its parameter (configuration cache). */
fun option(name: String): Provider<List<String>> =
    providers.gradleProperty("lich.$name").map { listOf("--$name", it) }.orElse(listOf())

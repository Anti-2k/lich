// LWJGL 2's API on top of LWJGL 3 (DOCS.md §2.5). Two parts:
// - org/lwjgl/**: the LWJGL 2 classes LWJGL 3 doesn't have (Display, Keyboard, Mouse, Sys, ...), under
//   their own names, so mods' Mixins and coremods that target them keep matching. Built on GLFW.
// - dev/lich/compat/lwjgl2/**: the redirect table for members LWJGL 3 dropped from classes both have
//   (GL11.glGetFloat(int, FloatBuffer), AL.create(), ...), and the static mirrors it points at. The
//   generated parts, renames.txt and ContextCapabilities.java, come from the generate* tasks below.
plugins {
    id("lich.java")
    id("lich.publish")
}

dependencies {
    implementation(project(":transform"))
    // On the game's class path at runtime: the installer ships LWJGL 3, the game provides log4j.
    compileOnly(libs.lwjgl3)
    compileOnly(libs.lwjgl3.glfw)
    compileOnly(libs.lwjgl3.opengl)
    compileOnly(libs.lwjgl3.openal)
    compileOnly(libs.lwjgl3.nanovg)
    compileOnly(libs.log4j.api.legacy)

    testImplementation(libs.lwjgl3)
    testImplementation(libs.lwjgl3.glfw)
    testImplementation(libs.lwjgl3.opengl)
    testImplementation(libs.lwjgl3.openal)
    testImplementation(libs.lwjgl3.nanovg)
    testImplementation(libs.log4j.api.legacy)
}

// The rename table: every public static LWJGL 2 method that LWJGL 3 dropped but still has under another
// name with the same parameters (glGetFloat(int, FloatBuffer) → glGetFloatv). Regenerate it after
// changing either LWJGL version: ./gradlew :compat-lwjgl2:generateRenames
val generator = sourceSets.create("generator")
val lwjgl2 = configurations.dependencyScope("lwjgl2")
val lwjgl2Classpath = configurations.resolvable("lwjgl2Classpath") {
    extendsFrom(lwjgl2.get())
    isTransitive = false
}
dependencies {
    add("lwjgl2", libs.lwjgl2)
    "generatorImplementation"(libs.asm)
    "generatorImplementation"(libs.asm.tree)
    "generatorRuntimeOnly"(libs.lwjgl3)
    "generatorRuntimeOnly"(libs.lwjgl3.glfw)
    "generatorRuntimeOnly"(libs.lwjgl3.opengl)
    "generatorRuntimeOnly"(libs.lwjgl3.openal)
}

/** Runs [main] from the generator source set with the LWJGL 2 jar and [output] (relative to this project). */
fun JavaExec.generate(main: String, output: String) {
    group = "lich"
    val lwjgl2Jar: FileCollection = lwjgl2Classpath.get()
    val file = layout.projectDirectory.file(output).asFile
    classpath = generator.runtimeClasspath
    mainClass = "dev.lich.compat.lwjgl2.generator.$main"
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(lwjgl2Jar.singleFile.absolutePath, file.absolutePath)
    })
}

tasks.register<JavaExec>("generateRenames") {
    description = "Regenerates compat-lwjgl2's LWJGL 2 → 3 rename table from both LWJGL jars."
    generate("RenameGenerator", "src/main/resources/dev/lich/compat/lwjgl2/renames.txt")
}

// LWJGL 2's ContextCapabilities fields, filled from LWJGL 3's GLCapabilities:
// ./gradlew :compat-lwjgl2:generateCapabilities
tasks.register<JavaExec>("generateCapabilities") {
    description = "Regenerates compat-lwjgl2's ContextCapabilities from both LWJGL jars."
    generate("CapabilitiesGenerator", "src/main/java/org/lwjgl/opengl/ContextCapabilities.java")
}

// The linkage check: which LWJGL references in the game, Forge and mods still don't link once the call
// table has run. ./gradlew :compat-lwjgl2:checkLinkage ["-Plich.linkage=<jar or dir>;..."]; by default the
// game's jars in run/cache and the mod corpus in run/corpus.
val linkage = sourceSets.create("linkage")
dependencies {
    "linkageImplementation"(sourceSets.main.get().output)
    "linkageImplementation"(project(":transform"))
    "linkageImplementation"(libs.asm)
    "linkageImplementation"(libs.asm.tree)
    "linkageRuntimeOnly"(libs.lwjgl3)
    "linkageRuntimeOnly"(libs.lwjgl3.glfw)
    "linkageRuntimeOnly"(libs.lwjgl3.opengl)
    "linkageRuntimeOnly"(libs.lwjgl3.openal)
    // The optional modules Lich ships for mods written for LWJGL 3 (see the installer).
    "linkageRuntimeOnly"(libs.lwjgl3.stb)
    "linkageRuntimeOnly"(libs.lwjgl3.tinyfd)
    "linkageRuntimeOnly"(libs.lwjgl3.tinyexr)
    "linkageRuntimeOnly"(libs.lwjgl3.nanovg)
}

tasks.register<JavaExec>("checkLinkage") {
    group = "lich"
    description = "Lists the LWJGL 2 references in the game, Forge and mods that don't link on LWJGL 3 under Lich."
    val run = rootProject.layout.projectDirectory.dir("run")
    val defaults = listOf(
        "cache/versions/1.7.10/1.7.10.jar", "cache/versions/1.8.9/1.8.9.jar", "cache/versions/1.12.2/1.12.2.jar",
        "cache/libraries/net/minecraftforge", "cache/libraries/com/paulscode",
        "cache/libraries/org/lwjgl/lwjgl/lwjgl_util", "corpus",
    ).map { run.file(it).asFile.absolutePath }
    val inputs = providers.gradleProperty("lich.linkage").map { it.split(";") }.orElse(defaults)
    classpath = linkage.runtimeClasspath
    mainClass = "dev.lich.compat.lwjgl2.linkage.LinkageCheck"
    argumentProviders.add(CommandLineArgumentProvider { inputs.get() })
}

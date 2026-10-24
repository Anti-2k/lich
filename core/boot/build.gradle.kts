// dev.lich.boot.Main: checks the JVM flags, makes logging safe, picks the profile and starts Lich's
// LaunchWrapper. PLAN.md §3, §4.1.
plugins {
    id("lich.java")
}

dependencies {
    implementation(project(":launchwrapper"))
    // Provided by the game's own classpath at runtime.
    compileOnly(libs.log4j.api.legacy)

    testImplementation(libs.log4j.api.legacy)
    testImplementation(libs.log4j.core.legacy)
}

tasks.test {
    // The safe log4j config opens logs/latest.log relative to the working directory.
    val workDir = layout.buildDirectory.dir("test-work")
    workingDir(workDir)
    doFirst { workDir.get().asFile.mkdirs() }
    // JvmFlagsTest checks that these count as present and the rest of jvm-flags.txt as missing.
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
    )
}

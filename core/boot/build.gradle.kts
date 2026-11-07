// dev.lich.boot.Main: checks the JVM flags, makes logging safe, installs the compat pass and the
// system class loader, picks the profile and starts Lich's LaunchWrapper. DOCS.md §3, §4.1.
plugins {
    id("lich.java")
}

dependencies {
    implementation(project(":launchwrapper"))
    implementation(project(":transform"))
    implementation(project(":remap"))
    implementation(project(":loader"))
    implementation(project(":v1_7_10-compat-forge"))
    implementation(project(":v1_8_9-compat-forge"))
    implementation(project(":v1_12_2-compat-forge"))
    implementation(project(":compat-jdk"))
    implementation(project(":compat-asm"))
    implementation(project(":compat-lwjgl2"))
    implementation(project(":v1_7_10-compat-mc"))
    implementation(project(":v1_8_9-compat-mc"))
    implementation(project(":v1_12_2-compat-mc"))
    // Provided by the game's own classpath at runtime.
    compileOnly(libs.log4j.api.legacy)
    compileOnly(libs.log4j.core.legacy)

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

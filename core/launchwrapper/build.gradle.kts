// Lich's own implementation of net.minecraft.launchwrapper.* (LaunchWrapper 1.12's API), plus the
// dev.lich.launchwrapper entry point that boot uses. DOCS.md §4.2.
plugins {
    id("lich.java")
    id("lich.publish")
}

// Classes the tests load through LaunchClassLoader. Deliberately not on the test classpath, so the
// tests can tell a class LaunchClassLoader defined itself from one it delegated.
val fixtures = sourceSets.create("fixtures")

dependencies {
    // Provided by the game's own classpath at runtime (log4j 2.0-beta9, jopt-simple 4.5/4.6).
    compileOnly(libs.log4j.api.legacy)
    compileOnly(libs.jopt.simple)

    testImplementation(libs.log4j.api.legacy)
    testRuntimeOnly(libs.log4j.core.legacy)
    testImplementation(libs.jopt.simple)
    testImplementation(libs.asm)
}

tasks.test {
    val fixtureDirs = fixtures.output.classesDirs
    inputs.files(fixtureDirs).withPropertyName("fixtures")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dlich.test.fixtures=${fixtureDirs.asPath}") })
}

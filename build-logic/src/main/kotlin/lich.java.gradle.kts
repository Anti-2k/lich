// Shared setup for every Lich module: Java 25, UTF-8, all lint warnings on, JUnit 5, and jars named
// lich-<module> so they're recognisable in a launcher's libraries folder.
// Modules that must stay Java 8 (the Forge test mod) will get their own convention when they appear.
plugins {
    `java-library`
}

val libs = the<VersionCatalogsExtension>().named("libs")

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

base {
    archivesName = "lich-${project.name}"
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<Jar>().configureEach {
    manifest.attributes(
        "Implementation-Title" to "lich-${project.name}",
        "Implementation-Version" to project.version.toString(),
    )
}

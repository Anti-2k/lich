// Shared setup for every Lich module: Java 25, UTF-8, all lint warnings on.
// Modules that must stay Java 8 (the Forge test mod) will get their own convention when they appear.
plugins {
    `java-library`
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
}

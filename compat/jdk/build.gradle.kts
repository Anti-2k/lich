// Shims for removed JDK APIs and their redirect table (PLAN.md §2.3, §4.3, §4.4).
plugins {
    id("lich.java")
}

dependencies {
    implementation(project(":transform"))
    // Only so tests can pack a jar to unpack; at runtime the nested copy below is used.
    testImplementation(libs.commons.compress)
}

// commons-compress and its dependencies travel inside this jar under META-INF/lich/pack200/ and are
// loaded by an isolated class loader (Pack200.java), so they never clash with the game's commons-io
// or commons-lang3. The index lists the nested jars, because a class loader can't list resources.
val pack200 = configurations.dependencyScope("pack200")
val pack200Classpath = configurations.resolvable("pack200Classpath") {
    extendsFrom(pack200.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
dependencies {
    add("pack200", libs.commons.compress)
}

val nestPack200 = tasks.register<Sync>("nestPack200") {
    val jars: FileCollection = pack200Classpath.get()
    val outputDir = layout.buildDirectory.dir("generated/pack200")
    val index = jars.elements.map { files -> files.joinToString("\n", postfix = "\n") { it.asFile.name } }
    inputs.property("index", index)
    from(jars) { into("META-INF/lich/pack200") }
    into(outputDir)
    doLast {
        outputDir.get().file("META-INF/lich/pack200/index.txt").asFile.writeText(index.get())
    }
}
sourceSets.main {
    resources.srcDir(nestPack200)
}

// UnsafeFields uses jdk.internal.misc.Unsafe, which the game gets through jvm-flags.txt. javac refuses
// --add-exports together with --release, so this module compiles against the JDK 25 toolchain directly.
tasks.compileJava {
    options.release.unset()
    options.compilerArgs.addAll(listOf("--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED"))
}
tasks.test {
    jvmArgs("--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED")
}

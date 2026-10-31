// ASM 5 compatibility on the ASM 9 that Lich ships instead of Forge's asm-all 5.0.3. DOCS.md §2.4.
// - org/objectweb/asm/**: classes ASM 9 removed but FML still uses (RemappingClassAdapter and friends,
//   in FML's DeobfuscationTransformer), copied from ASM 5.0.3 (BSD-3-Clause, see META-INF/LICENSE-ASM.txt).
//   Keeping ASM 5's exact behavior, e.g. LocalVariablesSorter's local renumbering, keeps FML's output
//   identical for coremods. The one change: their default API level is ASM9 (marked "Lich:"), so they
//   accept Java 11+ class files.
// - dev/lich/compat/asm/**: redirects for ASM 5 behavior that ASM 9 changed.
plugins {
    id("lich.java")
}

dependencies {
    implementation(project(":transform"))
    compileOnly(libs.asm)
    compileOnly(libs.asm.commons)
    compileOnly(libs.asm.tree)
    compileOnly(libs.asm.analysis)
    compileOnly(libs.asm.util)
    testImplementation(libs.asm)
    testImplementation(libs.asm.util)
}

tasks.compileJava {
    // For the third-party code kept verbatim: its deprecation and raw-type warnings aren't ours to fix.
    options.compilerArgs.addAll(listOf("-Xlint:none", "-Xlint:-dangling-doc-comments"))
}

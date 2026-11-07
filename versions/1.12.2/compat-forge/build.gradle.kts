// Changes to Forge 1.12.2's own code (FML), as compat-pass rules. The Java 25 fixes FML needs are generic and
// live in compat-jdk and compat-asm; what's here is how Lich and FML share a game: FML's mod discovery
// leaves Lich mods alone (DOCS.md §5.1).
plugins {
    id("lich.java")
}

dependencies {
    implementation(project(":transform"))
    testImplementation(libs.asm)
    testImplementation(libs.asm.tree)
}

// Lich's compat pass: renames of removed JDK types, call redirects and per-class patches, applied to
// every class Lich defines (DOCS.md §4.3). The redirect table itself lives with the shims it points at.
plugins {
    id("lich.java")
    id("lich.publish")
}

dependencies {
    // On the game's classpath at runtime (the installer ships them; Forge's asm-all 5 is dropped).
    api(libs.asm)
    api(libs.asm.tree)
    implementation(libs.asm.commons)
}

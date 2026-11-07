// Fixes to Minecraft 1.12.2 itself, as ClassPatches written against SRG names, applied after FML (or Lich's
// remapper) has deobfuscated the class. DOCS.md §3.2.
plugins {
    id("lich.java")
}

dependencies {
    implementation(project(":transform"))
    implementation(project(":shared-compat-mc"))
    testImplementation(libs.asm)
    testImplementation(libs.asm.tree)
}

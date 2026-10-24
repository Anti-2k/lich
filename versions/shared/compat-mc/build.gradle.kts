// Fixes to Minecraft that are the same in 1.7.10 and 1.8.9 (same code, same SRG names). The version
// modules register the ones that apply to them. ClassPatches until Lich has Mixin (Phase 4).
plugins {
    id("lich.java")
}

dependencies {
    implementation(project(":transform"))
    testImplementation(libs.asm)
    testImplementation(libs.asm.tree)
}

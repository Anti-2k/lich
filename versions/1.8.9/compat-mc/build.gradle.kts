// Fixes to Minecraft 1.8.9 itself. Until Lich has Mixin (Phase 4) these are ClassPatches written
// against SRG names, applied after FML has patched and deobfuscated the class, so for now they only
// run in the Forge profile. PLAN.md §3.2.
plugins {
    id("lich.java")
}

dependencies {
    implementation(project(":transform"))
    implementation(project(":shared-compat-mc"))
}

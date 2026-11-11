// The Mixin that Lich mods run on (Fabric's fork of Mixin 0.8.7, plus MixinExtras), started only when Lich
// mods are installed: without them the game keeps whatever Mixin Forge mods ship (DOCS.md §2.4, §5.2).
plugins {
    id("lich.java")
    id("lich.publish")
}

dependencies {
    // Shipped as their own libraries (the installer adds them), so a launch without Lich mods can leave
    // them off the class path.
    compileOnly(libs.mixin)
    compileOnly(libs.mixinextras)
}

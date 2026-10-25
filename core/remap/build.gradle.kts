// obf -> SRG runtime remapper for the vanilla profile, so the game runs with SRG names with and without
// Forge (PLAN.md §3.1). The mappings are MCP's mcp-<mc>-srg.zip, which the installer adds as a library.
plugins {
    id("lich.java")
}

dependencies {
    api("dev.lich:remap-core")
    implementation(project(":launchwrapper"))
    // Provided by the game's own classpath at runtime.
    compileOnly(libs.log4j.api.legacy)

    testImplementation(libs.asm.tree)
}

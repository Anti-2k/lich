// Mapping files (SRG, MCP's CSVs) and a remapper that resolves members through the class hierarchy.
// Used at build time by the dev.lich.mod plugin and at runtime by Lich's vanilla-profile remapper
// (core/remap), so it ships in Lich and only depends on ASM, which Lich ships too.
plugins {
    id("lich.java")
}

dependencies {
    api(libs.asm)
    api(libs.asm.commons)
}

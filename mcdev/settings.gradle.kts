// Minecraft development tooling, its own build so both Lich's build and Lich mods' builds can use it:
// remap-core (mapping files and a hierarchy-aware ASM remapper, also shipped in Lich's runtime for the
// vanilla profile's obf -> SRG remap) and the dev.lich.mod Gradle plugin (a vanilla Minecraft with MCP
// names to compile against, Mixin refmaps, and reobfuscation to SRG). DOCS.md §3.1, §5.
pluginManagement {
    includeBuild("../build-logic")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "mcdev"

include("remap-core")
include("gradle-plugin")

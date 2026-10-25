pluginManagement {
    includeBuild("build-logic")
    // The dev.lich.mod plugin, which builds the per-version API modules (Minecraft with MCP names, reobf to SRG).
    includeBuild("mcdev")
}

// remap-core (mappings and the hierarchy-aware remapper), which the runtime ships for the vanilla profile.
includeBuild("mcdev")

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // The game's LWJGL 2, which compat-lwjgl2's rename table is generated from.
        maven("https://libraries.minecraft.net") {
            content { includeGroup("org.lwjgl.lwjgl") }
        }
        // MCP's SRG mappings, which the vanilla profile remaps the game with (installed next to Lich, never shipped).
        maven("https://maven.minecraftforge.net") {
            content { includeGroup("de.oceanlabs.mcp") }
            // MCP's zips have no POM.
            metadataSources { artifact() }
        }
        // Fabric's Mixin fork, the Mixin Lich mods run on (mixin-provider).
        maven("https://maven.fabricmc.net") {
            content { includeGroup("net.fabricmc") }
        }
    }
}

rootProject.name = "lich"

// Flat project names with nested directories: the per-version modules would otherwise all be called
// "compat-mc" etc., which clashes in Gradle's dependency coordinates and in IDEs.
fun module(name: String, dir: String) {
    include(name)
    project(":$name").projectDir = file(dir)
}

module("boot", "core/boot")
module("launchwrapper", "core/launchwrapper")
module("transform", "core/transform")
module("remap", "core/remap")
module("loader", "core/loader")
module("mixin-provider", "core/mixin-provider")

module("compat-jdk", "compat/jdk")
module("compat-asm", "compat/asm")
module("compat-lwjgl2", "compat/lwjgl2")

module("api", "api")

// Fixes that are identical in 1.7.10 and 1.8.9; each version module registers the ones it needs.
module("shared-compat-mc", "versions/shared/compat-mc")
for (mc in listOf("1.7.10", "1.8.9")) {
    val prefix = "v" + mc.replace('.', '_')
    for (part in listOf("compat-mc", "compat-forge", "api")) {
        module("$prefix-$part", "versions/$mc/$part")
    }
}

// First-party Lich mods: optional, installed like any other mod, never part of the runtime.
module("perf", "mods/perf")

module("devlaunch", "tools/devlaunch")
module("installer", "tools/installer")

module("testmod", "test/testmod")
module("harness", "test/harness")

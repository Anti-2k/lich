// Lich's API for Minecraft 1.8.9: the events that carry game types, KeyBindings, and the hooks (Mixins on the
// game, woven only for hooks a loaded mod asks for) that fire them, with and without Forge (PLAN.md §5.2).
// Built against vanilla 1.8.9 with MCP names and reobfuscated to SRG by the dev.lich.mod plugin.
plugins {
    id("lich.java")
    id("dev.lich.mod")
}

lich {
    minecraft("1.8.9")
    mixinRefmap("lich.hooks.refmap.json")
}

// The few Forge classes the Forge-only hooks name (GuiIngameForge, GuiUtils, ...), as compile-time stubs:
// Lich never depends on Forge, and the Mixins only apply when Forge is there.
val forgeStubs = sourceSets.create("forgeStubs")

// Events whose source is the same for 1.7.10 and 1.8.9, compiled against each version's game.
sourceSets.main {
    java.srcDir("../../shared/api/src/main/java")
}

dependencies {
    api(project(":api"))
    implementation(project(":loader"))
    compileOnly(forgeStubs.output)
    "forgeStubsCompileOnly"(lich.minecraftClasspath)
}

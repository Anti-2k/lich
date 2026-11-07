// Lich's API for Minecraft 1.12.2: the events that carry game types, KeyBindings, and the hooks (Mixins on the
// game, woven only for hooks a loaded mod asks for) that fire them, with and without Forge (DOCS.md §5.2).
// Built against vanilla 1.12.2 with MCP names and reobfuscated to SRG by the dev.lich.mod plugin.
plugins {
    id("lich.java")
    id("dev.lich.mod")
}

lich {
    minecraft("1.12.2")
    mixinRefmap("lich.hooks.refmap.json")
}

// The few Forge classes the Forge-only hooks name (GuiIngameForge, GuiUtils, ...), as compile-time stubs:
// Lich never depends on Forge, and the Mixins only apply when Forge is there.
val forgeStubs = sourceSets.create("forgeStubs")

// Events whose source is the same for 1.7.10, 1.8.9 and 1.12.2, compiled against each version's game. A few of them
// carry types 1.12 renamed (chat components, ray traces) or a field it moved, so this version has its own copies of
// those, and the shared ones are staged without them (a source set can't tell two files of the same name apart).
val ownEvents = listOf("ChatEvents.java", "WorldRenderEvents.java", "KeyBindings.java")
val stageShared = tasks.register<Sync>("stageSharedApi") {
    from(rootProject.layout.projectDirectory.dir("versions/shared/api/src/main/java")) { exclude(ownEvents.map { "**/$it" }) }
    into(layout.buildDirectory.dir("generated/shared-api"))
}
sourceSets.main {
    java.srcDir(stageShared)
}

dependencies {
    api(project(":api"))
    implementation(project(":loader"))
    compileOnly(forgeStubs.output)
    "forgeStubsCompileOnly"(lich.minecraftClasspath)
}

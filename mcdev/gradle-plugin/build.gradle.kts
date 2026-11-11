// The dev.lich.mod Gradle plugin: what a Lich mod (and Lich's own per-version API modules) needs to build
// against Minecraft without Forge. DOCS.md §5, §9.
plugins {
    id("lich.java")
    `java-gradle-plugin`
}

dependencies {
    implementation(project(":remap-core"))
    implementation(libs.gson)
    implementation(libs.asm.tree)
}

gradlePlugin {
    plugins {
        create("lichMod") {
            id = "dev.lich.mod"
            implementationClass = "dev.lich.mcdev.LichModPlugin"
        }
    }
}

// The library versions the plugin hands to mods (Mixin, MixinExtras, ASM for Mixin's annotation processor),
// from Lich's version catalog, so a mod compiles against exactly what Lich ships.
tasks.processResources {
    val versions = mapOf(
        "mixin" to libs.versions.mixin.get(),
        "mixinextras" to libs.versions.mixinextras.get(),
        "asm" to libs.versions.asm.get(),
        "gson" to libs.versions.gson.asProvider().get(),
    )
    inputs.properties(versions)
    filesMatching("dev/lich/mcdev/versions.properties") {
        expand(versions)
    }
}

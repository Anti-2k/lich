// Lich's mod loader: discovery, lich.mod.json, dependency resolution, Mixin configs, access wideners
// (PLAN.md §5.1). Runs in the system class loader; the API reaches it through a class loader exclusion.
plugins {
    id("lich.java")
}

dependencies {
    implementation(project(":launchwrapper"))
    implementation(project(":mixin-provider"))
    api("dev.lich:remap-core")
    // The game's own Gson and log4j, from its class path at runtime.
    compileOnly(libs.gson.legacy)
    compileOnly(libs.log4j.api.legacy)

    testImplementation(libs.gson.legacy)
}

// ./gradlew publishMaven [-Plich.mavenRepo=<dir>]: every module mods build against (the API, what it pulls in, and the
// dev.lich.mod plugin from mcdev) into one Maven repository, by default build/maven. release.yml runs it into the
// maven branch, which GitHub Pages serves (lich.publish convention).
tasks.register("publishMaven") {
    group = "publishing"
    description = "Publishes the API, its dependencies and the dev.lich.mod plugin to Lich's Maven repository."
    dependsOn(
        listOf("api", "loader", "launchwrapper", "mixin-provider", "transform", "compat-lwjgl2",
            "v1_7_10-api", "v1_8_9-api", "v1_12_2-api")
            .map { ":$it:publishAllPublicationsToLichRepository" })
    dependsOn(listOf("remap-core", "gradle-plugin")
        .map { gradle.includedBuild("mcdev").task(":$it:publishAllPublicationsToLichRepository") })
}

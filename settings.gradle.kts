pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
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
module("perf", "core/perf")

module("compat-jdk", "compat/jdk")
module("compat-asm", "compat/asm")
module("compat-lwjgl2", "compat/lwjgl2")

module("api", "api")

// Fixes that are identical in 1.7.10 and 1.8.9; each version module registers the ones it needs.
module("shared-compat-mc", "versions/shared/compat-mc")
for (mc in listOf("1.7.10", "1.8.9")) {
    val prefix = "v" + mc.replace('.', '_')
    for (part in listOf("compat-mc", "compat-forge", "api-impl")) {
        module("$prefix-$part", "versions/$mc/$part")
    }
}

module("devlaunch", "tools/devlaunch")
module("installer", "tools/installer")

module("testmod", "test/testmod")
module("harness", "test/harness")

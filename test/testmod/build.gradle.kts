// A small Lich mod for 1.8.9 that touches every part of the loader: entrypoints, hooks, its own Mixin config
// and an access widener. Each part logs one "Lich testmod:" line, so a launch log shows what works
// (PLAN.md §7). Install it with :testmod:installDev (into run/lich-1.8.9/mods and run/lich-forge-1.8.9/mods).
// The Forge-mod half of the plan's test mod comes later, with the harness.
plugins {
    id("lich.java")
    id("dev.lich.mod")
}

lich {
    minecraft("1.8.9")
    mixinRefmap("mixins.lichtest.refmap.json")
    accessWidener("lichtest.aw")
}

dependencies {
    compileOnly(project(":v1_8_9-api"))
}

tasks.register<Copy>("installDev") {
    group = "lich"
    description = "Copies the test mod into the mods folders of devlaunch's Lich 1.8.9 profiles."
    val run = rootProject.layout.projectDirectory.dir("run")
    from(tasks.named("reobfJar"))
    into(run.dir("lich-1.8.9/mods"))
    doLast {
        // Copy can only have one destination; the Forge profile's folder gets the same jar.
        val jar = destinationDir.listFiles { file -> file.name.startsWith("lich-testmod") }!!.single()
        val forgeMods = run.dir("lich-forge-1.8.9/mods").asFile
        forgeMods.mkdirs()
        jar.copyTo(forgeMods.resolve(jar.name), overwrite = true)
    }
}

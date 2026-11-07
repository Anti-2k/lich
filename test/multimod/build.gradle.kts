// A small Lich mod built for 1.7.10, 1.8.9 and 1.12.2 as one jar, the dev.lich.mod plugin's multi-version mode
// (DOCS.md §5.1): shared code in src/main, and per version a Mixin, an access widener and an entrypoint in
// src/mc<version>. Each part logs one "Lich multimod:" line. Building it checks that the shared classes come
// out the same on both versions; :multimod:installDev copies the jar into devlaunch's Lich profiles.
plugins {
    id("lich.java")
    id("dev.lich.mod")
}

lich {
    minecraft("1.7.10", "1.8.9", "1.12.2")
    forMinecraft("1.7.10") {
        mixinRefmap("mixins.lichmulti.1_7_10.refmap.json")
        accessWidener("lichmulti.1_7_10.aw")
    }
    forMinecraft("1.8.9") {
        mixinRefmap("mixins.lichmulti.1_8_9.refmap.json")
        accessWidener("lichmulti.1_8_9.aw")
    }
    forMinecraft("1.12.2") {
        mixinRefmap("mixins.lichmulti.1_12_2.refmap.json")
        accessWidener("lichmulti.1_12_2.aw")
    }
}

dependencies {
    compileOnly(project(":api"))
    "mc1_7_10CompileOnly"(project(":v1_7_10-api"))
    "mc1_8_9CompileOnly"(project(":v1_8_9-api"))
    "mc1_12_2CompileOnly"(project(":v1_12_2-api"))
}

tasks.register<Copy>("installDev") {
    group = "lich"
    description = "Copies the multi-version test mod into the mods folders of devlaunch's Lich profiles."
    val run = rootProject.layout.projectDirectory.dir("run")
    from(tasks.named("reobfJar"))
    into(run.dir("lich-1.8.9/mods"))
    doLast {
        // Copy can only have one destination; the other profiles' folders get the same jar.
        val jar = destinationDir.listFiles { file -> file.name.startsWith("lich-multimod") }!!.single()
        for (profile in listOf("lich-forge-1.8.9", "lich-1.7.10", "lich-forge-1.7.10", "lich-1.12.2", "lich-forge-1.12.2")) {
            val mods = run.dir("$profile/mods").asFile
            mods.mkdirs()
            jar.copyTo(mods.resolve(jar.name), overwrite = true)
        }
    }
}

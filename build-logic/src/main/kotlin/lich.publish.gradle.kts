// Publishes a module to Lich's Maven repository, so a mod can build against a Lich release instead of a checkout:
// the API, what it pulls in, and the dev.lich.mod plugin (DOCS.md §5). release.yml points -Plich.mavenRepo at a
// checkout of the repository's maven branch, which GitHub Pages serves at https://anti-2k.github.io/lich/maven/.
// Without the property, publishing goes to build/maven of the build's root (mcdev sets ../build/maven), to look at.
// Apply it after the module's other plugins: a java-gradle-plugin module publishes through that plugin's own
// publications (the plugin and its marker), the others through one made here.
plugins {
    `java-library`
    `maven-publish`
}

java {
    withSourcesJar()
}

publishing {
    publications {
        if (!plugins.hasPlugin("java-gradle-plugin")) {
            create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
        withType<MavenPublication>().configureEach {
            // Dependencies declared without a version (dev.lich:remap-core, substituted by the composite build) get
            // the version they resolved to.
            versionMapping {
                allVariants {
                    fromResolutionResult()
                }
            }
        }
    }
    repositories {
        maven {
            name = "lich"
            url = uri(rootDir.resolve(providers.gradleProperty("lich.mavenRepo").getOrElse("build/maven")))
        }
    }
}

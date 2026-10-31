package dev.lich.mcdev;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.tasks.Jar;
import org.gradle.process.CommandLineArgumentProvider;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The {@code lich} block of a build using {@code dev.lich.mod}. For one Minecraft version:
 * <pre>
 * lich {
 *     minecraft("1.8.9")                          // vanilla 1.8.9 with MCP names on the compile classpath
 *     mixinRefmap("mixins.examplemod.refmap.json") // Mixin's annotation processor writes this refmap
 *     accessWidener("examplemod.aw")              // resource path of an access widener with MCP names
 * }
 * </pre>
 * For a jar that runs on several (DOCS.md §5.1, {@code "minecraft": ["1.7.10", "1.8.9"]}):
 * <pre>
 * lich {
 *     minecraft("1.7.10", "1.8.9")
 *     forMinecraft("1.7.10") {
 *         mixinRefmap("mixins.examplemod.1_7_10.refmap.json")
 *         accessWidener("examplemod.1_7_10.aw")    // in src/mc1_7_10/resources
 *     }
 * }
 * dependencies {
 *     "mc1_7_10CompileOnly"(...)                  // what only one version's code compiles against
 * }
 * </pre>
 * There, {@code src/main} holds the code every version shares. It is compiled against each version and
 * reobfuscated with each version's mappings, and the results must be byte for byte the same, so a shared
 * class can't silently link to something that differs between versions. Each version also gets a source set
 * of its own, {@code mc1_7_10} etc. ({@code src/mc1_7_10/java}, {@code /resources}), compiled against that
 * version and the shared code, for its Mixins, access widener and whatever else differs; its classes need a
 * package of their own. {@code reobfJar} merges the versions into the one jar the game loads.
 * <p>
 * The build also needs Maven Central and {@code https://maven.fabricmc.net} (Mixin) as repositories.
 */
public abstract class LichExtension {
    private static final Properties VERSIONS = versions();
    private static final String MIXIN = "net.fabricmc:sponge-mixin:" + VERSIONS.getProperty("mixin");
    private static final String MIXIN_EXTRAS = "io.github.llamalad7:mixinextras-common:" + VERSIONS.getProperty("mixinextras");

    private final Project project;
    /** The versions the mod is built for, in the order given; the first is the one {@code src/main} compiles against in the IDE. */
    private final Map<String, Target> targets = new LinkedHashMap<>();

    @Inject
    public LichExtension(Project project) {
        this.project = project;
    }

    /**
     * Puts Minecraft (vanilla, MCP names) and its libraries on the compile classpath: one version, or several
     * for a multi-version jar (see the class comment).
     */
    public void minecraft(String... versions) {
        if (!targets.isEmpty()) {
            throw new GradleException("lich.minecraft(...) can only be set once; it is " + targets.keySet());
        }
        if (versions.length == 0) {
            throw new GradleException("lich.minecraft(...) needs at least one Minecraft version");
        }
        if (new LinkedHashSet<>(List.of(versions)).size() != versions.length) {
            throw new GradleException("lich.minecraft(...) lists a version twice: " + List.of(versions));
        }
        project.getDependencies().add("compileOnly", MIXIN);
        project.getDependencies().add("compileOnly", MIXIN_EXTRAS);
        if (versions.length == 1) {
            single(versions[0]);
        } else {
            multi(versions);
        }
    }

    /** Configures one version of a multi-version build: its refmap and access widener. */
    public void forMinecraft(String version, Action<? super VersionSpec> action) {
        if (targets.size() < 2) {
            throw new GradleException("lich.forMinecraft(...) is for builds with several versions, lich.minecraft(\"1.7.10\", \"1.8.9\")"
                    + (targets.isEmpty() ? ", which comes first" : "; with one, set mixinRefmap and accessWidener in lich { } itself"));
        }
        Target target = targets.get(version);
        if (target == null) {
            throw new GradleException("lich.forMinecraft(\"" + version + "\"): the build is for " + targets.keySet());
        }
        action.execute(new VersionSpec(target));
    }

    /**
     * Runs Mixin's annotation processor, which checks the mod's Mixins against Minecraft and writes the
     * refmap {@code fileName} (MCP → SRG names of their targets) into the jar.
     */
    public void mixinRefmap(String fileName) {
        onlyTarget("mixinRefmap").mixinRefmap(fileName);
    }

    /** An access widener in the mod's resources (MCP names), applied to the compile classpath and reobfuscated with the jar. */
    public void accessWidener(String resourcePath) {
        onlyTarget("accessWidener").accessWidener = resourcePath;
    }

    /** Minecraft (widened) and its libraries, for source sets other than {@code main} that compile against it. */
    public FileCollection getMinecraftClasspath() {
        return onlyTarget("minecraftClasspath").classpath;
    }

    /** The same for one version of a multi-version build. */
    public FileCollection minecraftClasspath(String version) {
        Target target = targets.get(version);
        if (target == null) {
            throw new GradleException("lich.minecraftClasspath(\"" + version + "\"): the build is for " + targets.keySet());
        }
        return target.classpath;
    }

    /** What {@link #forMinecraft} configures: the parts of the build that belong to one Minecraft version. */
    public static final class VersionSpec {
        private final Target target;

        VersionSpec(Target target) {
            this.target = target;
        }

        /** Mixin's refmap for this version's Mixins (in {@code src/mc<version>/java}); name it after the version. */
        public void mixinRefmap(String fileName) {
            target.mixinRefmap(fileName);
        }

        /** This version's access widener, in {@code src/mc<version>/resources}, with this version's MCP names. */
        public void accessWidener(String resourcePath) {
            target.accessWidener = resourcePath;
        }

        public FileCollection getMinecraftClasspath() {
            return target.classpath;
        }
    }

    private Target onlyTarget(String what) {
        if (targets.isEmpty()) {
            throw new GradleException("Call lich.minecraft(\"<version>\") first");
        }
        if (targets.size() > 1) {
            throw new GradleException("With several Minecraft versions, set " + what + " per version: lich.forMinecraft(\""
                    + targets.keySet().iterator().next() + "\") { " + what + "(...) }");
        }
        return targets.values().iterator().next();
    }

    /** One version: {@code src/main} against it; jar → the dev jar (classifier dev), reobfJar → the SRG jar. */
    private void single(String version) {
        SourceSet main = sourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        Target target = new Target(version, main, "", false);
        project.getDependencies().add("compileOnly", target.classpath);

        TaskProvider<Jar> jar = project.getTasks().named("jar", Jar.class, task -> task.getArchiveClassifier().set("dev"));
        target.reobf = target.registerReobf("reobfJar", jar.flatMap(Jar::getArchiveFile),
                base().getArchivesName().map(name -> "libs/" + name + "-" + project.getVersion() + ".jar"));
        target.reobf.configure(task -> {
            task.setGroup("build");
            task.setDescription("Renames MCP names in the dev jar to the SRG names the game runs with.");
        });
        publish(target.reobf.flatMap(ReobfJar::getOutput), target.reobf);
    }

    /**
     * Several versions. Per version {@code <v>}: source set {@code mc<v>} (its compile configurations extend
     * {@code main}'s), {@code src/main} compiled against it (for the first version that's {@code compileJava}
     * itself, so the IDE sees one consistent classpath), a dev jar of both, and its reobfuscation. Then
     * {@code reobfJar} merges the reobfuscated jars, checking that what they share is identical.
     */
    private void multi(String[] versions) {
        SourceSetContainer sourceSets = sourceSets();
        SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        ConfigurationContainer configurations = project.getConfigurations();
        TaskProvider<Jar> jar = project.getTasks().named("jar", Jar.class, task -> task.getArchiveClassifier().set("dev"));
        TaskProvider<Task> resources = project.getTasks().named(main.getProcessResourcesTaskName());
        TaskProvider<JavaCompile> compileJava = project.getTasks().named(main.getCompileJavaTaskName(), JavaCompile.class);
        Provider<String> baseName = base().getArchivesName().map(name -> name + "-" + project.getVersion());

        for (int i = 0; i < versions.length; i++) {
            String version = versions[i];
            String suffix = version.replace('.', '_');
            SourceSet own = sourceSets.create("mc" + suffix);
            Configuration compileOnly = configurations.getByName(own.getCompileOnlyConfigurationName());
            Configuration implementation = configurations.getByName(own.getImplementationConfigurationName());
            compileOnly.extendsFrom(configurations.getByName(main.getCompileOnlyConfigurationName()));
            implementation.extendsFrom(configurations.getByName(main.getImplementationConfigurationName()));
            Target target = new Target(version, own, suffix, true);
            project.getDependencies().add(own.getCompileOnlyConfigurationName(), target.classpath);

            FileCollection shared;
            if (i == 0) {
                configurations.getByName(main.getCompileClasspathConfigurationName()).extendsFrom(compileOnly, implementation);
                shared = main.getOutput().getClassesDirs();
            } else {
                TaskProvider<JavaCompile> compile = project.getTasks().register("compileJavaFor" + suffix, JavaCompile.class, task -> {
                    task.setDescription("Compiles src/main against Minecraft " + version + ", the classes that version's jar shares.");
                    task.setSource(main.getJava());
                    task.setClasspath(configurations.getByName(own.getCompileClasspathConfigurationName()));
                    task.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("lich/classes/main-" + version));
                    task.getOptions().getRelease().convention(compileJava.flatMap(mainCompile -> mainCompile.getOptions().getRelease()));
                    // Mixin's processor is on the classpath (compileOnly) but has no business here: shared code has no Mixins.
                    task.getOptions().setAnnotationProcessorPath(project.files());
                });
                shared = project.files(compile.flatMap(JavaCompile::getDestinationDirectory));
            }
            own.setCompileClasspath(own.getCompileClasspath().plus(shared));

            TaskProvider<Jar> devJar = project.getTasks().register("devJar" + suffix, Jar.class, task -> {
                task.setDescription("The mod as it is on Minecraft " + version + ", with MCP names.");
                task.from(shared, resources, own.getOutput());
                task.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("lich"));
                task.getArchiveFileName().set(baseName.map(name -> name + "-dev-" + version + ".jar"));
                task.getManifest().from(jar.get().getManifest());
            });
            target.reobf = target.registerReobf("reobfJar" + suffix, devJar.flatMap(Jar::getArchiveFile),
                    baseName.map(name -> "lich/" + name + "-" + version + ".jar"));
            target.reobf.configure(task -> task.setDescription("Renames MCP names in Minecraft " + version
                    + "'s dev jar to its SRG names."));
        }

        TaskProvider<MergeJars> merge = project.getTasks().register("reobfJar", MergeJars.class, task -> {
            task.setGroup("build");
            task.setDescription("Merges the reobfuscated jars of every Minecraft version into the jar the game loads.");
            for (Target target : targets.values()) {
                task.getJars().from(target.reobf.flatMap(ReobfJar::getOutput));
                task.getVersions().add(target.version);
            }
            task.getSharedClasses().from(main.getOutput().getClassesDirs());
            task.getOutput().set(project.getLayout().getBuildDirectory().file(baseName.map(name -> "libs/" + name + ".jar")));
        });
        publish(merge.flatMap(MergeJars::getOutput), merge);
    }

    /** {@code task}'s {@code file} is what {@code assemble} builds and what other projects get when they depend on this one. */
    private void publish(Provider<RegularFile> file, TaskProvider<? extends Task> task) {
        project.getTasks().named("assemble", assemble -> assemble.dependsOn(task));
        for (String name : List.of("apiElements", "runtimeElements")) {
            Configuration elements = project.getConfigurations().getByName(name);
            elements.getOutgoing().getArtifacts().clear();
            elements.getOutgoing().artifact(file, artifact -> artifact.builtBy(task));
        }
    }

    private SourceSetContainer sourceSets() {
        return project.getExtensions().getByType(SourceSetContainer.class);
    }

    private BasePluginExtension base() {
        return project.getExtensions().getByType(BasePluginExtension.class);
    }

    /** One Minecraft version the mod is built for: its Minecraft, the source set with its Mixins, its reobfuscation. */
    private final class Target {
        final String version;
        final MinecraftSetup.Result minecraft;
        final SourceSet sourceSet;
        /** Appended to task and file names: empty for a single-version build, {@code 1_8_9} etc. otherwise. */
        final String suffix;
        final boolean multi;
        /** Widened Minecraft and its libraries. */
        final FileCollection classpath;
        String accessWidener;
        TaskProvider<ReobfJar> reobf;

        Target(String version, SourceSet sourceSet, String suffix, boolean multi) {
            this.version = version;
            this.sourceSet = sourceSet;
            this.suffix = suffix;
            this.multi = multi;
            Path cache = project.getGradle().getGradleUserHomeDir().toPath().resolve("caches/lich-mcdev");
            try {
                minecraft = MinecraftSetup.prepare(version, cache);
            } catch (IOException e) {
                throw new UncheckedIOException("Couldn't set up Minecraft " + version, e);
            }
            targets.put(version, this);

            TaskProvider<WidenJar> widen = project.getTasks().register("widenMinecraft" + suffix, WidenJar.class, task -> {
                task.setDescription("Applies the mod's access widener to the Minecraft " + version + " jar it compiles against.");
                task.getInput().set(minecraft.mcpJar().toFile());
                task.getAccessWidener().from(project.provider(() -> accessWidenerFile() == null ? List.of() : List.of(accessWidenerFile())));
                task.getOutput().set(project.getLayout().getBuildDirectory().file("lich/minecraft-" + version + "-mcp-widened.jar"));
            });
            ConfigurableFileCollection files = project.files(widen.flatMap(WidenJar::getOutput));
            for (Path library : minecraft.libraries()) {
                files.from(library.toFile());
            }
            classpath = files;
        }

        TaskProvider<ReobfJar> registerReobf(String name, Provider<RegularFile> input, Provider<String> output) {
            File mcpJar = minecraft.mcpJar().toFile();
            File mcpToSrg = minecraft.mcpToSrg().toFile();
            return project.getTasks().register(name, ReobfJar.class, task -> {
                task.getInput().set(input);
                task.getMinecraft().from(mcpJar);
                task.getMappings().set(mcpToSrg);
                task.getAccessWidener().set(project.provider(() -> accessWidener));
                task.getOutput().set(project.getLayout().getBuildDirectory().file(output));
            });
        }

        void mixinRefmap(String fileName) {
            // MixinExtras' processor registers its injectors (@WrapOperation, ...) with Mixin's, for their refmap entries.
            String asm = VERSIONS.getProperty("asm");
            for (String processor : List.of(MIXIN, MIXIN_EXTRAS, "com.google.code.gson:gson:" + VERSIONS.getProperty("gson"),
                    "org.ow2.asm:asm:" + asm, "org.ow2.asm:asm-tree:" + asm, "org.ow2.asm:asm-commons:" + asm,
                    "org.ow2.asm:asm-util:" + asm, "org.ow2.asm:asm-analysis:" + asm)) {
                project.getDependencies().add(sourceSet.getAnnotationProcessorConfigurationName(), processor);
            }
            File mcpToSrg = minecraft.mcpToSrg().toFile();
            Provider<RegularFile> mixinSrg = project.getLayout().getBuildDirectory()
                    .file(multi ? "lich/mixin-" + version + ".srg" : "lich/mixin.srg");
            project.getTasks().named(sourceSet.getCompileJavaTaskName(), JavaCompile.class, compile -> {
                File refmap = new File(compile.getDestinationDirectory().get().getAsFile(), fileName);
                File srg = mixinSrg.get().getAsFile();
                compile.getOutputs().file(srg).optional().withPropertyName("mixinSrg");
                compile.getOptions().getCompilerArgumentProviders().add(new MixinArguments(mcpToSrg, refmap, srg));
                compile.doFirst(task -> srg.getParentFile().mkdirs());
            });
            reobf.configure(task -> task.getExtraMappings().from(mixinSrg));
        }

        private File accessWidenerFile() {
            if (accessWidener == null) {
                return null;
            }
            // A multi-version build's widener has one version's names, so it lives in that version's resources.
            for (File dir : sourceSet.getResources().getSrcDirs()) {
                File file = new File(dir, accessWidener);
                if (file.isFile()) {
                    return file;
                }
            }
            throw new GradleException("Access widener " + accessWidener + " is not in " + sourceSet.getResources().getSrcDirs());
        }
    }

    private static Properties versions() {
        Properties properties = new Properties();
        try (InputStream in = LichExtension.class.getResourceAsStream("versions.properties")) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }

    /** Mixin's annotation processor options, as a provider so the configuration cache keeps plain files. */
    private record MixinArguments(File mcpToSrg, File refmap, File outSrg) implements CommandLineArgumentProvider {
        @Override
        public Iterable<String> asArguments() {
            List<String> arguments = new ArrayList<>();
            arguments.add("-AreobfSrgFile=" + mcpToSrg.getAbsolutePath());
            arguments.add("-AoutRefMapFile=" + refmap.getAbsolutePath());
            arguments.add("-AoutSrgFile=" + outSrg.getAbsolutePath());
            arguments.add("-AdefaultObfuscationEnv=searge");
            return arguments;
        }
    }
}

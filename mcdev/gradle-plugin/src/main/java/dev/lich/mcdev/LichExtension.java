package dev.lich.mcdev;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
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
import java.util.List;
import java.util.Properties;

/**
 * The {@code lich} block of a build using {@code dev.lich.mod}:
 * <pre>
 * lich {
 *     minecraft("1.8.9")                          // vanilla 1.8.9 with MCP names on the compile classpath
 *     mixinRefmap("mixins.examplemod.refmap.json") // Mixin's annotation processor writes this refmap
 *     accessWidener("examplemod.aw")              // resource path of an access widener with MCP names
 * }
 * </pre>
 * The build also needs Maven Central and {@code https://maven.fabricmc.net} (Mixin) as repositories.
 */
public abstract class LichExtension {
    private static final Properties VERSIONS = versions();

    private final Project project;
    private MinecraftSetup.Result minecraft;
    private String accessWidener;
    private FileCollection minecraftClasspath;

    @Inject
    public LichExtension(Project project) {
        this.project = project;
    }

    /** Puts Minecraft {@code version} (vanilla, MCP names) and its libraries on the compile classpath. */
    public void minecraft(String version) {
        if (minecraft != null) {
            throw new GradleException("lich.minecraft(...) can only be set once; it is " + minecraft.minecraft());
        }
        Path cache = project.getGradle().getGradleUserHomeDir().toPath().resolve("caches/lich-mcdev");
        try {
            minecraft = MinecraftSetup.prepare(version, cache);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't set up Minecraft " + version, e);
        }

        TaskProvider<WidenJar> widen = project.getTasks().register("widenMinecraft", WidenJar.class, task -> {
            task.setDescription("Applies the mod's access widener to the Minecraft jar it compiles against.");
            task.getInput().set(minecraft.mcpJar().toFile());
            task.getAccessWidener().from(project.provider(() -> accessWidenerFile() == null ? List.of() : List.of(accessWidenerFile())));
            task.getOutput().set(project.getLayout().getBuildDirectory().file("lich/minecraft-" + version + "-mcp-widened.jar"));
        });
        ConfigurableFileCollection classpath = project.files(widen.flatMap(WidenJar::getOutput));
        for (Path library : minecraft.libraries()) {
            classpath.from(library.toFile());
        }
        project.getDependencies().add("compileOnly", classpath);
        minecraftClasspath = classpath;
        project.getDependencies().add("compileOnly", "net.fabricmc:sponge-mixin:" + VERSIONS.getProperty("mixin"));
        project.getDependencies().add("compileOnly", "io.github.llamalad7:mixinextras-common:" + VERSIONS.getProperty("mixinextras"));

        configureReobf();
    }

    /**
     * Runs Mixin's annotation processor, which checks the mod's Mixins against Minecraft and writes the
     * refmap {@code fileName} (MCP → SRG names of their targets) into the jar.
     */
    public void mixinRefmap(String fileName) {
        MinecraftSetup.Result mc = requireMinecraft();
        String asm = VERSIONS.getProperty("asm");
        // MixinExtras' processor registers its injectors (@WrapOperation, ...) with Mixin's, for their refmap entries.
        for (String processor : List.of("net.fabricmc:sponge-mixin:" + VERSIONS.getProperty("mixin"),
                "io.github.llamalad7:mixinextras-common:" + VERSIONS.getProperty("mixinextras"),
                "com.google.code.gson:gson:" + VERSIONS.getProperty("gson"), "org.ow2.asm:asm:" + asm,
                "org.ow2.asm:asm-tree:" + asm, "org.ow2.asm:asm-commons:" + asm, "org.ow2.asm:asm-util:" + asm,
                "org.ow2.asm:asm-analysis:" + asm)) {
            project.getDependencies().add("annotationProcessor", processor);
        }
        File mcpToSrg = mc.mcpToSrg().toFile();
        Provider<RegularFile> mixinSrg = project.getLayout().getBuildDirectory().file("lich/mixin.srg");
        project.getTasks().named("compileJava", JavaCompile.class, compile -> {
            File refmap = new File(compile.getDestinationDirectory().get().getAsFile(), fileName);
            File srg = mixinSrg.get().getAsFile();
            compile.getOutputs().file(srg).optional().withPropertyName("mixinSrg");
            compile.getOptions().getCompilerArgumentProviders().add(new MixinArguments(mcpToSrg, refmap, srg));
            compile.doFirst(task -> srg.getParentFile().mkdirs());
        });
        project.getTasks().named("reobfJar", ReobfJar.class, reobf -> reobf.getExtraMappings().from(mixinSrg));
    }

    /** An access widener in the mod's resources (MCP names), applied to the compile classpath and reobfuscated with the jar. */
    public void accessWidener(String resourcePath) {
        requireMinecraft();
        this.accessWidener = resourcePath;
        project.getTasks().named("reobfJar", ReobfJar.class, reobf -> reobf.getAccessWidener().set(resourcePath));
    }

    /** Minecraft (widened) and its libraries, for source sets other than {@code main} that compile against it. */
    public FileCollection getMinecraftClasspath() {
        requireMinecraft();
        return minecraftClasspath;
    }

    private File accessWidenerFile() {
        if (accessWidener == null) {
            return null;
        }
        SourceSet main = project.getExtensions().getByType(SourceSetContainer.class).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        for (File dir : main.getResources().getSrcDirs()) {
            File file = new File(dir, accessWidener);
            if (file.isFile()) {
                return file;
            }
        }
        throw new GradleException("Access widener " + accessWidener + " is not in " + main.getResources().getSrcDirs());
    }

    private MinecraftSetup.Result requireMinecraft() {
        if (minecraft == null) {
            throw new GradleException("Call lich.minecraft(\"<version>\") first");
        }
        return minecraft;
    }

    /** jar → the dev jar (classifier dev); reobfJar → the SRG jar, which the project publishes to its consumers. */
    private void configureReobf() {
        TaskProvider<Jar> jar = project.getTasks().named("jar", Jar.class, task -> task.getArchiveClassifier().set("dev"));
        BasePluginExtension base = project.getExtensions().getByType(BasePluginExtension.class);
        File mcpJar = minecraft.mcpJar().toFile();
        File mcpToSrg = minecraft.mcpToSrg().toFile();
        TaskProvider<ReobfJar> reobf = project.getTasks().register("reobfJar", ReobfJar.class, task -> {
            task.setGroup("build");
            task.setDescription("Renames MCP names in the dev jar to the SRG names the game runs with.");
            task.getInput().set(jar.flatMap(Jar::getArchiveFile));
            task.getMinecraft().from(mcpJar);
            task.getMappings().set(mcpToSrg);
            task.getOutput().set(project.getLayout().getBuildDirectory().file(base.getArchivesName().map(name ->
                    "libs/" + name + "-" + project.getVersion() + ".jar")));
        });
        project.getTasks().named("assemble", task -> task.dependsOn(reobf));
        for (String name : List.of("apiElements", "runtimeElements")) {
            Configuration elements = project.getConfigurations().getByName(name);
            elements.getOutgoing().getArtifacts().clear();
            elements.getOutgoing().artifact(reobf.flatMap(ReobfJar::getOutput), artifact -> artifact.builtBy(reobf));
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

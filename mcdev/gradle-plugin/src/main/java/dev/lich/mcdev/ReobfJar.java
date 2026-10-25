package dev.lich.mcdev;

import dev.lich.remap.core.AccessWidener;
import dev.lich.remap.core.ClassHierarchy;
import dev.lich.remap.core.JarRemapper;
import dev.lich.remap.core.MappingRemapper;
import dev.lich.remap.core.Mappings;
import dev.lich.remap.core.SrgFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reobfuscates a mod: every MCP name of a Minecraft member becomes its SRG name, found through the
 * class hierarchy (the mod's own classes, then Minecraft's), so overrides of game methods in mod classes
 * are renamed too. Mixin classes' own members that shadow game ones come from the extra mappings that
 * Mixin's annotation processor writes. The access widener, if any, is rewritten to SRG names.
 */
@CacheableTask
public abstract class ReobfJar extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInput();

    /** The Minecraft jar with MCP names, for the hierarchy. */
    @Classpath
    public abstract ConfigurableFileCollection getMinecraft();

    /** MCP → SRG. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getMappings();

    /** More MCP → SRG mappings in SRG format; files that don't exist are skipped. */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getExtraMappings();

    /** The access widener's path inside the jar. */
    @Input
    @Optional
    public abstract Property<String> getAccessWidener();

    @OutputFile
    public abstract RegularFileProperty getOutput();

    @TaskAction
    public void reobf() throws IOException {
        Mappings.Builder mappings = Mappings.builder().addAll(read(getMappings().get().getAsFile()));
        for (File extra : getExtraMappings()) {
            if (extra.isFile()) {
                mappings.addAll(read(extra));
            }
        }
        File input = getInput().get().getAsFile();
        List<ZipFile> jars = new ArrayList<>();
        try {
            jars.add(new ZipFile(input));
            for (File jar : getMinecraft()) {
                jars.add(new ZipFile(jar));
            }
            ClassHierarchy hierarchy = ClassHierarchy.fromClassFiles(name -> classBytes(jars, name));
            MappingRemapper remapper = new MappingRemapper(mappings.build(), hierarchy);
            String widenerPath = getAccessWidener().getOrNull();
            JarRemapper.remap(input.toPath(), getOutput().get().getAsFile().toPath(), remapper, entry -> true,
                    (name, bytes) -> name.equals(widenerPath) ? reobfWidener(bytes, remapper) : bytes);
        } finally {
            for (ZipFile jar : jars) {
                jar.close();
            }
        }
    }

    private static Mappings read(File srg) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(srg.toPath())) {
            return SrgFile.read(reader);
        }
    }

    private static byte[] classBytes(List<ZipFile> jars, String name) {
        for (ZipFile jar : jars) {
            ZipEntry entry = jar.getEntry(name + ".class");
            if (entry != null) {
                try (InputStream in = jar.getInputStream(entry)) {
                    return in.readAllBytes();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return null;
    }

    private static byte[] reobfWidener(byte[] bytes, MappingRemapper remapper) {
        try {
            AccessWidener widener = AccessWidener.parse(new String(bytes, StandardCharsets.UTF_8));
            return widener.remap(remapper, "srg").write().getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Bad access widener", e);
        }
    }
}

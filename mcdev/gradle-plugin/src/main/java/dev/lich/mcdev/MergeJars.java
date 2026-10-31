package dev.lich.mcdev;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileVisitDetails;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Merges a multi-version mod's reobfuscated jars, one per Minecraft version, into the one jar the game loads
 * (DOCS.md §5.1). A file in several of them must be byte for byte the same in each: that's what proves a
 * class in {@code src/main} links to the same SRG names on every version. Version-only files are kept as
 * they are. Shared code can't hold Mixins, because refmaps are written per version.
 */
@CacheableTask
public abstract class MergeJars extends DefaultTask {
    private static final long FIXED_TIME = 315532800000L; // 1980-01-01, as JarRemapper writes them
    private static final String MANIFEST = "META-INF/MANIFEST.MF";
    private static final byte[] MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;".getBytes(StandardCharsets.UTF_8);

    /** The reobfuscated jars, in the order of {@link #getVersions}. */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getJars();

    @Input
    public abstract ListProperty<String> getVersions();

    /** {@code src/main}'s classes, checked for Mixins. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSharedClasses();

    @OutputFile
    public abstract RegularFileProperty getOutput();

    @TaskAction
    public void merge() throws IOException {
        List<File> jars = List.copyOf(getJars().getFiles());
        List<String> versions = getVersions().get();
        if (jars.size() != versions.size()) {
            throw new IllegalStateException("Got " + jars.size() + " jars for versions " + versions);
        }
        checkNoSharedMixins();

        Map<String, byte[]> merged = new TreeMap<>();
        Map<String, List<String>> owners = new LinkedHashMap<>();
        List<String> differing = new ArrayList<>();
        for (int i = 0; i < jars.size(); i++) {
            try (ZipFile zip = new ZipFile(jars.get(i))) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    byte[] bytes;
                    try (InputStream in = zip.getInputStream(entry)) {
                        bytes = in.readAllBytes();
                    }
                    owners.computeIfAbsent(entry.getName(), name -> new ArrayList<>()).add(versions.get(i));
                    byte[] before = merged.putIfAbsent(entry.getName(), bytes);
                    if (before != null && !Arrays.equals(before, bytes) && !differing.contains(entry.getName())) {
                        differing.add(entry.getName());
                    }
                }
            }
        }
        if (!differing.isEmpty()) {
            StringBuilder message = new StringBuilder("These files differ between the Minecraft versions they're built for:");
            for (String name : differing.stream().sorted().toList()) {
                message.append("\n  - ").append(name).append(" (").append(String.join(", ", owners.get(name))).append(')');
            }
            message.append("\nA class in src/main must compile to the same SRG code on every version, so it can't use what")
                    .append(" differs between them: move that into each version's src/mc<version>/java, in a package of its own.")
                    .append(" A resource must be the same everywhere, or live in src/mc<version>/resources under a name of its own.");
            throw new GradleException(message.toString());
        }
        write(merged);
    }

    private void checkNoSharedMixins() {
        List<String> mixins = new ArrayList<>();
        getSharedClasses().getAsFileTree().matching(pattern -> pattern.include("**/*.class")).visit((FileVisitDetails details) -> {
            if (!details.isDirectory() && contains(read(details.getFile()), MIXIN_ANNOTATION)) {
                mixins.add(details.getRelativePath().getPathString());
            }
        });
        if (!mixins.isEmpty()) {
            throw new GradleException("Mixins in src/main: " + mixins + ". In a multi-version build they go in a version's"
                    + " src/mc<version>/java, whose compile writes that version's refmap (lich.forMinecraft(...) { mixinRefmap(...) }).");
        }
    }

    /** Sorted by name, the manifest first (as {@code JarInputStream} expects), fixed times: the same input gives the same bytes. */
    private void write(Map<String, byte[]> entries) throws IOException {
        File output = getOutput().get().getAsFile();
        output.getParentFile().mkdirs();
        File temporary = new File(output.getPath() + ".tmp");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(temporary.toPath()))) {
            if (entries.containsKey(MANIFEST)) {
                put(out, MANIFEST, entries.get(MANIFEST));
            }
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (!entry.getKey().equals(MANIFEST)) {
                    put(out, entry.getKey(), entry.getValue());
                }
            }
        }
        Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(FIXED_TIME);
        out.putNextEntry(entry);
        out.write(bytes);
        out.closeEntry();
    }

    private static byte[] read(File file) {
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}

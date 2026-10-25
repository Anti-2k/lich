package dev.lich.loader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds Lich mods: jars with a {@code lich.mod.json} at their root in {@code mods/} and
 * {@code mods/<minecraft>/} (where Forge 1.8.9 also looks), plus whatever {@code -Dlich.devMods} lists
 * (jars or directories, separated by the path separator), for running a mod straight from its build.
 * Jars without the file are Forge's business and are left alone.
 */
final class ModDiscovery {
    private ModDiscovery() {
    }

    /** Every mod found, or a {@link ModLoadingException} listing every jar that couldn't be read. */
    static List<LichMod> discover(Path gameDir, String minecraft, String devMods) {
        List<Path> candidates = new ArrayList<>();
        Path mods = gameDir.resolve("mods");
        candidates.addAll(jarsIn(mods));
        if (minecraft != null) {
            candidates.addAll(jarsIn(mods.resolve(minecraft)));
        }
        if (devMods != null) {
            for (String entry : devMods.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    candidates.add(Path.of(entry.strip()).toAbsolutePath());
                }
            }
        }

        List<LichMod> found = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Path candidate : candidates) {
            try {
                String json = descriptor(candidate);
                if (json != null) {
                    found.add(new LichMod(ModDescriptor.parse(json), candidate));
                }
            } catch (IOException | IllegalArgumentException e) {
                problems.add(candidate.getFileName() + ": " + e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new ModLoadingException(problems);
        }
        return found;
    }

    private static List<Path> jarsIn(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> Files.isRegularFile(file) && file.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(file -> file.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException e) {
            throw new ModLoadingException(List.of("Can't list " + dir + ": " + e.getMessage()));
        }
    }

    /** The descriptor's text, or {@code null} if {@code source} isn't a Lich mod. */
    static String descriptor(Path source) throws IOException {
        if (Files.isDirectory(source)) {
            Path file = source.resolve(ModDescriptor.FILE);
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        }
        try (ZipFile zip = new ZipFile(source.toFile())) {
            ZipEntry entry = zip.getEntry(ModDescriptor.FILE);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}

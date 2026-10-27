package dev.lich.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/** File helpers. Modpacks are untrusted input, so every path they name is checked to stay inside its directory. */
final class Paths {
    private Paths() {
    }

    /** {@code root.resolve(relative)}, refusing absolute paths and any that climb out of {@code root}. */
    static Path inside(Path root, String relative) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relative).normalize();
        if (Path.of(relative).isAbsolute() || !resolved.startsWith(normalizedRoot) || resolved.equals(normalizedRoot)) {
            throw new IOException("Path leaves its directory: " + relative);
        }
        return resolved;
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path source : files.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}

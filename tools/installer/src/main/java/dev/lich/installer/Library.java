package dev.lich.installer;

import java.nio.file.Path;

/**
 * One jar (or zip) a Lich component puts on the game's class path, by its Maven coordinates
 * ({@code group:artifact:version[:classifier][@extension]}). Lich's own jars are {@link Local}: copied into the
 * instance. Everything published elsewhere is {@link Remote}: the launcher downloads it and checks its SHA-1, so
 * the installer never redistributes third-party files, MCP's mappings above all.
 */
sealed interface Library {
    String name();

    /** A file the installer has (built here, or inside the installer jar). */
    record Local(String name, Path file) implements Library {
    }

    /** A file the launcher downloads from {@code url}. */
    record Remote(String name, String url, String sha1, long size) implements Library {
    }
}

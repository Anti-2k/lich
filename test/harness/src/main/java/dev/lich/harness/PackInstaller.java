package dev.lich.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Turns a pinned {@link Pack} into a ready game directory, {@code run/packs/<pack>/minecraft}, the way the pack's own
 * launcher installs it (one {@link Source} per pack format), then McInstanceLoader's list if the pack has one, then the
 * pack's first-launch {@code setup}. Optional files are left out, as a fresh install does by default. Built once per
 * pin: {@code .lich-pack} holds the fingerprint it was built for, and {@code files.txt} where each file came from.
 */
final class PackInstaller {
    private static final String DONE = ".lich-pack";

    /** One pack format's installer. */
    interface Source {
        void install(Pack pack, Target target) throws IOException;
    }

    private static final Map<String, Source> SOURCES = Map.of(
            "mrpack", new MrpackSource(),
            "solder", TechnicSource.SOLDER,
            "zip", TechnicSource.ZIP,
            "ftb", new FtbSource(),
            "atlauncher", new AtlauncherSource());

    private final Http http;
    private final Path runDir;

    PackInstaller(Http http, Path runDir) {
        this.http = http;
        this.runDir = runDir;
    }

    /** The pack's game directory template, built once per pinned pack. */
    Path install(Pack pack) throws IOException {
        Path root = runDir.resolve("packs").resolve(pack.id());
        Path template = root.resolve("minecraft");
        Path done = root.resolve(DONE);
        if (Files.isRegularFile(done) && Files.readString(done).strip().equals(pack.fingerprint())) {
            log(pack.name() + " is already in " + template);
            return template;
        }
        Source source = SOURCES.get(pack.source().type());
        if (source == null) {
            throw new IOException("Unknown pack source type " + pack.source().type());
        }
        Files.createDirectories(root);
        Files.deleteIfExists(done);
        deleteTree(template);
        Files.createDirectories(template);

        Target target = new Target(http, template);
        source.install(pack, target);
        McInstanceLoader.installIfPresent(target);
        setUp(pack, target);

        List<String> lines = new ArrayList<>();
        target.manifest.forEach((path, from) -> lines.add(path + "  " + from));
        Files.write(root.resolve("files.txt"), lines);
        Files.writeString(done, pack.fingerprint());
        log(pack.name() + ": " + target.manifest.size() + " files, " + countMods(template) + " mod jars, in " + template);
        return template;
    }

    /** The pack's first-launch answers (see {@link Pack}). */
    private static void setUp(Pack pack, Target target) throws IOException {
        for (String file : pack.remove()) {
            // A name that no longer matches must not quietly leave the file in.
            if (!Files.deleteIfExists(Paths.inside(target.template, file))) {
                throw new IOException("Setup: no " + file + " to remove");
            }
            target.manifest.remove(file);
            log("Setup: removed " + file);
        }
        List<Download> downloads = new ArrayList<>();
        pack.download().forEach((path, file) -> downloads.add(new Download(path, file.url(), Http.Hash.sha512(file.sha512()))));
        if (!downloads.isEmpty()) {
            target.downloadAll(downloads);
            downloads.forEach(d -> log("Setup: downloaded " + d.path()));
        }
        for (String archive : pack.unzip()) {
            target.unzip(Paths.inside(target.template, archive), "", "", "setup: unzipped from " + archive);
            log("Setup: unpacked " + archive);
        }
        for (Map.Entry<String, String> dir : pack.copy().entrySet()) {
            Path from = Paths.inside(target.template, dir.getValue());
            Path to = Paths.inside(target.template, dir.getKey());
            if (!Files.isDirectory(from)) {
                throw new IOException("Setup: no " + dir.getValue() + " to copy");
            }
            Paths.copyTree(from, to);
            target.manifest.put(dir.getKey() + "/", "setup: copied from " + dir.getValue() + "/");
            log("Setup: copied " + dir.getValue() + " to " + dir.getKey());
        }
        for (Map.Entry<String, String> file : pack.write().entrySet()) {
            Path path = Paths.inside(target.template, file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
            target.manifest.put(file.getKey(), "setup: written");
            log("Setup: wrote " + file.getKey());
        }
    }

    /** A file to download into the game directory, checked against its hash (null: the pack gives none). */
    record Download(String path, String url, Http.Hash hash) {
    }

    /** The game directory being built, the record of where each file came from, and the ways files get there. */
    static final class Target {
        final Http http;
        final Path template;
        final Map<String, String> manifest = new TreeMap<>();

        Target(Http http, Path template) {
            this.http = http;
            this.template = template;
        }

        void downloadAll(List<Download> downloads) throws IOException {
            List<Path> cached = fetchAll(downloads);
            for (int i = 0; i < downloads.size(); i++) {
                Download d = downloads.get(i);
                place(cached.get(i), d.path(), d.url() + (d.hash() == null ? "  (no hash given)" : ""));
            }
        }

        /** Downloads into the cache, 8 at a time; returns the cached files in the same order. */
        List<Path> fetchAll(List<Download> downloads) throws IOException {
            try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
                List<Future<Path>> results = new ArrayList<>();
                for (Download d : downloads) {
                    String fileName = d.path().substring(d.path().lastIndexOf('/') + 1);
                    results.add(pool.submit(() -> http.fetch(d.url(), fileName, d.hash())));
                }
                List<Path> cached = new ArrayList<>();
                for (int i = 0; i < downloads.size(); i++) {
                    try {
                        cached.add(results.get(i).get());
                    } catch (java.util.concurrent.ExecutionException e) {
                        throw new IOException("Downloading " + downloads.get(i).url() + " failed", e.getCause());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.InterruptedIOException();
                    }
                }
                return cached;
            }
        }

        void place(Path cached, String path, String from) throws IOException {
            Path target = Paths.inside(template, path);
            Files.createDirectories(target.getParent());
            Files.copy(cached, target, StandardCopyOption.REPLACE_EXISTING);
            manifest.put(path, from);
        }

        void write(String path, InputStream in, String from) throws IOException {
            Path target = Paths.inside(template, path);
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            manifest.put(path, from);
        }

        /** Unpacks the entries of {@code archive} under {@code folder} (a prefix inside it) into {@code into}. */
        void unzip(Path archive, String folder, String into, String from) throws IOException {
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                extract(zip, folder, into, from);
            }
        }

        /** {@link #unzip} for an open zip. */
        void extract(ZipFile zip, String folder, String into, String from) throws IOException {
            for (ZipEntry entry : zip.stream().toList()) {
                if (entry.isDirectory() || !entry.getName().startsWith(folder)) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    write(into + entry.getName().substring(folder.length()), in, from);
                }
            }
        }
    }

    static long countMods(Path gameDir) throws IOException {
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(mods)) {
            return files.filter(p -> p.toString().endsWith(".jar")).count();
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    static void log(String message) {
        System.out.println("[harness] " + message);
    }
}

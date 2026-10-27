package dev.lich.harness;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
import java.util.zip.ZipInputStream;

/**
 * Turns a pinned {@link Pack} into a ready game directory, {@code run/packs/<pack>/minecraft}: the {@code .mrpack}
 * index's client files and overrides, as a launcher installs them. Packs that leave their mods to McInstanceLoader get
 * those resolved here instead (its {@code resources.packconfig}), and the loader itself is left out: it would
 * download them again at the first launch, through a third-party CurseForge proxy with certificate checks turned off.
 * Optional resources are left out too, as a fresh install does by default.
 */
final class PackInstaller {
    private static final String DONE = ".lich-pack";
    private static final String MCINSTANCE = "config/mcinstanceloader/pack.mcinstance";

    private final Http http;
    private final Path runDir;

    PackInstaller(Http http, Path runDir) {
        this.http = http;
        this.runDir = runDir;
    }

    /** The pack's game directory template, built once per pinned pack file. */
    Path install(Pack pack) throws IOException {
        Path root = runDir.resolve("packs").resolve(pack.id());
        Path template = root.resolve("minecraft");
        Path done = root.resolve(DONE);
        if (Files.isRegularFile(done) && Files.readString(done).strip().equals(pack.fingerprint())) {
            log(pack.name() + " is already in " + template);
            return template;
        }
        Files.createDirectories(root);
        Files.deleteIfExists(done);
        deleteTree(template);
        Files.createDirectories(template);

        Path mrpack = http.fetch(pack.url(), pack.id() + ".mrpack", pack.sha512());
        Map<String, String> manifest = new TreeMap<>();
        try (ZipFile zip = new ZipFile(mrpack.toFile())) {
            JsonObject index = JsonParser.parseString(read(zip, "modrinth.index.json")).getAsJsonObject();
            List<Download> downloads = new ArrayList<>();
            for (JsonElement element : index.getAsJsonArray("files")) {
                JsonObject file = element.getAsJsonObject();
                JsonObject env = file.getAsJsonObject("env");
                if (env != null && env.has("client") && env.get("client").getAsString().equals("unsupported")) {
                    continue;
                }
                JsonArray urls = file.getAsJsonArray("downloads");
                downloads.add(new Download(file.get("path").getAsString(), urls.get(0).getAsString(),
                        file.getAsJsonObject("hashes").get("sha512").getAsString()));
            }
            log(pack.name() + ": " + downloads.size() + " files from the .mrpack index");
            downloadAll(downloads, template, manifest);
            // Client overrides win over the shared ones (the .mrpack format's rule).
            extract(zip, "overrides/", template, manifest);
            extract(zip, "client-overrides/", template, manifest);
        }

        Path mcInstance = template.resolve(MCINSTANCE);
        if (Files.isRegularFile(mcInstance)) {
            installMcInstance(mcInstance, template, manifest);
        }
        setUp(pack, template, manifest);

        List<String> lines = new ArrayList<>();
        manifest.forEach((path, source) -> lines.add(path + "  " + source));
        Files.write(root.resolve("files.txt"), lines);
        Files.writeString(done, pack.fingerprint());
        long mods = countMods(template);
        log(pack.name() + ": " + manifest.size() + " files, " + mods + " mod jars, in " + template);
        return template;
    }

    private void installMcInstance(Path mcInstance, Path template, Map<String, String> manifest) throws IOException {
        byte[] archive = Files.readAllBytes(mcInstance);
        String resources = null;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
            // The archive's own overrides, then its client overrides, then the resource list.
            List<Map.Entry<String, byte[]>> overrides = new ArrayList<>();
            List<Map.Entry<String, byte[]>> clientOverrides = new ArrayList<>();
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.equals("resources.packconfig")) {
                    resources = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } else if (name.startsWith("overrides/")) {
                    overrides.add(Map.entry(name.substring("overrides/".length()), in.readAllBytes()));
                } else if (name.startsWith("client-overrides/")) {
                    clientOverrides.add(Map.entry(name.substring("client-overrides/".length()), in.readAllBytes()));
                }
            }
            for (List<Map.Entry<String, byte[]>> group : List.of(overrides, clientOverrides)) {
                for (Map.Entry<String, byte[]> file : group) {
                    Path target = Paths.inside(template, file.getKey());
                    Files.createDirectories(target.getParent());
                    Files.write(target, file.getValue());
                    manifest.put(file.getKey(), "mcinstance override");
                }
            }
        }
        if (resources == null) {
            throw new IOException(mcInstance + " has no resources.packconfig");
        }

        List<Download> downloads = new ArrayList<>();
        int skipped = 0;
        for (PackConfig.Section resource : PackConfig.parse(resources)) {
            if ("server".equals(resource.get("side")) || "true".equals(resource.get("optional"))) {
                skipped++;
                continue;
            }
            String destination = resource.get("destination");
            switch (resource.get("type")) {
                case "url" -> downloads.add(new Download(destination, resource.get("url"), resource.get("SHA512")));
                case "modrinth" -> downloads.add(modrinth(resource, destination));
                default -> throw new IOException("Unknown resource type in " + resource.name() + ": " + resource.get("type"));
            }
        }
        log("McInstanceLoader list: " + downloads.size() + " files (" + skipped + " optional or server-only left out)");
        downloadAll(downloads, template, manifest);

        Files.delete(mcInstance);
        manifest.remove(MCINSTANCE);
        try (Stream<Path> mods = Files.list(template.resolve("mods"))) {
            for (Path jar : mods.filter(p -> p.getFileName().toString().toLowerCase().startsWith("mcinstanceloader")).toList()) {
                Files.delete(jar);
                manifest.remove("mods/" + jar.getFileName());
                log("Left out " + jar.getFileName() + ": its downloads are installed already");
            }
        }
    }

    /** The pack's first-launch answers (see {@link Pack}). */
    private static void setUp(Pack pack, Path template, Map<String, String> manifest) throws IOException {
        for (String archive : pack.unzip()) {
            Path zipFile = Paths.inside(template, archive);
            try (ZipFile zip = new ZipFile(zipFile.toFile())) {
                for (ZipEntry entry : zip.stream().toList()) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    Path target = Paths.inside(template, entry.getName());
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    manifest.put(entry.getName(), "setup: unzipped from " + archive);
                }
            }
            log("Setup: unpacked " + archive);
        }
        for (Map.Entry<String, String> file : pack.write().entrySet()) {
            Path target = Paths.inside(template, file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
            manifest.put(file.getKey(), "setup: written");
            log("Setup: wrote " + file.getKey());
        }
    }

    /** A Modrinth version's file: the API gives its URL and SHA-512. */
    private Download modrinth(PackConfig.Section resource, String destination) throws IOException {
        JsonObject version = JsonParser.parseString(
                http.getString("https://api.modrinth.com/v2/version/" + resource.get("versionId"))).getAsJsonObject();
        JsonObject chosen = null;
        for (JsonElement element : version.getAsJsonArray("files")) {
            JsonObject file = element.getAsJsonObject();
            if (file.get("filename").getAsString().equals(resource.get("sourceFileName"))
                    || chosen == null && file.get("primary").getAsBoolean()) {
                chosen = file;
            }
        }
        if (chosen == null) {
            throw new IOException("No file in Modrinth version " + resource.get("versionId") + " for " + resource.name());
        }
        return new Download(destination, chosen.get("url").getAsString(),
                chosen.getAsJsonObject("hashes").get("sha512").getAsString());
    }

    private void downloadAll(List<Download> downloads, Path template, Map<String, String> manifest) throws IOException {
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<Path>> results = new ArrayList<>();
            for (Download d : downloads) {
                results.add(pool.submit(() -> {
                    Path target = Paths.inside(template, d.path());
                    Path cached = http.fetch(d.url(), target.getFileName().toString(), d.sha512());
                    Files.createDirectories(target.getParent());
                    Files.copy(cached, target, StandardCopyOption.REPLACE_EXISTING);
                    return target;
                }));
            }
            for (int i = 0; i < downloads.size(); i++) {
                try {
                    results.get(i).get();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IOException("Downloading " + downloads.get(i).url() + " failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException();
                }
                Download d = downloads.get(i);
                manifest.put(d.path(), d.url() + (d.sha512() == null ? "  (no hash given)" : ""));
            }
        }
    }

    private static void extract(ZipFile zip, String prefix, Path template, Map<String, String> manifest) throws IOException {
        for (ZipEntry entry : zip.stream().toList()) {
            if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
                continue;
            }
            String path = entry.getName().substring(prefix.length());
            Path target = Paths.inside(template, path);
            Files.createDirectories(target.getParent());
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            manifest.put(path, prefix.equals("overrides/") ? "override" : "client override");
        }
    }

    private static String read(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new IOException("Not a .mrpack: no " + name);
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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

    private record Download(String path, String url, String sha512) {
    }

    private static void log(String message) {
        System.out.println("[harness] " + message);
    }
}

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Turns a pinned {@link Pack} into a ready game directory, {@code run/packs/<pack>/minecraft}, the way the pack's own
 * launcher installs it: a {@code .mrpack}'s client files and overrides; a Technic build's mod zips unpacked in order
 * (without {@code bin/}, which holds the Forge the dev profile provides), or one Technic client zip the same way;
 * an FTB version's client files; an ATLauncher version's client mods and configs. Packs that leave their mods to McInstanceLoader get those resolved here instead (its {@code
 * resources.packconfig}), and the loader itself is left out: it would download them again at the first launch,
 * through a third-party CurseForge proxy with certificate checks turned off. Optional files are left out, as a fresh
 * install does by default.
 */
final class PackInstaller {
    private static final String DONE = ".lich-pack";
    private static final String MCINSTANCE = "config/mcinstanceloader/pack.mcinstance";
    private static final String ATL_CDN = "https://download.nodecdn.net/containers/atl/";
    private static final String FTB_API = "https://api.feed-the-beast.com/v1/modpacks/public/modpack/";

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
        Files.createDirectories(root);
        Files.deleteIfExists(done);
        deleteTree(template);
        Files.createDirectories(template);

        Map<String, String> manifest = new TreeMap<>();
        switch (pack.source().type()) {
            case "mrpack" -> installMrpack(pack, template, manifest);
            case "solder" -> installSolder(pack, template, manifest);
            case "ftb" -> installFtb(pack, template, manifest);
            case "zip" -> installTechnicZip(pack, template, manifest);
            case "atlauncher" -> installAtlauncher(pack, template, manifest);
            default -> throw new IOException("Unknown pack source type " + pack.source().type());
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

    /** A Modrinth {@code .mrpack}: its index's client files, then its overrides. */
    private void installMrpack(Pack pack, Path template, Map<String, String> manifest) throws IOException {
        Pack.Source source = pack.source();
        Path mrpack = http.fetch(source.get("url"), pack.id() + ".mrpack", Http.Hash.sha512(source.get("sha512")));
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
                        Http.Hash.sha512(file.getAsJsonObject("hashes").get("sha512").getAsString())));
            }
            log(pack.name() + ": " + downloads.size() + " files from the .mrpack index");
            downloadAll(downloads, template, manifest);
            // Client overrides win over the shared ones (the .mrpack format's rule).
            extract(zip, "overrides/", template, manifest);
            extract(zip, "client-overrides/", template, manifest);
        }
    }

    /**
     * A Technic pack build from its Solder API: every mod is a zip of game-directory files, unpacked in the build's
     * order as the Technic launcher does (later ones win). {@code bin/} is skipped, after checking that the Forge its
     * {@code modpack.jar} names is the pack's: the dev profile provides that Forge.
     */
    private void installSolder(Pack pack, Path template, Map<String, String> manifest) throws IOException {
        Pack.Source source = pack.source();
        String api = source.get("api");
        JsonObject build = JsonParser.parseString(http.getString(api + (api.endsWith("/") ? "" : "/") + "modpack/"
                + source.get("slug") + "/" + source.get("build"))).getAsJsonObject();
        String minecraft = build.get("minecraft").getAsString();
        if (!pack.mc().equals(minecraft)) {
            throw new IOException(pack.name() + " is for Minecraft " + minecraft + ", not " + pack.mc());
        }
        List<Download> zips = new ArrayList<>();
        for (JsonElement element : build.getAsJsonArray("mods")) {
            JsonObject mod = element.getAsJsonObject();
            String url = mod.get("url").getAsString();
            zips.add(new Download(url.substring(url.lastIndexOf('/') + 1), url, Http.Hash.md5(mod.get("md5").getAsString())));
        }
        log(pack.name() + ": " + zips.size() + " mod zips from Solder");
        List<Path> cached = fetchAll(zips);
        boolean forgeChecked = false;
        for (int i = 0; i < zips.size(); i++) {
            try (ZipFile zip = new ZipFile(cached.get(i).toFile())) {
                forgeChecked |= unpackTechnic(pack, zip, zips.get(i).url(), template, manifest);
            }
        }
        if (!forgeChecked) {
            throw new IOException(pack.name() + " has no bin/modpack.jar naming its Forge");
        }
    }

    /** A Technic pack that is one client zip, laid out like a Solder mod zip. */
    private void installTechnicZip(Pack pack, Path template, Map<String, String> manifest) throws IOException {
        Pack.Source source = pack.source();
        Path cached = http.fetch(source.get("url"), pack.id() + ".zip", Http.Hash.sha512(source.get("sha512")));
        try (ZipFile zip = new ZipFile(cached.toFile())) {
            if (!unpackTechnic(pack, zip, source.get("url"), template, manifest)) {
                throw new IOException(pack.name() + " has no bin/modpack.jar naming its Forge");
            }
        }
    }

    /**
     * Unpacks one Technic zip into the game directory as the Technic launcher does, without {@code bin/}. Returns
     * whether it held {@code bin/modpack.jar}, whose Forge was checked.
     */
    private static boolean unpackTechnic(Pack pack, ZipFile zip, String from, Path template, Map<String, String> manifest)
            throws IOException {
        boolean forgeChecked = false;
        for (ZipEntry entry : zip.stream().toList()) {
            if (entry.isDirectory()) {
                continue;
            }
            if (entry.getName().startsWith("bin/")) {
                if (entry.getName().equals("bin/modpack.jar")) {
                    checkForge(pack, zip, entry);
                    forgeChecked = true;
                }
                continue;
            }
            Path target = Paths.inside(template, entry.getName());
            Files.createDirectories(target.getParent());
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            manifest.put(entry.getName(), from);
        }
        return forgeChecked;
    }

    /** The Forge a Technic pack's {@code bin/modpack.jar} names must be the one the dev profile runs. */
    private static void checkForge(Pack pack, ZipFile zip, ZipEntry modpackJar) throws IOException {
        try (ZipInputStream jar = new ZipInputStream(zip.getInputStream(modpackJar))) {
            for (ZipEntry entry; (entry = jar.getNextEntry()) != null; ) {
                if (entry.getName().equals("version.json")) {
                    String id = JsonParser.parseString(new String(jar.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject().get("id").getAsString();
                    String named = pack.source().packForge(pack.forge());
                    if (!id.contains(named)) {
                        throw new IOException(pack.name() + " runs " + id + ", not Forge " + named);
                    }
                    return;
                }
            }
        }
        throw new IOException(pack.name() + "'s bin/modpack.jar has no version.json");
    }

    /** An FTB pack version from FTB's API: its client files (not server-only or optional ones), each with a SHA-1. */
    private void installFtb(Pack pack, Path template, Map<String, String> manifest) throws IOException {
        Pack.Source source = pack.source();
        JsonObject version = JsonParser.parseString(
                http.getString(FTB_API + source.get("pack") + "/" + source.get("version"))).getAsJsonObject();
        for (JsonElement element : version.getAsJsonArray("targets")) {
            JsonObject target = element.getAsJsonObject();
            String name = target.get("name").getAsString();
            String expected = name.equals("minecraft") ? pack.mc()
                    : name.equals("forge") ? source.packForge(pack.forge()) : null;
            if (expected != null && !expected.equals(target.get("version").getAsString())) {
                throw new IOException(pack.name() + " needs " + name + " " + target.get("version").getAsString()
                        + ", not " + expected);
            }
        }
        List<Download> downloads = new ArrayList<>();
        int skipped = 0;
        for (JsonElement element : version.getAsJsonArray("files")) {
            JsonObject file = element.getAsJsonObject();
            if (file.get("serveronly").getAsBoolean() || file.get("optional").getAsBoolean()) {
                skipped++;
                continue;
            }
            String dir = file.get("path").getAsString();
            dir = dir.startsWith("./") ? dir.substring(2) : dir;
            downloads.add(new Download(dir + file.get("name").getAsString(), file.get("url").getAsString(),
                    Http.Hash.sha1(file.get("sha1").getAsString())));
        }
        log(pack.name() + ": " + downloads.size() + " files from FTB (" + skipped + " server-only or optional left out)");
        downloadAll(downloads, template, manifest);
    }


    /**
     * An ATLauncher pack version from its {@code Configs.json}: the client mods, the way ATLauncher's install dialog
     * leaves them with its recommended choices: required mods, and optional ones that are {@code selected} or
     * {@code recommended}. An optional {@code extract} (a recipe or config fix for another mod) comes only when every
     * mod it {@code depends} on is in. Mods the pack leaves to the player's browser (a forum link) are left out, and a
     * required one is an error. Then the pack's {@code Configs.zip}, unless it says {@code noConfigs}.
     */
    private void installAtlauncher(Pack pack, Path template, Map<String, String> manifest) throws IOException {
        Pack.Source source = pack.source();
        String base = ATL_CDN + "packs/" + source.get("pack") + "/versions/" + source.get("version") + "/";
        JsonObject config = JsonParser.parseString(http.getString(base + "Configs.json")).getAsJsonObject();
        String minecraft = config.get("minecraft").getAsString();
        if (!pack.mc().equals(minecraft)) {
            throw new IOException(pack.name() + " is for Minecraft " + minecraft + ", not " + pack.mc());
        }

        JsonArray mods = config.getAsJsonArray("mods");
        Set<String> wanted = new HashSet<>();
        for (JsonElement element : mods) {
            JsonObject mod = element.getAsJsonObject();
            if (atlWanted(mod) && !atlIsOptionalFix(mod)) {
                wanted.add(text(mod, "name"));
            }
        }
        List<JsonObject> chosen = new ArrayList<>();
        List<Download> downloads = new ArrayList<>();
        boolean forgeChecked = false;
        int left = 0;
        for (JsonElement element : mods) {
            JsonObject mod = element.getAsJsonObject();
            if (!flag(mod, "client")) {
                left++;
                continue;
            }
            if (text(mod, "type").equals("forge")) {
                String named = source.packForge(pack.forge());
                if (!text(mod, "version").equals(named)) {
                    throw new IOException(pack.name() + " runs Forge " + text(mod, "version") + ", not " + named);
                }
                forgeChecked = true;
                continue;
            }
            if (!atlWanted(mod) || atlIsOptionalFix(mod) && !wanted.containsAll(depends(mod))) {
                left++;
                continue;
            }
            String download = text(mod, "download");
            if (download.equals("browser")) {
                if (!flag(mod, "optional")) {
                    throw new IOException(pack.name() + ": " + text(mod, "name") + " has to be downloaded by hand");
                }
                left++;
                continue;
            }
            String url = download.equals("server") ? ATL_CDN + text(mod, "url") : text(mod, "url");
            String md5 = text(mod, "md5");
            chosen.add(mod);
            downloads.add(new Download(text(mod, "file"), url, md5.isEmpty() ? null : Http.Hash.md5(md5)));
        }
        if (!forgeChecked) {
            throw new IOException(pack.name() + " does not name its Forge");
        }
        log(pack.name() + ": " + downloads.size() + " files from ATLauncher (" + left
                + " server-only, optional or by-hand left out)");

        List<Path> cached = fetchAll(downloads);
        for (int i = 0; i < chosen.size(); i++) {
            JsonObject mod = chosen.get(i);
            Download d = downloads.get(i);
            String file = text(mod, "file");
            switch (text(mod, "type")) {
                case "mods", "dependency" -> place(cached.get(i), "mods/" + file, template, manifest, d.url());
                case "resourcepack" -> place(cached.get(i), "resourcepacks/" + file, template, manifest, d.url());
                case "extract" -> {
                    String folder = text(mod, "extractFolder").replaceAll("^/|/$", "");
                    unzip(cached.get(i), folder.isEmpty() ? "" : folder + "/", atlDirectory(mod, "extractTo"),
                            template, manifest, d.url());
                }
                case "decomp" -> {
                    String inner = text(mod, "decompFile");
                    try (ZipFile zip = new ZipFile(cached.get(i).toFile())) {
                        ZipEntry entry = zip.getEntry(inner);
                        if (entry == null) {
                            throw new IOException(file + " has no " + inner);
                        }
                        String target = atlDirectory(mod, "decompType") + inner;
                        Path path = Paths.inside(template, target);
                        Files.createDirectories(path.getParent());
                        try (InputStream in = zip.getInputStream(entry)) {
                            Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
                        }
                        manifest.put(target, d.url());
                    }
                }
                default -> throw new IOException(pack.name() + ": " + text(mod, "name") + " has the unknown type "
                        + text(mod, "type"));
            }
        }

        if (!flag(config, "noConfigs")) {
            JsonObject configs = config.getAsJsonObject("configs");
            Path zip = http.fetch(base + "Configs.zip", pack.id() + "-configs.zip",
                    Http.Hash.sha1(text(configs, "sha1")));
            unzip(zip, "", "", template, manifest, "Configs.zip");
        }
    }

    /** Whether ATLauncher's recommended choices install this mod. */
    private static boolean atlWanted(JsonObject mod) {
        return flag(mod, "client") && (!flag(mod, "optional") || flag(mod, "selected") || flag(mod, "recommended"));
    }

    /** An optional {@code extract}: a fix that only means something with the mods it depends on. */
    private static boolean atlIsOptionalFix(JsonObject mod) {
        return flag(mod, "optional") && text(mod, "type").equals("extract");
    }

    private static List<String> depends(JsonObject mod) {
        List<String> names = new ArrayList<>();
        if (mod.has("depends") && mod.get("depends").isJsonArray()) {
            mod.getAsJsonArray("depends").forEach(e -> names.add(e.getAsString()));
        }
        return names;
    }

    /** The game-directory folder ({@code ""} for the root, else with a trailing slash) an ATLauncher mod names. */
    private static String atlDirectory(JsonObject mod, String key) throws IOException {
        String where = text(mod, key);
        return switch (where) {
            case "root" -> "";
            case "mods", "coremods", "resourcepacks" -> where + "/";
            default -> throw new IOException(text(mod, "name") + " goes to the unknown folder " + where);
        };
    }

    private static boolean flag(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static void place(Path cached, String path, Path template, Map<String, String> manifest, String from)
            throws IOException {
        Path target = Paths.inside(template, path);
        Files.createDirectories(target.getParent());
        Files.copy(cached, target, StandardCopyOption.REPLACE_EXISTING);
        manifest.put(path, from);
    }

    /** Unpacks the entries of {@code archive} under {@code folder} (a prefix inside it) into {@code into}. */
    private static void unzip(Path archive, String folder, String into, Path template, Map<String, String> manifest,
                              String from) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                if (entry.isDirectory() || !entry.getName().startsWith(folder)) {
                    continue;
                }
                String path = into + entry.getName().substring(folder.length());
                Path target = Paths.inside(template, path);
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                manifest.put(path, from);
            }
        }
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
                case "url" -> downloads.add(new Download(destination, resource.get("url"), sha512(resource)));
                case "modrinth" -> downloads.add(modrinth(resource, destination));
                case "curseforge" -> downloads.add(curseforge(resource, destination));
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
        for (Map.Entry<String, String> dir : pack.copy().entrySet()) {
            Path from = Paths.inside(template, dir.getValue());
            Path to = Paths.inside(template, dir.getKey());
            if (!Files.isDirectory(from)) {
                throw new IOException("Setup: no " + dir.getValue() + " to copy");
            }
            Paths.copyTree(from, to);
            manifest.put(dir.getKey() + "/", "setup: copied from " + dir.getValue() + "/");
            log("Setup: copied " + dir.getValue() + " to " + dir.getKey());
        }
        for (Map.Entry<String, String> file : pack.write().entrySet()) {
            Path target = Paths.inside(template, file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
            manifest.put(file.getKey(), "setup: written");
            log("Setup: wrote " + file.getKey());
        }
    }

    /**
     * A CurseForge file, from CurseForge's public file CDN (no API key: the id alone gives the URL), checked against
     * the SHA-512 the pack lists.
     */
    private static Download curseforge(PackConfig.Section resource, String destination) {
        long fileId = Long.parseLong(resource.get("fileId"));
        return new Download(destination, "https://mediafilez.forgecdn.net/files/" + fileId / 1000 + "/" + fileId % 1000
                + "/" + resource.get("sourceFileName"), sha512(resource));
    }

    /** The SHA-512 of a McInstanceLoader resource: packs spell the key {@code SHA512} or {@code SHA-512}. */
    private static Http.Hash sha512(PackConfig.Section resource) {
        String hex = resource.get("SHA512") != null ? resource.get("SHA512") : resource.get("SHA-512");
        return Http.Hash.sha512(hex);
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
                Http.Hash.sha512(chosen.getAsJsonObject("hashes").get("sha512").getAsString()));
    }

    private void downloadAll(List<Download> downloads, Path template, Map<String, String> manifest) throws IOException {
        List<Path> cached = fetchAll(downloads);
        for (int i = 0; i < downloads.size(); i++) {
            Download d = downloads.get(i);
            Path target = Paths.inside(template, d.path());
            Files.createDirectories(target.getParent());
            Files.copy(cached.get(i), target, StandardCopyOption.REPLACE_EXISTING);
            manifest.put(d.path(), d.url() + (d.hash() == null ? "  (no hash given)" : ""));
        }
    }

    /** Downloads into the cache, 8 at a time; returns the cached files in the same order. */
    private List<Path> fetchAll(List<Download> downloads) throws IOException {
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

    private record Download(String path, String url, Http.Hash hash) {
    }

    private static void log(String message) {
        System.out.println("[harness] " + message);
    }
}

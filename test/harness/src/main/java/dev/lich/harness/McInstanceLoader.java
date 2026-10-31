package dev.lich.harness;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Packs that leave their mods to McInstanceLoader get that list resolved at install time instead (its
 * {@code resources.packconfig}), and the loader itself is left out: it would download them again at the first launch,
 * through a third-party CurseForge proxy with certificate checks turned off.
 */
final class McInstanceLoader {
    private static final String MCINSTANCE = "config/mcinstanceloader/pack.mcinstance";

    private McInstanceLoader() {
    }

    static void installIfPresent(PackInstaller.Target target) throws IOException {
        Path mcInstance = target.template.resolve(MCINSTANCE);
        if (!Files.isRegularFile(mcInstance)) {
            return;
        }
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
                    target.write(file.getKey(), new ByteArrayInputStream(file.getValue()), "mcinstance override");
                }
            }
        }
        if (resources == null) {
            throw new IOException(mcInstance + " has no resources.packconfig");
        }

        List<PackInstaller.Download> downloads = new ArrayList<>();
        int skipped = 0;
        for (PackConfig.Section resource : PackConfig.parse(resources)) {
            if ("server".equals(resource.get("side")) || "true".equals(resource.get("optional"))) {
                skipped++;
                continue;
            }
            String destination = resource.get("destination");
            switch (resource.get("type")) {
                case "url" -> downloads.add(new PackInstaller.Download(destination, resource.get("url"), sha512(resource)));
                case "modrinth" -> downloads.add(modrinth(target.http, resource, destination));
                case "curseforge" -> downloads.add(curseforge(resource, destination));
                default -> throw new IOException("Unknown resource type in " + resource.name() + ": " + resource.get("type"));
            }
        }
        PackInstaller.log("McInstanceLoader list: " + downloads.size() + " files (" + skipped
                + " optional or server-only left out)");
        target.downloadAll(downloads);

        Files.delete(mcInstance);
        target.manifest.remove(MCINSTANCE);
        try (Stream<Path> mods = Files.list(target.template.resolve("mods"))) {
            for (Path jar : mods.filter(p -> p.getFileName().toString().toLowerCase().startsWith("mcinstanceloader")).toList()) {
                Files.delete(jar);
                target.manifest.remove("mods/" + jar.getFileName());
                PackInstaller.log("Left out " + jar.getFileName() + ": its downloads are installed already");
            }
        }
    }

    /**
     * A CurseForge file, from CurseForge's public file CDN (no API key: the id alone gives the URL), checked against
     * the SHA-512 the pack lists.
     */
    private static PackInstaller.Download curseforge(PackConfig.Section resource, String destination) {
        long fileId = Long.parseLong(resource.get("fileId"));
        return new PackInstaller.Download(destination, "https://mediafilez.forgecdn.net/files/" + fileId / 1000 + "/"
                + fileId % 1000 + "/" + resource.get("sourceFileName"), sha512(resource));
    }

    /** The SHA-512 of a McInstanceLoader resource: packs spell the key {@code SHA512} or {@code SHA-512}. */
    private static Http.Hash sha512(PackConfig.Section resource) {
        String hex = resource.get("SHA512") != null ? resource.get("SHA512") : resource.get("SHA-512");
        return Http.Hash.sha512(hex);
    }

    /** A Modrinth version's file: the API gives its URL and SHA-512. */
    private static PackInstaller.Download modrinth(Http http, PackConfig.Section resource, String destination)
            throws IOException {
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
        return new PackInstaller.Download(destination, chosen.get("url").getAsString(),
                Http.Hash.sha512(chosen.getAsJsonObject("hashes").get("sha512").getAsString()));
    }
}

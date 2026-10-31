package dev.lich.harness;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Technic packs: zips of game-directory files, unpacked in order as the Technic launcher does (later ones win), without
 * {@code bin/}, after checking that the Forge its {@code modpack.jar} names is the pack's (the dev profile provides
 * that Forge). {@link #SOLDER}: a build on a Solder API ({@code api}, {@code slug}, {@code build}), every mod a zip with
 * an MD5. {@link #ZIP}: a pack that is one client zip ({@code url}, {@code sha512}, which the pin adds: Technic gives
 * none).
 */
final class TechnicSource implements PackInstaller.Source {
    static final TechnicSource SOLDER = new TechnicSource(true);
    static final TechnicSource ZIP = new TechnicSource(false);

    private final boolean solder;

    private TechnicSource(boolean solder) {
        this.solder = solder;
    }

    @Override
    public void install(Pack pack, PackInstaller.Target target) throws IOException {
        Pack.Source source = pack.source();
        List<PackInstaller.Download> zips = new ArrayList<>();
        if (solder) {
            String api = source.get("api");
            JsonObject build = JsonParser.parseString(target.http.getString(api + (api.endsWith("/") ? "" : "/") + "modpack/"
                    + source.get("slug") + "/" + source.get("build"))).getAsJsonObject();
            String minecraft = build.get("minecraft").getAsString();
            if (!pack.mc().equals(minecraft)) {
                throw new IOException(pack.name() + " is for Minecraft " + minecraft + ", not " + pack.mc());
            }
            for (JsonElement element : build.getAsJsonArray("mods")) {
                JsonObject mod = element.getAsJsonObject();
                String url = mod.get("url").getAsString();
                zips.add(new PackInstaller.Download(url.substring(url.lastIndexOf('/') + 1), url,
                        Http.Hash.md5(mod.get("md5").getAsString())));
            }
            PackInstaller.log(pack.name() + ": " + zips.size() + " mod zips from Solder");
        } else {
            zips.add(new PackInstaller.Download(pack.id() + ".zip", source.get("url"), Http.Hash.sha512(source.get("sha512"))));
        }
        List<Path> cached = target.fetchAll(zips);
        boolean forgeChecked = false;
        for (int i = 0; i < zips.size(); i++) {
            try (ZipFile zip = new ZipFile(cached.get(i).toFile())) {
                forgeChecked |= unpack(pack, zip, zips.get(i).url(), target);
            }
        }
        if (!forgeChecked) {
            throw new IOException(pack.name() + " has no bin/modpack.jar naming its Forge");
        }
    }

    /** Unpacks one zip without {@code bin/}; returns whether it held {@code bin/modpack.jar}, whose Forge was checked. */
    private static boolean unpack(Pack pack, ZipFile zip, String from, PackInstaller.Target target) throws IOException {
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
            try (InputStream in = zip.getInputStream(entry)) {
                target.write(entry.getName(), in, from);
            }
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
}

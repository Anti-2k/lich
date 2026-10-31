package dev.lich.harness;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * An FTB pack version from FTB's API ({@code pack}, {@code version} ids): its client files (not server-only or optional
 * ones), each with a SHA-1. A fifth of them come from CurseForge's CDN, so this is also the keyless way to CurseForge's
 * files.
 */
final class FtbSource implements PackInstaller.Source {
    private static final String API = "https://api.feed-the-beast.com/v1/modpacks/public/modpack/";

    @Override
    public void install(Pack pack, PackInstaller.Target target) throws IOException {
        Pack.Source source = pack.source();
        JsonObject version = JsonParser.parseString(
                target.http.getString(API + source.get("pack") + "/" + source.get("version"))).getAsJsonObject();
        for (JsonElement element : version.getAsJsonArray("targets")) {
            JsonObject t = element.getAsJsonObject();
            String name = t.get("name").getAsString();
            String expected = name.equals("minecraft") ? pack.mc()
                    : name.equals("forge") ? source.packForge(pack.forge()) : null;
            if (expected != null && !expected.equals(t.get("version").getAsString())) {
                throw new IOException(pack.name() + " needs " + name + " " + t.get("version").getAsString()
                        + ", not " + expected);
            }
        }
        List<PackInstaller.Download> downloads = new ArrayList<>();
        int skipped = 0;
        for (JsonElement element : version.getAsJsonArray("files")) {
            JsonObject file = element.getAsJsonObject();
            if (file.get("serveronly").getAsBoolean() || file.get("optional").getAsBoolean()) {
                skipped++;
                continue;
            }
            String dir = file.get("path").getAsString();
            dir = dir.startsWith("./") ? dir.substring(2) : dir;
            downloads.add(new PackInstaller.Download(dir + file.get("name").getAsString(), file.get("url").getAsString(),
                    Http.Hash.sha1(file.get("sha1").getAsString())));
        }
        PackInstaller.log(pack.name() + ": " + downloads.size() + " files from FTB (" + skipped
                + " server-only or optional left out)");
        target.downloadAll(downloads);
    }
}

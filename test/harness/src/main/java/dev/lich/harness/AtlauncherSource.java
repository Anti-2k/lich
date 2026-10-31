package dev.lich.harness;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * An ATLauncher pack version from its {@code Configs.json} ({@code pack} safe name, {@code version}): the client mods,
 * the way ATLauncher's install dialog leaves them with its recommended choices: required mods, and optional ones that
 * are {@code selected} or {@code recommended}. An optional {@code extract} (a recipe or config fix for another mod)
 * comes only when every mod it {@code depends} on is in. Mods the pack leaves to the player's browser (a forum link)
 * are left out, and a required one is an error. Then the pack's {@code Configs.zip}, unless it says
 * {@code noConfigs}. Files have MD5s.
 */
final class AtlauncherSource implements PackInstaller.Source {
    private static final String CDN = "https://download.nodecdn.net/containers/atl/";

    @Override
    public void install(Pack pack, PackInstaller.Target target) throws IOException {
        Pack.Source source = pack.source();
        String base = CDN + "packs/" + source.get("pack") + "/versions/" + source.get("version") + "/";
        JsonObject config = JsonParser.parseString(target.http.getString(base + "Configs.json")).getAsJsonObject();
        String minecraft = config.get("minecraft").getAsString();
        if (!pack.mc().equals(minecraft)) {
            throw new IOException(pack.name() + " is for Minecraft " + minecraft + ", not " + pack.mc());
        }

        JsonArray mods = config.getAsJsonArray("mods");
        Set<String> wanted = new HashSet<>();
        for (JsonElement element : mods) {
            JsonObject mod = element.getAsJsonObject();
            if (wanted(mod) && !isOptionalFix(mod)) {
                wanted.add(text(mod, "name"));
            }
        }
        List<JsonObject> chosen = new ArrayList<>();
        List<PackInstaller.Download> downloads = new ArrayList<>();
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
            if (!wanted(mod) || isOptionalFix(mod) && !wanted.containsAll(depends(mod))) {
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
            String url = download.equals("server") ? CDN + text(mod, "url") : text(mod, "url");
            String md5 = text(mod, "md5");
            chosen.add(mod);
            downloads.add(new PackInstaller.Download(text(mod, "file"), url, md5.isEmpty() ? null : Http.Hash.md5(md5)));
        }
        if (!forgeChecked) {
            throw new IOException(pack.name() + " does not name its Forge");
        }
        PackInstaller.log(pack.name() + ": " + downloads.size() + " files from ATLauncher (" + left
                + " server-only, optional or by-hand left out)");

        List<Path> cached = target.fetchAll(downloads);
        for (int i = 0; i < chosen.size(); i++) {
            JsonObject mod = chosen.get(i);
            String from = downloads.get(i).url();
            String file = text(mod, "file");
            switch (text(mod, "type")) {
                case "mods", "dependency" -> target.place(cached.get(i), "mods/" + file, from);
                case "resourcepack" -> target.place(cached.get(i), "resourcepacks/" + file, from);
                case "extract" -> {
                    String folder = text(mod, "extractFolder").replaceAll("^/|/$", "");
                    target.unzip(cached.get(i), folder.isEmpty() ? "" : folder + "/", directory(mod, "extractTo"), from);
                }
                case "decomp" -> {
                    String inner = text(mod, "decompFile");
                    try (ZipFile zip = new ZipFile(cached.get(i).toFile())) {
                        ZipEntry entry = zip.getEntry(inner);
                        if (entry == null) {
                            throw new IOException(file + " has no " + inner);
                        }
                        try (InputStream in = zip.getInputStream(entry)) {
                            target.write(directory(mod, "decompType") + inner, in, from);
                        }
                    }
                }
                default -> throw new IOException(pack.name() + ": " + text(mod, "name") + " has the unknown type "
                        + text(mod, "type"));
            }
        }

        if (!flag(config, "noConfigs")) {
            JsonObject configs = config.getAsJsonObject("configs");
            Path zip = target.http.fetch(base + "Configs.zip", pack.id() + "-configs.zip", Http.Hash.sha1(text(configs, "sha1")));
            target.unzip(zip, "", "", "Configs.zip");
        }
    }

    /** Whether ATLauncher's recommended choices install this mod. */
    private static boolean wanted(JsonObject mod) {
        return flag(mod, "client") && (!flag(mod, "optional") || flag(mod, "selected") || flag(mod, "recommended"));
    }

    /** An optional {@code extract}: a fix that only means something with the mods it depends on. */
    private static boolean isOptionalFix(JsonObject mod) {
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
    private static String directory(JsonObject mod, String key) throws IOException {
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
}

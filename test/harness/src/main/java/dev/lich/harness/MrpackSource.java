package dev.lich.harness;

import com.google.gson.JsonArray;
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

/** A Modrinth {@code .mrpack} ({@code url}, {@code sha512}): its index's client files, then its overrides. */
final class MrpackSource implements PackInstaller.Source {
    @Override
    public void install(Pack pack, PackInstaller.Target target) throws IOException {
        Pack.Source source = pack.source();
        Path mrpack = target.http.fetch(source.get("url"), pack.id() + ".mrpack", Http.Hash.sha512(source.get("sha512")));
        try (ZipFile zip = new ZipFile(mrpack.toFile())) {
            JsonObject index = JsonParser.parseString(read(zip, "modrinth.index.json")).getAsJsonObject();
            List<PackInstaller.Download> downloads = new ArrayList<>();
            for (JsonElement element : index.getAsJsonArray("files")) {
                JsonObject file = element.getAsJsonObject();
                JsonObject env = file.getAsJsonObject("env");
                if (env != null && env.has("client") && env.get("client").getAsString().equals("unsupported")) {
                    continue;
                }
                JsonArray urls = file.getAsJsonArray("downloads");
                downloads.add(new PackInstaller.Download(file.get("path").getAsString(), urls.get(0).getAsString(),
                        Http.Hash.sha512(file.getAsJsonObject("hashes").get("sha512").getAsString())));
            }
            PackInstaller.log(pack.name() + ": " + downloads.size() + " files from the .mrpack index");
            target.downloadAll(downloads);
            // Client overrides win over the shared ones (the .mrpack format's rule).
            target.extract(zip, "overrides/", "", "override");
            target.extract(zip, "client-overrides/", "", "client override");
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
}

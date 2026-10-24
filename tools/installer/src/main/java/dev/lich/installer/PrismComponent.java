package dev.lich.installer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * Adds Lich to an existing Prism Launcher (or MultiMC) instance as a custom component: the component
 * file {@code patches/dev.lich.json}, the Lich jars as instance-local libraries, and an entry in
 * {@code mmc-pack.json} right after Minecraft. Running it again only refreshes the jars and the
 * component file, so it doubles as "deploy the latest build".
 */
final class PrismComponent {
    static final String UID = "dev.lich";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private PrismComponent() {
    }

    static void install(Path instance, String mc, String version, List<String> jvmFlags, Map<String, Path> libraries)
            throws IOException {
        Path pack = instance.resolve("mmc-pack.json");
        if (!Files.isRegularFile(pack)) {
            throw new IOException("Not a Prism instance (no mmc-pack.json): " + instance);
        }

        JsonArray libraryList = new JsonArray();
        for (Map.Entry<String, Path> library : libraries.entrySet()) {
            String[] coordinates = library.getKey().split(":");
            // Prism looks up "local" libraries as <instance>/libraries/<artifact>-<version>.jar.
            Path target = instance.resolve("libraries").resolve(coordinates[1] + "-" + coordinates[2] + ".jar");
            Files.createDirectories(target.getParent());
            Files.copy(library.getValue(), target, StandardCopyOption.REPLACE_EXISTING);
            JsonObject entry = new JsonObject();
            entry.addProperty("name", library.getKey());
            entry.addProperty("MMC-hint", "local");
            libraryList.add(entry);
        }

        JsonObject requirement = new JsonObject();
        requirement.addProperty("uid", "net.minecraft");
        requirement.addProperty("equals", mc);
        JsonArray requires = new JsonArray();
        requires.add(requirement);
        JsonArray jvm = new JsonArray();
        jvmFlags.forEach(jvm::add);

        JsonObject component = new JsonObject();
        component.addProperty("formatVersion", 1);
        component.addProperty("name", "Lich");
        component.addProperty("uid", UID);
        component.addProperty("version", version);
        component.add("requires", requires);
        component.addProperty("mainClass", ProfileInstaller.MAIN_CLASS);
        component.add("+libraries", libraryList);
        component.add("+jvmArgs", jvm);
        Path patch = instance.resolve("patches").resolve(UID + ".json");
        Files.createDirectories(patch.getParent());
        Files.writeString(patch, GSON.toJson(component) + "\n");

        Files.writeString(pack, GSON.toJson(withLichComponent(JsonParser.parseString(Files.readString(pack)).getAsJsonObject(), version)) + "\n");
    }

    /** Adds (or updates) the Lich entry, placed after Minecraft so its main class wins. */
    static JsonObject withLichComponent(JsonObject pack, String version) {
        JsonArray components = pack.getAsJsonArray("components");
        JsonArray updated = new JsonArray();
        JsonObject lich = new JsonObject();
        lich.addProperty("cachedName", "Lich");
        lich.addProperty("cachedVersion", version);
        lich.addProperty("uid", UID);
        lich.addProperty("version", version);
        for (JsonElement element : components) {
            JsonObject component = element.getAsJsonObject();
            String uid = component.get("uid").getAsString();
            if (uid.equals(UID)) {
                continue;
            }
            updated.add(component);
            if (uid.equals("net.minecraft")) {
                updated.add(lich);
            }
        }
        pack.add("components", updated);
        return pack;
    }
}

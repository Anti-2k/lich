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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adds Lich to an existing Prism Launcher (or MultiMC) instance as a custom component: the component
 * file {@code patches/dev.lich.json}, Lich's jars as instance-local libraries, the other libraries as downloads,
 * and an entry in {@code mmc-pack.json} after every other component. Running it again only refreshes the jars and
 * the component file, so it doubles as "deploy the latest build".
 */
final class PrismComponent {
    static final String UID = "dev.lich";
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private PrismComponent() {
    }

    static void install(Path instance, String mc, String version, List<String> jvmFlags, List<Library> libraries)
            throws IOException {
        Path pack = instance.resolve("mmc-pack.json");
        if (!Files.isRegularFile(pack)) {
            throw new IOException("Not a Prism instance (no mmc-pack.json): " + instance);
        }

        Set<String> localFiles = new HashSet<>();
        JsonArray libraryList = new JsonArray();
        for (Library library : libraries) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", library.name());
            switch (library) {
                case Library.Local local -> {
                    // Prism looks up "local" libraries as <instance>/libraries/<artifact>-<version>[-<classifier>].<extension>.
                    String fileName = ProfileInstaller.fileName(local.name());
                    Path target = instance.resolve("libraries").resolve(fileName);
                    Files.createDirectories(target.getParent());
                    Files.copy(local.file(), target, StandardCopyOption.REPLACE_EXISTING);
                    localFiles.add(fileName);
                    entry.addProperty("MMC-hint", "local");
                }
                case Library.Remote remote -> {
                    JsonObject artifact = new JsonObject();
                    artifact.addProperty("path", ProfileInstaller.mavenPath(remote.name()));
                    artifact.addProperty("sha1", remote.sha1());
                    artifact.addProperty("size", remote.size());
                    artifact.addProperty("url", remote.url());
                    JsonObject downloads = new JsonObject();
                    downloads.add("artifact", artifact);
                    entry.add("downloads", downloads);
                }
            }
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
        Path patch = patchFile(instance);
        // Jars an older Lich version left behind would otherwise pile up in libraries/.
        deleteLocalLibraries(instance, localFiles);
        Files.createDirectories(patch.getParent());
        Files.writeString(patch, GSON.toJson(component) + "\n");

        Files.writeString(pack, GSON.toJson(withLichComponent(readJson(pack), version)) + "\n");
    }

    /** Removes the Lich component, its file and its instance-local jars; the rest of the instance stays as it is. */
    static void uninstall(Path instance) throws IOException {
        Path pack = instance.resolve("mmc-pack.json");
        deleteLocalLibraries(instance, Set.of());
        Files.deleteIfExists(patchFile(instance));
        JsonObject json = readJson(pack);
        JsonArray kept = new JsonArray();
        for (JsonElement component : json.getAsJsonArray("components")) {
            if (!UID.equals(component.getAsJsonObject().get("uid").getAsString())) {
                kept.add(component);
            }
        }
        json.add("components", kept);
        Files.writeString(pack, GSON.toJson(json) + "\n");
    }

    static boolean isInstalled(Path instance) {
        return Files.isRegularFile(patchFile(instance));
    }

    /**
     * Adds (or updates) the Lich entry as the last component: Prism takes the main class from the last
     * component that sets one, and Forge sets LaunchWrapper's.
     */
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
        }
        updated.add(lich);
        pack.add("components", updated);
        return pack;
    }

    static JsonObject readJson(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    private static Path patchFile(Path instance) {
        return instance.resolve("patches").resolve(UID + ".json");
    }

    /** Deletes the instance-local jars the current component file lists, except {@code keep}. */
    private static void deleteLocalLibraries(Path instance, Set<String> keep) throws IOException {
        Path patch = patchFile(instance);
        if (!Files.isRegularFile(patch)) {
            return;
        }
        JsonArray old = readJson(patch).getAsJsonArray("+libraries");
        if (old == null) {
            return;
        }
        for (JsonElement element : old) {
            JsonObject library = element.getAsJsonObject();
            if (library.has("MMC-hint") && "local".equals(library.get("MMC-hint").getAsString())) {
                String fileName = ProfileInstaller.fileName(library.get("name").getAsString());
                if (!keep.contains(fileName)) {
                    Files.deleteIfExists(instance.resolve("libraries").resolve(fileName));
                }
            }
        }
    }
}

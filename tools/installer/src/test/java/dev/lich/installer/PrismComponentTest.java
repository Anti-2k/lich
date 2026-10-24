package dev.lich.installer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrismComponentTest {
    private static final String PACK = """
            {"components": [
              {"uid": "org.lwjgl", "version": "2.9.4-nightly-20150209", "dependencyOnly": true},
              {"uid": "net.minecraft", "version": "1.8.9", "important": true}
            ], "formatVersion": 1}
            """;

    @Test
    void addsTheComponentLastAndCopiesTheJars(@TempDir Path instance, @TempDir Path build) throws Exception {
        Files.writeString(instance.resolve("mmc-pack.json"), PACK);
        Path jar = Files.write(build.resolve("lich-boot-1.0.jar"), new byte[]{1});

        // Twice: installing again must update in place, not add a second entry.
        for (int i = 0; i < 2; i++) {
            PrismComponent.install(instance, "1.8.9", "1.0", List.of("--enable-native-access=ALL-UNNAMED"),
                    Map.of("dev.lich:lich-boot:1.0", jar));
        }

        JsonArray components = JsonParser.parseString(Files.readString(instance.resolve("mmc-pack.json")))
                .getAsJsonObject().getAsJsonArray("components");
        List<String> uids = new ArrayList<>();
        components.forEach(c -> uids.add(c.getAsJsonObject().get("uid").getAsString()));
        assertEquals(List.of("org.lwjgl", "net.minecraft", "dev.lich"), uids);
        assertTrue(components.get(1).getAsJsonObject().get("important").getAsBoolean(), "other components are kept as they were");

        JsonObject patch = JsonParser.parseString(Files.readString(instance.resolve("patches/dev.lich.json"))).getAsJsonObject();
        assertEquals("dev.lich.boot.Main", patch.get("mainClass").getAsString());
        assertEquals("1.8.9", patch.getAsJsonArray("requires").get(0).getAsJsonObject().get("equals").getAsString());
        assertEquals("local", patch.getAsJsonArray("+libraries").get(0).getAsJsonObject().get("MMC-hint").getAsString());
        assertEquals("--enable-native-access=ALL-UNNAMED", patch.getAsJsonArray("+jvmArgs").get(0).getAsString());
        assertTrue(Files.isRegularFile(instance.resolve("libraries/lich-boot-1.0.jar")));
    }

    @Test
    void goesAfterForgeSoItsMainClassWins() {
        JsonObject pack = JsonParser.parseString("""
                {"components": [
                  {"uid": "net.minecraft", "version": "1.8.9"},
                  {"uid": "dev.lich", "version": "0.9"},
                  {"uid": "net.minecraftforge", "version": "11.15.1.2318"}
                ]}
                """).getAsJsonObject();
        List<String> uids = new ArrayList<>();
        PrismComponent.withLichComponent(pack, "1.0").getAsJsonArray("components")
                .forEach(c -> uids.add(c.getAsJsonObject().get("uid").getAsString()));
        assertEquals(List.of("net.minecraft", "net.minecraftforge", "dev.lich"), uids);
    }
}

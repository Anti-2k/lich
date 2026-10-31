package dev.lich.installer;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrismInstanceTest {
    private static Path instance(Path dir, String components) throws Exception {
        Files.writeString(dir.resolve("mmc-pack.json"), "{\"components\": [" + components + "], \"formatVersion\": 1}");
        Files.writeString(dir.resolve("instance.cfg"), """
                [General]
                ConfigVersion=1.3
                name=My Pack
                JavaPath=C:/old/java.exe
                JavaVersion=1.8.0_51

                [UI]
                mods_Page\\Columns="AAAA"
                """);
        return dir;
    }

    private static List<String> uids(PrismInstance instance) {
        List<String> uids = new ArrayList<>();
        instance.pack.getAsJsonArray("components").forEach(c -> uids.add(c.getAsJsonObject().get("uid").getAsString()));
        return uids;
    }

    @Test
    void addsForgeRightAfterMinecraft(@TempDir Path dir) throws Exception {
        PrismInstance instance = PrismInstance.read(instance(dir, """
                {"uid": "org.lwjgl", "version": "2.9.4-nightly-20150209"}, {"uid": "net.minecraft", "version": "1.7.10"},
                {"uid": "com.mumfrey.liteloader", "version": "1.7.10"}"""));
        assertNull(instance.problem());
        assertEquals("add Forge 10.13.4.1614", instance.forgeChange(true));
        assertNull(instance.forgeChange(false), "without Forge wanted, a vanilla instance stays vanilla");
        instance.ensureForge(true);
        assertEquals(List.of("org.lwjgl", "net.minecraft", "net.minecraftforge", "com.mumfrey.liteloader"), uids(instance));
        assertEquals("10.13.4.1614", instance.version(PrismInstance.FORGE));
        assertNull(instance.forgeChange(true));
    }

    @Test
    void switchesAnotherForgeBuildEvenWhenForgeIsntAskedFor(@TempDir Path dir) throws Exception {
        PrismInstance instance = PrismInstance.read(instance(dir, """
                {"uid": "net.minecraft", "version": "1.8.9"},
                {"uid": "net.minecraftforge", "version": "11.15.1.1722", "cachedVersion": "11.15.1.1722"}"""));
        assertTrue(instance.forgeChange(false).contains("11.15.1.1722 to 11.15.1.2318"));
        instance.ensureForge(false);
        instance.writePack();
        String pack = Files.readString(dir.resolve("mmc-pack.json"));
        assertTrue(pack.contains("11.15.1.2318"));
        assertFalse(pack.contains("1722"), "Prism's cached metadata of the old build goes too");
    }

    @Test
    void refusesWhatLichCantRunWith(@TempDir Path dir) throws Exception {
        assertTrue(PrismInstance.read(instance(dir, "{\"uid\": \"net.minecraft\", \"version\": \"1.12.2\"}"))
                .problem().contains("1.12.2"));
        assertTrue(PrismInstance.read(instance(dir, """
                {"uid": "net.minecraft", "version": "1.8.9"}, {"uid": "net.fabricmc.fabric-loader", "version": "0.16.0"}"""))
                .problem().contains("Fabric"));
        assertTrue(PrismInstance.read(instance(dir, """
                {"uid": "org.lwjgl3", "version": "3.3.3"}, {"uid": "net.minecraft", "version": "1.8.9"}"""))
                .problem().contains("LWJGL 3"));
    }

    @Test
    void setsJavaInGeneralAndKeepsTheRest(@TempDir Path dir) throws Exception {
        PrismInstance instance = PrismInstance.read(instance(dir, "{\"uid\": \"net.minecraft\", \"version\": \"1.8.9\"}"));
        instance.useJava(Path.of("/opt/jdk-25/bin/java"));
        List<String> lines = Files.readAllLines(dir.resolve("instance.cfg"));
        int ui = lines.indexOf("[UI]");
        assertTrue(lines.indexOf("IgnoreJavaCompatibility=true") > 0 && lines.indexOf("IgnoreJavaCompatibility=true") < ui);
        assertTrue(lines.contains("AutomaticJava=false"));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("JavaPath=") && l.endsWith("/opt/jdk-25/bin/java")));
        assertFalse(lines.contains("JavaVersion=1.8.0_51"), "Prism checks the new Java again");
        assertTrue(lines.contains("mods_Page\\Columns=\"AAAA\""));
        assertEquals("My Pack", instance.name());

        instance.resetJava();
        assertTrue(Files.readAllLines(dir.resolve("instance.cfg")).contains("AutomaticJava=true"));
    }

    @Test
    void createsAForgeInstance(@TempDir Path instances) throws Exception {
        PrismInstance created = PrismInstance.create(instances.resolve("new"), "1.8.9 Forge (Lich)", "1.8.9", true);
        PrismInstance read = PrismInstance.read(created.dir);
        assertEquals(List.of("org.lwjgl", "net.minecraft", "net.minecraftforge"), uids(read));
        assertEquals("11.15.1.2318", read.version(PrismInstance.FORGE));
        assertEquals("1.8.9 Forge (Lich)", read.name());
        assertTrue(PrismInstance.isInstance(created.dir));
    }

    @Test
    void findsTheInstancesFolderAndAFreeName(@TempDir Path data) throws Exception {
        Files.writeString(data.resolve("prismlauncher.cfg"), "[General]\nInstanceDir=my instances\n");
        assertTrue(PrismLauncher.isDataDir(data));
        assertEquals(data.resolve("my instances"), PrismLauncher.instancesDir(data));
        Files.createDirectories(data.resolve("my instances/1.8.9 (Lich)"));
        assertEquals(data.resolve("my instances/1.8.9 (Lich) (2)"), PrismLauncher.newInstanceDir(data, "1.8.9 (Lich)"));
        assertEquals(data.resolve("my instances/a_b"), PrismLauncher.newInstanceDir(data, "a/b"));
    }

    @Test
    void payloadRoundTrips(@TempDir Path dir, @TempDir Path build) throws Exception {
        Path boot = Files.write(build.resolve("boot.jar"), new byte[]{1, 2, 3});
        Path mcp = Files.write(build.resolve("mcp.zip"), new byte[]{4});
        Payload.write(dir, "1.8.9", "1.0", List.of("--enable-native-access=ALL-UNNAMED"),
                Map.of("dev.lich:lich-boot:1.0", boot, "de.oceanlabs.mcp:mcp:1.8.9:srg@zip", mcp));
        Payload payload = Payload.read(dir, "1.8.9");
        assertEquals("1.0", payload.version());
        for (Library library : payload.libraries()) {
            switch (library) {
                case Library.Local local -> assertTrue(Files.isRegularFile(local.file()));
                case Library.Remote remote -> {
                    assertEquals("https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp/1.8.9/mcp-1.8.9-srg.zip", remote.url());
                    assertEquals(1, remote.size());
                }
            }
        }
        assertNull(Payload.read(dir, "1.7.10"));
        assertTrue(JsonParser.parseString(Files.readString(dir.resolve("1.8.9.json"))).isJsonObject());
    }
}

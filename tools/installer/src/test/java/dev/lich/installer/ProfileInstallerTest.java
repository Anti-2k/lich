package dev.lich.installer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProfileInstallerTest {
    @Test
    void writesAProfileThatInheritsFromVanilla(@TempDir Path root, @TempDir Path build) throws Exception {
        Path jar = Files.write(build.resolve("lich-boot-1.0.jar"), new byte[]{1, 2, 3});
        Path json = ProfileInstaller.install("1.8.9", root, List.of("--enable-native-access=ALL-UNNAMED"),
                Map.of("dev.lich:lich-boot:1.0", jar));

        assertEquals(root.resolve("versions/lich-1.8.9/lich-1.8.9.json"), json);
        JsonObject profile = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
        assertEquals("lich-1.8.9", profile.get("id").getAsString());
        assertEquals("1.8.9", profile.get("inheritsFrom").getAsString());
        assertEquals(ProfileInstaller.MAIN_CLASS, profile.get("mainClass").getAsString());
        assertEquals("--enable-native-access=ALL-UNNAMED",
                profile.getAsJsonObject("arguments").getAsJsonArray("jvm").get(0).getAsString());
        assertFalse(profile.has("minecraftArguments"), "game arguments are inherited");

        JsonObject artifact = profile.getAsJsonArray("libraries").get(0).getAsJsonObject()
                .getAsJsonObject("downloads").getAsJsonObject("artifact");
        String path = "dev/lich/lich-boot/1.0/lich-boot-1.0.jar";
        assertEquals(path, artifact.get("path").getAsString());
        assertEquals("7037807198c22a7d2b0807371d763779a84fdfcf", artifact.get("sha1").getAsString());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(root.resolve("libraries").resolve(path)));
    }

    @Test
    void mavenPathsKeepTheClassifier() {
        assertEquals("org/lwjgl/lwjgl/3.3.6/lwjgl-3.3.6.jar", ProfileInstaller.mavenPath("org.lwjgl:lwjgl:3.3.6"));
        assertEquals("org/lwjgl/lwjgl-glfw/3.3.6/lwjgl-glfw-3.3.6-natives-windows.jar",
                ProfileInstaller.mavenPath("org.lwjgl:lwjgl-glfw:3.3.6:natives-windows"));
    }

    @Test
    void flagFilesSkipCommentsAndBlankLines(@TempDir Path dir) throws Exception {
        Path flags = Files.writeString(dir.resolve("jvm-flags.txt"), "# comment\n\n  --a=b  \n--c=d\n");
        assertEquals(List.of("--a=b", "--c=d"), ProfileInstaller.readFlags(flags));
    }
}

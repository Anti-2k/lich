package dev.lich.boot;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProfileTest {
    @Test
    void findsTweakClassesInBothSpellings() {
        String[] args = {"--username", "Dev", "--tweakClass", "a.B", "--tweakClass=c.D", "--version", "1.8.9"};
        assertEquals(List.of("a.B", "c.D"), Profile.tweakClasses(args));
    }

    @Test
    void forgeIsDetectedByFmlsTweakerOnEitherVersion() {
        assertEquals(Profile.FORGE, Profile.detect(List.of("net.minecraftforge.fml.common.launcher.FMLTweaker")));
        assertEquals(Profile.FORGE, Profile.detect(List.of("cpw.mods.fml.common.launcher.FMLTweaker")));
        assertEquals(Profile.VANILLA, Profile.detect(List.of()));
        assertEquals(Profile.VANILLA, Profile.detect(List.of("optifine.OptiFineTweaker")));
    }

    @Test
    void minecraftVersionComesFromTheMappingsThenTheTweakerThenTheAssetIndex() {
        List<Path> none = List.of();
        // 1.8.9 and 1.12.2 share FML's tweaker; 1.7.10 has its own.
        List<String> fml = List.of(Profile.FML_1_8_9);
        assertEquals("1.7.10", Profile.minecraftVersion(List.of(Profile.FML_1_7_10), none, new String[0]));
        assertEquals("1.12.2", Profile.minecraftVersion(fml, none, new String[]{"--assetIndex", "1.12"}));
        assertEquals("1.8.9", Profile.minecraftVersion(fml, none, new String[]{"--assetIndex=1.8"}));
        assertEquals("1.8.9", Profile.minecraftVersion(fml, none, new String[0]), "a launcher without an asset index: the older one");
        // The mappings name it exactly, and are all there is without Forge.
        Path mappings = Path.of("libraries", "de", "mcp-1.12.2-srg.zip");
        assertEquals("1.12.2", Profile.minecraftVersion(fml, List.of(mappings), new String[]{"--assetIndex", "1.8"}));
        assertEquals("1.12.2", Profile.minecraftVersion(List.of(), List.of(mappings), new String[0]));
        assertNull(Profile.minecraftVersion(List.of(), none, new String[]{"--assetIndex", "1.12"}));
    }

    @Test
    void vanillaTweakerPutsBackTheOptionsLaunchWrapperConsumed() {
        LichVanillaTweaker tweaker = new LichVanillaTweaker();
        File gameDir = new File("game").getAbsoluteFile();
        File assetsDir = new File("assets").getAbsoluteFile();
        tweaker.acceptOptions(List.of("--username", "Dev"), gameDir, assetsDir, "1.8.9");
        assertEquals("net.minecraft.client.main.Main", tweaker.getLaunchTarget());
        assertArrayEquals(new String[]{"--username", "Dev", "--version", "1.8.9",
                "--gameDir", gameDir.getPath(), "--assetsDir", assetsDir.getPath()}, tweaker.getLaunchArguments());
    }
}

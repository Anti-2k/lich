package dev.lich.boot;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

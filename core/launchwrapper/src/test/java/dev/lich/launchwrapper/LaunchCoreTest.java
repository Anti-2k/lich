package dev.lich.launchwrapper;

import dev.lich.launchwrapper.testtweak.FirstTweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LaunchCoreTest {
    @Test
    void runsQueuedTweakersAndCallsThePrimaryTargetThroughTheClassLoader(@TempDir Path gameDir) throws Exception {
        AtomicReference<LaunchClassLoader> hooked = new AtomicReference<>();
        String[] args = {
                "--version", "1.8.9",
                "--gameDir", gameDir.toString(),
                "--tweakClass", FirstTweaker.class.getName(),
                "--username", "Dev",
        };

        LaunchCore.launch(args, new LaunchHooks() {
            @Override
            public void classLoaderCreated(LaunchClassLoader classLoader) {
                hooked.set(classLoader);
            }
        });

        assertSame(Launch.classLoader, hooked.get());
        assertEquals(gameDir.toFile(), Launch.minecraftHome);
        assertEquals("1.8.9", FirstTweaker.profile);
        assertEquals(gameDir.toFile(), FirstTweaker.gameDir);
        // Unrecognised options reach the tweakers as plain arguments; recognised ones are consumed.
        assertEquals(LaunchClassLoader.class.getName() + "|--username Dev --fromFirst --fromSecond",
                System.getProperty("lich.test.launched"));
        assertEquals(List.of(), Launch.blackboard.get("TweakClasses"));
        assertEquals(List.of(), Launch.blackboard.get("Tweaks"));
    }
}

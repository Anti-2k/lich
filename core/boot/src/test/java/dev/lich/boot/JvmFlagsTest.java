package dev.lich.boot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The test JVM gets some of Lich's flags (see build.gradle.kts), in both spellings, and not the rest. */
class JvmFlagsTest {
    @Test
    void readsTheFlagList() {
        List<String> required = JvmFlags.required();
        assertTrue(required.contains("--add-opens=java.base/java.lang=ALL-UNNAMED"));
        assertTrue(required.stream().noneMatch(flag -> flag.startsWith("#") || flag.isBlank()));
    }

    @Test
    void detectsPresentAndMissingFlagsByEffect() {
        List<String> missing = JvmFlags.missing(List.of(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                // Given as two arguments ("--add-exports", "...") but still detected.
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED",
                "--enable-native-access=ALL-UNNAMED",
                "--sun-misc-unsafe-memory-access=allow",
                "--some-unknown-flag=x"));
        assertEquals(List.of(
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED",
                "--some-unknown-flag=x"), missing);
    }
}

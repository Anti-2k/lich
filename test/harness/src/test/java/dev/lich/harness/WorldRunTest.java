package dev.lich.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The harness's side of a world run: the driver's log lines, the game log tail and crash reports. */
class WorldRunTest {
    @Test
    void driverEventsComeOutOfLogLines() {
        DriverEvent stats = DriverEvent.parse("[12:00:01] [Lich harness driver/INFO]: LICH-HARNESS stats phase=spawn "
                + "chunks=625 entities=80 tileEntities=12 mspt=21.4");
        assertNotNull(stats);
        assertEquals("stats", stats.name());
        assertEquals(Map.of("phase", "spawn", "chunks", "625", "entities", "80", "tileEntities", "12", "mspt", "21.4"),
                stats.values());

        DriverEvent failed = DriverEvent.parse("[12:00:02] [Lich harness driver/ERROR]: LICH-HARNESS failed "
                + "java.lang.IllegalStateException: Left the world unexpectedly, screen x.Gui");
        assertNotNull(failed);
        assertEquals("failed", failed.name());
        assertEquals("failed java.lang.IllegalStateException: Left the world unexpectedly, screen x.Gui", failed.text());

        assertNull(DriverEvent.parse("[12:00:03] [Client thread/INFO]: Sound engine started"));
    }

    @Test
    void logTailReturnsOnlyCompleteNewLines(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("latest.log");
        GameRun.LogTail tail = new GameRun.LogTail(log);
        assertEquals(List.of(), tail.newLines());

        Files.writeString(log, "one\ntwo\nthr");
        assertEquals(List.of("one", "two"), tail.newLines());
        Files.writeString(log, "ee\nfour\n", StandardOpenOption.APPEND);
        assertEquals(List.of("three", "four"), tail.newLines());
        assertEquals(List.of(), tail.newLines());

        // The game rotates the log at startup.
        Files.writeString(log, "new\n");
        assertEquals(List.of("new"), tail.newLines());
    }

    @Test
    void crashSummaryNamesTheDescriptionAndException(@TempDir Path dir) throws IOException {
        Path report = dir.resolve("crash-2026-10-27_11.32.23-server.txt");
        Files.writeString(report, """
                ---- Minecraft Crash Report ----
                // Uh... Did I do that?

                Time: 27/10/26, 11:32 am
                Description: Exception while ticking a block

                java.lang.RuntimeException
                \tat net.minecraftforge.common.util.EnumHelper.addEnum(EnumHelper.java:279)
                """, StandardCharsets.ISO_8859_1);
        assertEquals(": Exception while ticking a block: java.lang.RuntimeException", GameRun.crashSummary(report));

        Files.writeString(report, "---- Minecraft Crash Report ----\n");
        assertEquals("", GameRun.crashSummary(report));
    }

    /** The driver runs inside the game on Java 8 too, and LaunchWrapper loads it by name as a tweaker. */
    @Test
    void theDriverJarHoldsJava8Classes() throws IOException {
        try (InputStream in = GameRun.class.getResourceAsStream("harness-driver.jar")) {
            assertNotNull(in, "harness-driver.jar isn't in the harness's resources");
            JarInputStream jar = new JarInputStream(in);
            boolean found = false;
            for (JarEntry entry; (entry = jar.getNextJarEntry()) != null; ) {
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                DataInputStream data = new DataInputStream(jar);
                data.readInt(); // magic
                data.readUnsignedShort(); // minor
                assertEquals(52, data.readUnsignedShort(), entry.getName() + " isn't a Java 8 class");
                found |= entry.getName().equals("dev/lich/harness/driver/WorldDriver.class");
            }
            assertEquals(true, found, "no WorldDriver in harness-driver.jar");
        }
    }
}

package dev.lich.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The harness's side of a run: the driver's lines, the game's options, crash reports, and the driver jar. */
class GameRunTest {
    @Test
    void eventsComeOutOfTheDriversLines() {
        Event end = Event.parse("end spawn settled=true settleSeconds=4 chunks=625 mspt=21.4");
        assertNotNull(end);
        assertEquals("end", end.type());
        assertEquals("spawn", end.step());
        assertEquals(Map.of("settled", "true", "settleSeconds", "4", "chunks", "625", "mspt", "21.4"), end.values());

        Event fail = Event.parse("fail tour -- java.lang.IllegalStateException: Left the world unexpectedly (screen x.Gui)");
        assertNotNull(fail);
        assertEquals("java.lang.IllegalStateException: Left the world unexpectedly (screen x.Gui)", fail.text());
        assertEquals(Map.of(), fail.values());

        Event problem = Event.parse("problem mods mod=foo -- config screen: java.lang.NoSuchMethodError: a.b()V");
        assertNotNull(problem);
        assertEquals("mod=foo: config screen: java.lang.NoSuchMethodError: a.b()V", problem.describe());

        assertNull(Event.parse("garbage"));
    }

    @Test
    void bothProfilesGetTheSameQuietOptions(@TempDir Path dir) throws IOException {
        Path options = dir.resolve("options.txt");
        Files.writeString(options, "renderDistance:16\nlang:de_DE\nfullscreen:true\n", StandardCharsets.ISO_8859_1);
        GameLauncher.options(options);
        List<String> lines = Files.readAllLines(options, StandardCharsets.ISO_8859_1);
        assertTrue(lines.contains("lang:de_DE"), "the pack's own settings stay");
        assertTrue(lines.containsAll(List.of("renderDistance:8", "fullscreen:false", "pauseOnLostFocus:false",
                "soundCategory_master:0.0", "maxFps:30")), lines.toString());
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
        try (InputStream in = GameLauncher.class.getResourceAsStream("harness-driver.jar")) {
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
                found |= entry.getName().equals("dev/lich/harness/driver/Driver.class");
            }
            assertTrue(found, "no Driver in harness-driver.jar");
        }
    }

    @Test
    void theSummarySaysWhatChangedSinceThePreviousRun() {
        Pack pack = Pack.get("compact-claustrophobia");
        Verdict pass = new Verdict(Verdict.Kind.PASS, "completed like Forge", List.of());
        Verdict fail = new Verdict(Verdict.Kind.FAIL, "Lich crashed", List.of());
        RunResult run = Runs.completed("lich-forge");
        History.Entry wasFail = new History.Entry("t", "compact-claustrophobia", "a", "FAIL", "", 1, true);
        History.Entry wasPass = new History.Entry("t", "compact-claustrophobia", "a", "PASS", "", 1, true);
        assertEquals("first run", Summary.change(new Summary.Row(pack, pass, run, true, null, Path.of("r"))));
        assertEquals("**fixed**", Summary.change(new Summary.Row(pack, pass, run, true, wasFail, Path.of("r"))));
        assertEquals("**regressed**", Summary.change(new Summary.Row(pack, fail, run, true, wasPass, Path.of("r"))));
        assertEquals("same", Summary.change(new Summary.Row(pack, pass, run, true, wasPass, Path.of("r"))));
    }

    @Test
    void theReportRendersBothRuns(@TempDir Path dir) throws IOException {
        RunResult forge = Runs.completed("forge");
        RunResult lich = Runs.failed("lich-forge", "tour", RunResult.Ending.CRASHED, "crash.txt: Ticking | it");
        Path report = Report.write(dir.resolve("report.md"), Pack.get("compact-claustrophobia"), 19, Verdict.of(lich, forge), lich, forge, true);
        String text = Files.readString(report);
        assertTrue(text.startsWith("# Compact Claustrophobia 1.3.5.2: ❌ FAIL"), text);
        assertTrue(text.contains("| tour | ✓ 10 s | ✗ 10 s: crash.txt: Ticking / it |"), text);
        assertTrue(text.contains("| reload | ✓ 10 s | · |"), text);
    }
}

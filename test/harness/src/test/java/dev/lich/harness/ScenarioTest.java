package dev.lich.harness;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScenarioTest {
    private static final Pack PACK = Pack.parse("x", JsonParser.parseString("""
            {"name": "X", "mc": "1.8.9", "forge": "11.15.1.2318", "memoryMb": 3072,
             "source": {"type": "ftb", "pack": "23", "version": "99"}}
            """).getAsJsonObject());

    @Test
    void theFullScenarioLeavesOutTheGalleryUnlessThePackAsks() {
        assertEquals(List.of("boot", "mods", "server", "world", "spawn", "tour", "inventory", "hold", "save", "reload", "exit"),
                Scenario.of(PACK, null, 20, true, null).steps());
        assertEquals(List.of("boot", "mods", "world", "spawn", "tour", "inventory", "gallery", "hold", "save", "reload", "exit"),
                Scenario.of(PACK, null, 20, false, true).steps());
    }

    @Test
    void untilCutsAfterTheStepButAlwaysExits() {
        Scenario boot = Scenario.of(PACK, "boot", 20, true, null);
        assertEquals(List.of("boot", "exit"), boot.steps());
        assertEquals(List.of("boot"), boot.driverSteps());
        assertEquals("boot", boot.properties(PACK, 1234, 25599).get("lich.harness.steps"));
        assertThrows(IllegalArgumentException.class, () -> Scenario.of(PACK, "nap", 20, true, null));
    }

    @Test
    void theDriverGetsItsSettings() {
        Map<String, String> p = Scenario.of(PACK, null, 30, true, null).properties(PACK, 1234, 25599);
        assertEquals("1234", p.get("lich.harness.port"));
        assertEquals("127.0.0.1:25599", p.get("lich.harness.server"));
        assertEquals("30", p.get("lich.harness.hold"));
        assertEquals("default", p.get("lich.harness.worldType"));
        assertNull(p.get("lich.harness.save"));
    }

    @Test
    void aBaselineTightensTheDeadlines() {
        Scenario scenario = Scenario.of(PACK, null, 20, false, null);
        assertEquals(Duration.ofSeconds(900), scenario.deadline("boot", null));
        // A 30 s boot on Forge: 3 × 30 + 60 s.
        assertEquals(Duration.ofSeconds(150), scenario.deadline("boot", Runs.withStep(Runs.completed("forge"),
                new RunResult.Step("boot", RunResult.Status.PASSED, 0, 30, Map.of(), "", List.of()))));
        // Never below two minutes, never above the default.
        assertEquals(Duration.ofSeconds(120), scenario.deadline("mods", Runs.completed("forge")));
        assertEquals(Duration.ofSeconds(30), scenario.deadline("exit", Runs.completed("forge")));
    }

    @Test
    void aBaselineIsReusedOnlyForTheSameKeyAndSteps(@TempDir Path dir) throws IOException {
        Scenario full = Scenario.of(PACK, null, 20, false, null);
        RunResult stored = withSteps(Runs.completed("forge"), full.steps(), "k1");
        stored.write(Baseline.file(dir));
        assertNotNull(Baseline.usable(dir, "k1", full));
        assertNull(Baseline.usable(dir, "k2", full), "another key");
        assertNotNull(Baseline.usable(dir, "k1", Scenario.of(PACK, "mods", 20, false, null)), "fewer steps compare");
        assertNull(Baseline.usable(dir, "k1", Scenario.of(PACK, null, 20, false, true)), "a gallery the baseline didn't play");
    }

    @Test
    void aBaselineCutToFewerStepsForgetsALaterFailure() {
        Scenario full = Scenario.of(PACK, null, 20, false, null);
        RunResult crashedInTour = withSteps(Runs.failed("forge", "tour", RunResult.Ending.CRASHED, "boom"), full.steps(), "k");
        RunResult cut = Baseline.cut(crashedInTour, Scenario.of(PACK, "mods", 20, false, null));
        assertEquals(List.of("boot", "mods", "exit"), cut.steps().stream().map(RunResult.Step::name).toList());
        assertEquals(RunResult.Ending.COMPLETED, cut.ending());
        assertEquals(Verdict.Kind.PASS, Verdict.of(withSteps(Runs.completed("lich-forge"), cut.steps().stream()
                .map(RunResult.Step::name).toList(), null), cut).kind());

        RunResult crashedInMods = withSteps(Runs.failed("forge", "mods", RunResult.Ending.CRASHED, "boom"), full.steps(), "k");
        assertEquals(RunResult.Ending.CRASHED, Baseline.cut(crashedInMods, Scenario.of(PACK, "mods", 20, false, null)).ending());
    }

    @Test
    void theKeyFollowsTheDriversClassesNotTheJarsTimestamps(@TempDir Path dir) throws IOException {
        Path profile = Files.writeString(dir.resolve("forge-1.8.9.json"), "{}");
        Path java8 = dir.resolve("java.exe");
        Scenario scenario = Scenario.of(PACK, null, 20, true, null);
        Path a = jar(dir.resolve("a.jar"), 1_000L, "class");
        Path b = jar(dir.resolve("b.jar"), 9_000_000L, "class");
        Path c = jar(dir.resolve("c.jar"), 1_000L, "other class");
        assertEquals(Baseline.key(PACK, scenario, a, profile, java8), Baseline.key(PACK, scenario, b, profile, java8));
        assertNotEquals(Baseline.key(PACK, scenario, a, profile, java8), Baseline.key(PACK, scenario, c, profile, java8));
        assertNotEquals(Baseline.key(PACK, scenario, a, profile, java8),
                Baseline.key(PACK, Scenario.of(PACK, null, 60, true, null), a, profile, java8), "another hold");
    }

    private static Path jar(Path file, long time, String content) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            JarEntry entry = new JarEntry("dev/lich/harness/driver/Driver.class");
            entry.setTime(time);
            out.putNextEntry(entry);
            out.write(content.getBytes());
            out.closeEntry();
        }
        return file;
    }

    /** {@code run} with exactly these steps, keeping each one's status from {@link Runs}. */
    private static RunResult withSteps(RunResult run, List<String> names, String key) {
        List<RunResult.Step> steps = names.stream().map(name -> {
            RunResult.Step step = run.step(name);
            return step != null ? step : new RunResult.Step(name, run.ending() == RunResult.Ending.COMPLETED
                    ? RunResult.Status.PASSED : RunResult.Status.NOT_REACHED, 0, 1, Map.of(), "", List.of());
        }).toList();
        return new RunResult(run.profile(), run.startedAt(), run.lich(), run.seconds(), steps, run.ending(), run.detail(),
                run.progress(), run.gameDir(), run.log(), key);
    }
}

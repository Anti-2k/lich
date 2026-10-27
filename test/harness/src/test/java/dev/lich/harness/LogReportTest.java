package dev.lich.harness;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogReportTest {
    private static final Path GAME = Path.of("C:/run/harness/pack/lich-forge").toAbsolutePath();

    @Test
    void warningsKeepTheirExceptionButNotTimesThreadsOrHashes() {
        LogReport report = LogReport.parse(List.of(
                "[10:00:01] [Client thread/INFO]: Forge Mod Loader has successfully loaded 183 mods",
                "[10:00:02] [Client thread/WARN]: Failed to load " + GAME + "\\config\\x.cfg for Foo@1a2b3c in 42 ms",
                "java.io.FileNotFoundException: x.cfg",
                "\tat foo.Bar.baz(Bar.java:1)",
                "Caused by: java.lang.Error: inner",
                "[10:00:03] [pool-2-thread-1/ERROR]: Boom",
                "[10:00:04] [main/INFO]: Mixing MixinFoo from mixins.foo.json into net.minecraft.client.Minecraft",
                "[10:00:05] [main/INFO]: Compat: java/lang/SecurityManager in Foo",
                "[10:00:06] [Server thread/ERROR]: Boom"), GAME);

        assertEquals(183, report.modsLoaded());
        assertEquals(Map.of(
                "WARN Failed to load <game>\\config\\x.cfg for Foo@… in #ms | java.io.FileNotFoundException: x.cfg", 1,
                "ERROR Boom", 2), report.problems());
        assertEquals("MixinFoo from mixins.foo.json into net.minecraft.client.Minecraft", report.mixins().first());
        assertEquals(Map.of("Compat: java/lang/SecurityManager in Foo", 1), report.lichLines());
    }

    @Test
    void whatJava8AndJava25PrintDifferentlyComparesEqual() {
        List<String> none = List.of();
        assertEquals("ERROR x | java.lang.NullPointerException",
                LogReport.normalize("ERROR x | java.lang.NullPointerException: Cannot invoke \"a.B.c()\" because \"d\" is null", none));
        assertEquals("*  at <reflection>...",
                LogReport.normalize("*  at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)...", none));
        assertEquals("*  at <reflection>...", LogReport.normalize(
                "*  at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)...", none));
        assertEquals("java.lang.NullPointerException: name", LogReport.normalize("java.lang.NullPointerException: name", none),
                "a message the mod gave stays");
    }

    @Test
    void moreInCountsOnlyTheExtraOccurrences() {
        Map<String, Integer> lich = Map.of("ERROR a", 3, "WARN b", 1);
        Map<String, Integer> forge = Map.of("ERROR a", 1, "WARN b", 1, "WARN c", 1);
        assertEquals(Map.of("ERROR a", 2), LogReport.moreIn(lich, forge));
        assertEquals(Map.of("WARN c", 1), LogReport.moreIn(forge, lich));
    }

    @Test
    void anEmptyLogHasNoProblems() {
        LogReport report = LogReport.parse(List.of(), GAME);
        assertEquals(null, report.modsLoaded());
        assertTrue(report.problems().isEmpty());
    }
}

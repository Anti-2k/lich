package dev.lich.harness;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogDigestTest {
    private static final Path GAME = Path.of("C:/run/harness/pack/lich-forge").toAbsolutePath();

    @Test
    void warningsKeepTheirExceptionButNotTimesThreadsOrHashes() {
        LogDigest digest = LogDigest.parse(List.of(
                "[10:00:01] [Client thread/INFO]: Forge Mod Loader has successfully loaded 183 mods",
                "[10:00:02] [Client thread/WARN]: Failed to load " + GAME + "\\config\\x.cfg for Foo@1a2b3c in 42 ms",
                "java.io.FileNotFoundException: x.cfg",
                "\tat foo.Bar.baz(Bar.java:1)",
                "Caused by: java.lang.Error: inner",
                "[10:00:03] [pool-2-thread-1/ERROR]: Boom",
                "[10:00:04] [main/INFO]: Mixing MixinFoo from mixins.foo.json into net.minecraft.client.Minecraft",
                "[10:00:05] [main/INFO]: Compat: java/lang/SecurityManager in Foo",
                "[10:00:06] [Server thread/ERROR]: Boom"), GAME);

        assertEquals(183, digest.modsLoaded());
        assertEquals(Map.of(
                "WARN Failed to load <game>\\config\\x.cfg for Foo@… in #ms | java.io.FileNotFoundException: x.cfg", 1,
                "ERROR Boom", 2), digest.problems());
        assertEquals(List.of("MixinFoo from mixins.foo.json into net.minecraft.client.Minecraft"), digest.mixins());
        assertEquals(Map.of("Compat: java/lang/SecurityManager in Foo", 1), digest.lichLines());
        assertFalse(digest.outOfMemory());
    }

    @Test
    void theDriversOwnLinesAreNotProblems() {
        LogDigest digest = LogDigest.parse(List.of(
                "[10:00:01] [Lich harness driver/ERROR]: LICH-HARNESS fail tour -- java.lang.IllegalStateException: x"), GAME);
        assertTrue(digest.problems().isEmpty());
    }

    @Test
    void forgesLayoutWithTheLoggersNameDigestsLikeMojangs() {
        LogDigest forge = LogDigest.parse(List.of("[10:00:01] [Client thread/WARN] [FML]: Boom",
                "[10:00:02] [Client thread/INFO] [FML]: Forge Mod Loader has successfully loaded 183 mods",
                "[10:00:03] [main/INFO] [mixin]: Mixing MixinFoo from mixins.foo.json into net.minecraft.client.Minecraft",
                "[10:00:04] [Lich harness driver/ERROR] [LichHarness]: LICH-HARNESS fail tour -- x"), GAME);
        LogDigest lich = LogDigest.parse(List.of("[10:00:01] [Client thread/WARN]: Boom",
                "[10:00:02] [Client thread/INFO]: Forge Mod Loader has successfully loaded 183 mods",
                "[10:00:03] [main/INFO]: Mixing MixinFoo from mixins.foo.json into net.minecraft.client.Minecraft",
                "[10:00:04] [Lich harness driver/ERROR]: LICH-HARNESS fail tour -- x"), GAME);
        assertEquals(lich, forge);
        assertEquals(1, forge.problemCount());
    }

    @Test
    void anOutOfMemoryErrorAnywhereIsNoticed() {
        LogDigest digest = LogDigest.parse(List.of("[10:00:01] [Server thread/ERROR]: Encountered an unexpected exception",
                "java.lang.OutOfMemoryError: Java heap space"), GAME);
        assertTrue(digest.outOfMemory());
    }

    @Test
    void whatJava8AndJava25PrintDifferentlyComparesEqual() {
        List<String> none = List.of();
        assertEquals("ERROR x | java.lang.NullPointerException",
                LogDigest.normalize("ERROR x | java.lang.NullPointerException: Cannot invoke \"a.B.c()\" because \"d\" is null", none));
        assertEquals("*  at <reflection>...",
                LogDigest.normalize("*  at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)...", none));
        assertEquals("*  at <reflection>...", LogDigest.normalize(
                "*  at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)...", none));
        assertEquals("java.lang.NullPointerException: name", LogDigest.normalize("java.lang.NullPointerException: name", none),
                "a message the mod gave stays");
        // A config screen failing in the driver, as each Java tells it (JEI's, without a connection).
        assertEquals("mod=JEI: config screen: x: java.lang.NullPointerException caused by java.lang.NullPointerException",
                LogDigest.normalize("mod=JEI: config screen: x: java.lang.NullPointerException: Cannot invoke \"a.B.c()\" "
                        + "because \"networkManager\" is null caused by java.lang.NullPointerException: Cannot invoke \"a.B.c()\" "
                        + "because \"networkManager\" is null", none));
        // ShadowTweaks' networkCheckHandler warning, as each Java prints it.
        assertEquals(LogDigest.normalize("java.lang.NoSuchMethodException: a.B.check(java.util.Map,net.x.Side)", none),
                LogDigest.normalize("java.lang.NoSuchMethodException: a.B.check(java.util.Map, net.x.Side)", none));
        assertEquals("a.B.m(int[],java.lang.String)", LogDigest.normalize("a.B.m(int[], java.lang.String)", none));
    }

    @Test
    void maskingMakesLinesThatDifferInNumbersEqual() {
        assertEquals("WARN Skipping entity at #, #, # in dim #", LogDigest.masked("WARN Skipping entity at 12.5, 64, -300 in dim 0"));
        LogDigest digest = new LogDigest(1, Map.of("WARN tick 1 took", 2, "WARN tick 7 took", 1), List.of(), Map.of(), false);
        assertEquals(Map.of("WARN tick # took", 3), digest.maskedProblems());
    }

    @Test
    void moreInCountsOnlyTheExtraOccurrences() {
        Map<String, Integer> lich = Map.of("ERROR a", 3, "WARN b", 1);
        Map<String, Integer> forge = Map.of("ERROR a", 1, "WARN b", 1, "WARN c", 1);
        assertEquals(Map.of("ERROR a", 2), LogDigest.moreIn(lich, forge));
        assertEquals(Map.of("WARN c", 1), LogDigest.moreIn(forge, lich));
    }

    @Test
    void triageFlagsJava25ProblemsButNotOrdinaryNoise() {
        assertTrue(Triage.suspect("ERROR x | java.lang.reflect.InaccessibleObjectException: Unable to make field private"));
        assertTrue(Triage.suspect("WARN Failed | java.lang.ClassCastException: jdk.internal.loader.ClassLoaders$AppClassLoader"));
        assertTrue(Triage.suspect("ERROR Couldn't read | java.lang.NoClassDefFoundError: javax/xml/bind/DatatypeConverter"));
        assertFalse(Triage.suspect("WARN Unable to find spawn biome"));
        assertFalse(Triage.suspect("WARN Missing texture modid:foo"));
    }
}

package dev.lich.harness;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackTest {
    @Test
    void everyPinnedPackHasAKnownSourceWithItsKeys() {
        Map<String, List<String>> keys = Map.of(
                "mrpack", List.of("url", "sha512"),
                "solder", List.of("api", "slug", "build"),
                "ftb", List.of("pack", "version"),
                "zip", List.of("url", "sha512"),
                "atlauncher", List.of("pack", "version"));
        for (Pack pack : Pack.all().values()) {
            List<String> needed = keys.get(pack.source().type());
            assertTrue(needed != null, pack.id() + ": unknown source type " + pack.source().type());
            needed.forEach(key -> pack.source().get(key));
            assertTrue(Set.of("1.7.10", "1.8.9", "1.12.2").contains(pack.mc()), pack.id() + ": " + pack.mc());
            assertTrue(!pack.source().describe().isBlank());
        }
    }

    @Test
    void sourcesParseWithTheirPinAndDefaults() {
        Pack pack = Pack.parse("x", JsonParser.parseString("""
                {"name": "X", "mc": "1.8.9", "forge": "11.15.1.2318", "memoryMb": 3072,
                 "source": {"type": "ftb", "pack": "23", "version": "99"}}
                """).getAsJsonObject());
        assertEquals("ftb", pack.source().type());
        assertEquals("99", pack.source().get("version"));
        assertEquals("screen", pack.worldType());
        assertNull(pack.save());

        Pack other = Pack.parse("x", JsonParser.parseString("""
                {"name": "X", "mc": "1.8.9", "forge": "11.15.1.2318", "memoryMb": 3072,
                 "source": {"type": "ftb", "pack": "23", "version": "100"}}
                """).getAsJsonObject());
        assertNotEquals(pack.fingerprint(), other.fingerprint(), "a new pin must rebuild the template");
        assertEquals(List.of(), pack.tags());
        assertNull(pack.skip());
        assertNull(pack.noServer());
        assertTrue(!pack.gallery());

        Pack offline = Pack.parse("x", JsonParser.parseString("""
                {"name": "X", "mc": "1.12.2", "forge": "14.23.5.2859", "memoryMb": 3072,
                 "source": {"type": "ftb", "pack": "23", "version": "99"}, "noServer": "changes vanilla packets"}
                """).getAsJsonObject());
        assertEquals("changes vanilla packets", offline.noServer());
        assertNull(offline.skip(), "it still runs, without the server step");
    }

    @Test
    void removingAFileRebuildsThatPackOnly() {
        Pack plain = Pack.parse("x", JsonParser.parseString("""
                {"name": "X", "mc": "1.12.2", "forge": "14.23.5.2860", "memoryMb": 3072,
                 "source": {"type": "ftb", "pack": "23", "version": "99"}}
                """).getAsJsonObject());
        Pack removing = Pack.parse("x", JsonParser.parseString("""
                {"name": "X", "mc": "1.12.2", "forge": "14.23.5.2860", "memoryMb": 3072,
                 "source": {"type": "ftb", "pack": "23", "version": "99"},
                 "setup": {"remove": ["mods/relauncher.jar"]}}
                """).getAsJsonObject());
        assertEquals(List.of("mods/relauncher.jar"), removing.remove());
        assertEquals(List.of(), plain.remove());
        assertNotEquals(plain.fingerprint(), removing.fingerprint());
        assertTrue(!plain.fingerprint().contains("remove"), "other packs' templates stay valid");

        Pack replacing = Pack.parse("x", JsonParser.parseString("""
                {"name": "X", "mc": "1.12.2", "forge": "14.23.5.2860", "memoryMb": 3072,
                 "source": {"type": "ftb", "pack": "23", "version": "99"},
                 "setup": {"remove": ["mods/lib.jar"], "download": {"mods/lib.jar": {"url": "https://example.invalid/lib.jar", "sha512": "ab"}}}}
                """).getAsJsonObject());
        assertEquals(new Pack.Download("https://example.invalid/lib.jar", "ab"), replacing.download().get("mods/lib.jar"));
        assertTrue(plain.download().isEmpty());
        assertNotEquals(plain.fingerprint(), replacing.fingerprint());
        assertTrue(!plain.fingerprint().contains("download"), "other packs' templates stay valid");
    }

    @Test
    void selectionsNameSuitesButLeaveOutPacksThatCantRunUnattended() {
        List<Pack> quick = Pack.select("@quick");
        assertTrue(!quick.isEmpty() && quick.stream().allMatch(p -> p.tags().contains("quick")));
        List<Pack> modern = Pack.select("@1.12.2");
        assertTrue(!modern.isEmpty() && modern.stream().allMatch(p -> p.mc().equals("1.12.2")));
        assertTrue(Pack.select("all").stream().noneMatch(p -> p.skip() != null));
        assertEquals("rlcraft", Pack.select("rlcraft").getFirst().id());
        assertEquals(List.of("rlcraft", "compact-claustrophobia"),
                Pack.select("rlcraft, compact-claustrophobia,rlcraft").stream().map(Pack::id).toList());
    }
}

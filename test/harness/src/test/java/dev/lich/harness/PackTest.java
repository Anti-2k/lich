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
            assertTrue(Set.of("1.7.10", "1.8.9").contains(pack.mc()), pack.id() + ": " + pack.mc());
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
        assertEquals("default", pack.worldType());
        assertNull(pack.save());

        Pack other = Pack.parse("x", JsonParser.parseString("""
                {"name": "X", "mc": "1.8.9", "forge": "11.15.1.2318", "memoryMb": 3072,
                 "source": {"type": "ftb", "pack": "23", "version": "100"}}
                """).getAsJsonObject());
        assertNotEquals(pack.fingerprint(), other.fingerprint(), "a new pin must rebuild the template");
        assertEquals(List.of(), pack.tags());
        assertNull(pack.skip());
        assertTrue(!pack.gallery());
    }

    @Test
    void selectionsNameSuitesButLeaveOutPacksThatCantRunUnattended() {
        List<Pack> quick = Pack.select("@quick");
        assertTrue(!quick.isEmpty() && quick.stream().allMatch(p -> p.tags().contains("quick")));
        List<Pack> oneEight = Pack.select("@1.8.9");
        assertTrue(oneEight.stream().allMatch(p -> p.mc().equals("1.8.9")));
        assertTrue(Pack.select("all").stream().noneMatch(p -> p.skip() != null));
        assertEquals("ultimate-warfare", Pack.select("ultimate-warfare").getFirst().id(), "named, it still runs");
        assertEquals(List.of("cubesoft", "lost-era"), Pack.select("cubesoft, lost-era,cubesoft").stream().map(Pack::id).toList());
    }
}

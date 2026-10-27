package dev.lich.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackConfigTest {
    @Test
    void sectionsKeepValuesWithSpaces() {
        List<PackConfig.Section> sections = PackConfig.parse("""
                [Born in a Barn]
                type = modrinth
                versionId = t2p54yVF
                sourceFileName = Born in a Barn 1.7.x-1.1.jar
                destination = mods/Born in a Barn 1.7.x-1.1.jar

                # a comment
                [Antique Atlas]
                type = url
                url = https://github.com/x/y/releases/download/4.4.6/a.jar?x=1
                optional = true
                """);
        assertEquals(2, sections.size());
        assertEquals("Born in a Barn", sections.get(0).name());
        assertEquals("mods/Born in a Barn 1.7.x-1.1.jar", sections.get(0).get("destination"));
        assertEquals("https://github.com/x/y/releases/download/4.4.6/a.jar?x=1", sections.get(1).get("url"));
        assertEquals("true", sections.get(1).get("optional"));
    }

    @Test
    void packPathsStayInsideTheirDirectory(@TempDir Path root) throws IOException {
        assertEquals(root.resolve("mods/a.jar").toAbsolutePath().normalize(), Paths.inside(root, "mods/a.jar"));
        assertThrows(IOException.class, () -> Paths.inside(root, "../evil.jar"));
        assertThrows(IOException.class, () -> Paths.inside(root, "mods/../../evil.jar"));
        assertThrows(IOException.class, () -> Paths.inside(root, root.resolve("x").toAbsolutePath().toString()));
    }
}

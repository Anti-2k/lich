package dev.lich.launchwrapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageIoProvidersTest {
    private static final String WRITERS = "META-INF/services/javax.imageio.spi.ImageWriterSpi";

    @Test
    void onlyImageIoProviderListsAreFiltered() {
        assertTrue(ImageIoProviders.applies(WRITERS));
        assertFalse(ImageIoProviders.applies("META-INF/services/javax.naming.spi.InitialContextFactory"));
    }

    @Test
    void entriesForMissingClassesAreLeftOut(@TempDir Path dir) throws IOException {
        // A shaded copy that kept the original names (Dungeons Guide's TwelveMonkeys), next to a class that is there.
        Path services = dir.resolve(WRITERS);
        Files.createDirectories(services.getParent());
        Files.writeString(services, """
                # TwelveMonkeys
                com.twelvemonkeys.imageio.plugins.bmp.BMPImageWriterSpi
                java.lang.String # stands in for a provider that is there
                """);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()}, null)) {
            URL original = loader.getResource(WRITERS);
            URL filtered = ImageIoProviders.filter(original, loader);
            assertEquals("lich-filtered", filtered.getProtocol());
            assertEquals(List.of("# TwelveMonkeys", "java.lang.String # stands in for a provider that is there"), read(filtered));

            List<URL> all = Collections.list(ImageIoProviders.filter(loader.getResources(WRITERS), loader));
            assertEquals(1, all.size());
        }
    }

    @Test
    void aListWithNothingMissingIsServedAsIs(@TempDir Path dir) throws IOException {
        Path services = dir.resolve(WRITERS);
        Files.createDirectories(services.getParent());
        Files.writeString(services, "java.lang.String\n");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()}, null)) {
            URL original = loader.getResource(WRITERS);
            assertSame(original, ImageIoProviders.filter(original, loader));
        }
    }

    private static List<String> read(URL url) throws IOException {
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }
}

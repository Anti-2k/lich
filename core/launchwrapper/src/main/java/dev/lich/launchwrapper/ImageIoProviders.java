package dev.lich.launchwrapper;

import net.minecraft.launchwrapper.LogWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ImageIO finds its plugins with {@code ServiceLoader} through the context class loader, which is
 * {@code LaunchClassLoader} once the game starts. Mods that shade an ImageIO library under their own package often
 * keep its {@code META-INF/services} files, which still name the original classes (Dungeons Guide does this with
 * TwelveMonkeys). Java 8 put up with that under FML's SecurityManager: {@code IIORegistry} printed each broken entry
 * and went on. Java 24+ has no SecurityManager, and Java 25's {@code ServiceLoader} throws while it looks the class
 * up, outside {@code IIORegistry}'s catch. So the first ImageIO use anywhere fails (with Dungeons Guide and a OneConfig
 * mod, OneConfig's loader at launch, and the game exits). {@code LaunchClassLoader} serves these files with the
 * entries for classes it can't find left out, which gives the same plugins Java 8 ended up with.
 */
public final class ImageIoProviders {
    private static final String SERVICES = "META-INF/services/javax.imageio.spi.";
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private ImageIoProviders() {
    }

    public static boolean applies(String resourceName) {
        return resourceName.startsWith(SERVICES);
    }

    /** {@code found}, with each file that names a class {@code loader} can't find replaced by a copy without it. */
    public static Enumeration<URL> filter(Enumeration<URL> found, ClassLoader loader) throws IOException {
        List<URL> result = new ArrayList<>();
        while (found.hasMoreElements()) {
            result.add(filter(found.nextElement(), loader));
        }
        return Collections.enumeration(result);
    }

    static URL filter(URL file, ClassLoader loader) throws IOException {
        URLConnection connection = file.openConnection();
        connection.setUseCaches(false);
        String text;
        try (InputStream in = connection.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder kept = new StringBuilder();
        boolean dropped = false;
        for (String line : text.split("\\R")) {
            int comment = line.indexOf('#');
            String provider = (comment >= 0 ? line.substring(0, comment) : line).strip();
            // Only looks for the class file: loading it here would run the transformers out of turn.
            if (!provider.isEmpty() && loader.getResource(provider.replace('.', '/') + ".class") == null) {
                dropped = true;
                if (LOGGED.add(provider + " " + file)) {
                    LogWrapper.info("Compat: left out ImageIO provider %s, listed in %s but not there"
                            + " (Java 8 skipped it under FML's SecurityManager)", provider, file);
                }
                continue;
            }
            kept.append(line).append('\n');
        }
        if (!dropped) {
            return file;
        }
        byte[] content = kept.toString().getBytes(StandardCharsets.UTF_8);
        try {
            return URL.of(new URI("lich-filtered", file.toExternalForm(), null), new Content(content));
        } catch (URISyntaxException e) {
            throw new IOException("Can't make a URL for the filtered " + file, e);
        }
    }

    /** Serves the filtered file. */
    private static final class Content extends URLStreamHandler {
        private final byte[] content;

        Content(byte[] content) {
            this.content = content;
        }

        @Override
        protected URLConnection openConnection(URL url) {
            return new URLConnection(url) {
                @Override
                public void connect() {
                    connected = true;
                }

                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(content);
                }
            };
        }
    }
}

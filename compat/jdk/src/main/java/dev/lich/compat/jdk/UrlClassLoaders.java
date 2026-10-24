package dev.lich.compat.jdk;

import java.net.URL;
import java.util.Arrays;
import java.util.Objects;

/**
 * Java 8's {@code URLClassLoader} took {@code null} entries in its URL array and only failed if a lookup
 * reached one; Java 9+ throws in the constructor. OneConfig, when LWJGL 3 comes from the environment, makes
 * its class loader with a {@code null} jar it never looks in. The compat pass passes the array of every
 * {@code URLClassLoader} constructor call through {@link #withoutNulls}.
 */
public final class UrlClassLoaders {
    private UrlClassLoaders() {
    }

    /** {@code urls} itself if it has no {@code null}s, so nothing changes for everyone else. */
    public static URL[] withoutNulls(URL[] urls) {
        if (urls == null) {
            return null;
        }
        for (URL url : urls) {
            if (url == null) {
                return Arrays.stream(urls).filter(Objects::nonNull).toArray(URL[]::new);
            }
        }
        return urls;
    }
}

package dev.lich.compat.jdk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Class loaders for libraries that travel as jars nested inside Lich's own jar. Each loader has only
 * the platform class loader as parent, so a nested library and its dependencies never see, or clash
 * with, the older copies of the same libraries on the game's class path.
 *
 * <p>The nested jars are read into memory once, on first use: nothing is extracted to disk.
 */
final class IsolatedLibraries {
    private static final String PACK200_DIR = "/META-INF/lich/pack200/";

    private IsolatedLibraries() {
    }

    /** commons-compress (and its dependencies), for {@link Pack200}. */
    static ClassLoader pack200() {
        return Pack200Holder.LOADER;
    }

    private static final class Pack200Holder {
        static final ClassLoader LOADER = load("lich-pack200", PACK200_DIR);
    }

    private static ClassLoader load(String name, String dir) {
        Map<String, byte[]> entries = new HashMap<>();
        try (InputStream index = IsolatedLibraries.class.getResourceAsStream(dir + "index.txt")) {
            if (index == null) {
                throw new IllegalStateException("Missing " + dir + "index.txt in the Lich compat jar");
            }
            for (String jar : new String(index.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!jar.isBlank()) {
                    readJar(dir + jar.strip(), entries);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new InMemoryClassLoader(name, entries);
    }

    private static void readJar(String resource, Map<String, byte[]> entries) throws IOException {
        try (InputStream in = IsolatedLibraries.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing nested jar " + resource);
            }
            ZipInputStream zip = new ZipInputStream(in);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    buffer.reset();
                    zip.transferTo(buffer);
                    // First jar wins, like a class path.
                    entries.putIfAbsent(entry.getName(), buffer.toByteArray());
                }
            }
        }
    }

    /** Defines classes from a map of {@code a/b/C.class} → bytes. */
    private static final class InMemoryClassLoader extends ClassLoader {
        static {
            registerAsParallelCapable();
        }

        private final Map<String, byte[]> classes;

        InMemoryClassLoader(String name, Map<String, byte[]> classes) {
            super(name, ClassLoader.getPlatformClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/') + ".class");
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}

package dev.lich.boot;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * The JVM's system class loader under Lich ({@code -Djava.system.class.loader=dev.lich.boot.LichSystemClassLoader}).
 * It loads the whole class path itself, so Lich can run its compat pass on classes that LaunchWrapper's
 * exclusions keep out of {@code LaunchClassLoader}: tweakers and FML's launch code ({@code FMLTweaker}
 * calls {@code System.setSecurityManager}, which throws on Java 24+). It extends {@code URLClassLoader}
 * because FML and old mods call {@code URLClassLoader.addURL} on it by reflection (cascading tweakers),
 * as they could on Java 8's application class loader.
 *
 * <p>It also leaves out of the class path the jars Lich replaces: Mojang's LaunchWrapper and ASM older
 * than 9 (Forge's asm-all 5.0.3 can't read Java 9+ class files). It tells Lich's own jars apart, so
 * boot can keep them out of FML's mod discovery.
 *
 * <p>This class is defined by the JDK's application class loader and uses JDK classes only. Every other
 * class, Lich's boot included, is defined by this loader.
 */
public final class LichSystemClassLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    private static final List<String> DROPPED = new CopyOnWriteArrayList<>();
    private static final List<URL> LICH_JARS = new CopyOnWriteArrayList<>();
    private static volatile BiFunction<String, byte[], byte[]> transformer;

    /** Called by the JDK with the application class loader, which this loader replaces. */
    public LichSystemClassLoader(ClassLoader applicationClassLoader) {
        super("lich-system", classPath(System.getProperty("java.class.path", ""), DROPPED, LICH_JARS),
                ClassLoader.getPlatformClassLoader());
    }

    /**
     * Sets the compat pass: (binary class name, bytes) → bytes. Classes loaded before this call, Lich's
     * boot among them, aren't transformed.
     */
    public static void setTransformer(BiFunction<String, byte[], byte[]> compat) {
        transformer = compat;
    }

    /** The class path entries left out because Lich replaces them, for boot to log. */
    public static List<String> droppedEntries() {
        return List.copyOf(DROPPED);
    }

    /** Lich's own jars and ASM 9, which boot hides from mod discovery. */
    public static List<URL> lichJars() {
        return List.copyOf(LICH_JARS);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // There must be one LichSystemClassLoader class, so boot's instanceof checks work.
        if (name.equals(LichSystemClassLoader.class.getName())) {
            return LichSystemClassLoader.class;
        }
        return super.loadClass(name, resolve);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/').concat(".class");
        URL url = findResource(path);
        if (url == null) {
            throw new ClassNotFoundException(name);
        }
        try {
            URLConnection connection = url.openConnection();
            byte[] bytes;
            try (InputStream in = connection.getInputStream()) {
                bytes = in.readAllBytes();
            }

            URL location = url;
            Manifest manifest = null;
            CodeSigner[] signers = null;
            if (connection instanceof JarURLConnection jar) {
                location = jar.getJarFileURL();
                manifest = jar.getManifest();
                // Known only now that the entry has been read to the end.
                JarEntry entry = jar.getJarEntry();
                signers = entry == null ? null : entry.getCodeSigners();
            }
            definePackageFor(name, manifest, location);

            BiFunction<String, byte[], byte[]> compat = transformer;
            if (compat != null && !skipsCompat(name)) {
                bytes = compat.apply(name, bytes);
            }
            return defineClass(name, bytes, 0, bytes.length, new CodeSource(location, signers));
        } catch (IOException | RuntimeException e) {
            throw new ClassNotFoundException(name, e);
        }
    }

    /**
     * Lich's own classes and ASM never need the compat pass, and transforming ASM with ASM would load
     * the class being defined from inside its own definition.
     */
    private static boolean skipsCompat(String name) {
        return name.startsWith("org.objectweb.asm.") || name.startsWith("dev.lich.");
    }

    private void definePackageFor(String className, Manifest manifest, URL location) {
        int dot = className.lastIndexOf('.');
        if (dot < 0) {
            return;
        }
        String pkg = className.substring(0, dot);
        if (getDefinedPackage(pkg) != null) {
            return;
        }
        try {
            if (manifest != null) {
                definePackage(pkg, manifest, location);
            } else {
                definePackage(pkg, null, null, null, null, null, null, null);
            }
        } catch (IllegalArgumentException definedMeanwhile) {
            // Another thread defined it between the check and here.
        }
    }

    /** The JDK calls this when a {@code -javaagent} jar needs to be on the system class path. */
    @SuppressWarnings("unused")
    private void appendToClassPathForInstrumentation(String path) {
        try {
            addURL(Path.of(path).toAbsolutePath().toUri().toURL());
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(path, e);
        }
    }

    /**
     * {@code classPath} as URLs, without the jars Lich replaces (added to {@code dropped}); Lich's own
     * jars are also added to {@code lich}.
     */
    static URL[] classPath(String classPath, List<String> dropped, List<URL> lich) {
        List<URL> urls = new ArrayList<>();
        for (String entry : classPath.split(java.io.File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            Path path = Path.of(entry).toAbsolutePath();
            Kind kind = classify(path);
            if (kind == Kind.REPLACED) {
                dropped.add(entry);
                continue;
            }
            try {
                URL url = path.toUri().toURL();
                urls.add(url);
                if (kind == Kind.LICH) {
                    lich.add(url);
                }
            } catch (MalformedURLException e) {
                throw new IllegalStateException("Bad class path entry: " + entry, e);
            }
        }
        return urls.toArray(new URL[0]);
    }

    enum Kind {
        /** The game's, a launcher's or Forge's: loaded as usual. */
        OTHER,
        /** Mojang's LaunchWrapper or an ASM older than 9: Lich ships its own, so it's left out. */
        REPLACED,
        /** One of Lich's jars (by its manifest title), or a library Lich ships: ASM 9, Nashorn. */
        LICH
    }

    static Kind classify(Path path) {
        if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".jar")) {
            return Kind.OTHER;
        }
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            if (jar.getEntry("net/minecraft/launchwrapper/Launch.class") != null
                    && jar.getEntry("dev/lich/launchwrapper/LaunchCore.class") == null) {
                return Kind.REPLACED;
            }
            // Java 11+ class files, which FML's mod discovery can't parse either.
            if (jar.getEntry("org/openjdk/nashorn/api/scripting/NashornScriptEngineFactory.class") != null) {
                return Kind.LICH;
            }
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return Kind.OTHER;
            }
            String title = manifest.getMainAttributes().getValue("Implementation-Title");
            if (title != null && title.startsWith("lich-")) {
                return Kind.LICH;
            }
            String name = manifest.getMainAttributes().getValue("Bundle-SymbolicName");
            String version = manifest.getMainAttributes().getValue("Bundle-Version");
            if (name != null && name.startsWith("org.objectweb.asm") && version != null) {
                return major(version) < 9 ? Kind.REPLACED : Kind.LICH;
            }
            return Kind.OTHER;
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read class path entry " + path, e);
        }
    }

    private static int major(String version) {
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Integer.parseInt(version.substring(0, end));
    }
}

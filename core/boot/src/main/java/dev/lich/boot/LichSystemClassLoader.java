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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>It also leaves out of the class path the jars Lich replaces: Mojang's LaunchWrapper, ASM older
 * than 9 (Forge's asm-all 5.0.3 can't read Java 9+ class files) and, when LWJGL 3 and Lich's LWJGL 2 API
 * on it are both there, the game's LWJGL 2. {@code -Dlich.lwjgl=2} keeps LWJGL 2 and leaves out the other
 * two instead. It tells Lich's own jars (and the libraries it ships) apart, so boot can keep them out of
 * FML's mod discovery, and they skip the compat pass, which only old code needs.
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
    private static final List<Path> MAPPINGS = new CopyOnWriteArrayList<>();
    private static final List<URL> DEFERRED = new CopyOnWriteArrayList<>();
    private static final Set<String> LICH_LOCATIONS = ConcurrentHashMap.newKeySet();
    private static volatile BiFunction<String, byte[], byte[]> transformer;

    /** Called by the JDK with the application class loader, which this loader replaces. */
    public LichSystemClassLoader(ClassLoader applicationClassLoader) {
        super("lich-system", classPath(System.getProperty("java.class.path", ""), System.getProperty("lich.lwjgl"),
                DROPPED, LICH_JARS, MAPPINGS, DEFERRED), ClassLoader.getPlatformClassLoader());
        LICH_JARS.forEach(url -> LICH_LOCATIONS.add(url.toExternalForm()));
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

    /**
     * Puts the jars held back from the class path (Lich's Mixin) on it, for a launch with Lich mods. Must be
     * called before {@code LaunchClassLoader} is created, which copies this loader's URLs.
     */
    public void activateDeferred() {
        for (URL url : DEFERRED) {
            addURL(url);
            LICH_JARS.add(url);
            LICH_LOCATIONS.add(url.toExternalForm());
        }
        DEFERRED.clear();
    }

    /** MCP's SRG mappings ({@code mcp-<mc>-srg.zip}) on the class path, for the vanilla profile's remapper. */
    public static List<Path> mappings() {
        return List.copyOf(MAPPINGS);
    }

    /** Lich's own jars and the libraries it ships (ASM 9, Nashorn, LWJGL 3), which boot hides from mod discovery. */
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
            if (compat != null && !skipsCompat(name) && !LICH_LOCATIONS.contains(location.toExternalForm())) {
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
     * {@code classPath} as URLs, without the jars Lich replaces (added to {@code dropped}) or holds back
     * (added to {@code deferred}); Lich's own jars are also added to {@code lich}, and MCP's mappings to
     * {@code mappings} as well. {@code lwjgl} is {@code -Dlich.lwjgl}: "2" keeps LWJGL 2.
     */
    static URL[] classPath(String classPath, String lwjgl, List<String> dropped, List<URL> lich, List<Path> mappings,
                           List<URL> deferred) {
        Map<String, Kind> kinds = new LinkedHashMap<>();
        for (String entry : classPath.split(java.io.File.pathSeparator)) {
            if (!entry.isEmpty()) {
                kinds.putIfAbsent(entry, classify(Path.of(entry).toAbsolutePath()));
            }
        }
        boolean lwjgl3 = !"2".equals(lwjgl) && kinds.containsValue(Kind.LWJGL3) && kinds.containsValue(Kind.LWJGL2_API);
        List<URL> urls = new ArrayList<>();
        for (Map.Entry<String, Kind> classified : kinds.entrySet()) {
            String entry = classified.getKey();
            Path path = Path.of(entry).toAbsolutePath();
            Kind kind = switch (classified.getValue()) {
                case LWJGL2 -> lwjgl3 ? Kind.REPLACED : Kind.OTHER;
                case LWJGL3, LWJGL2_API -> lwjgl3 ? Kind.LICH : Kind.REPLACED;
                default -> classified.getValue();
            };
            if (kind == Kind.REPLACED) {
                dropped.add(entry);
                continue;
            }
            try {
                URL url = path.toUri().toURL();
                if (kind == Kind.DEFERRED) {
                    deferred.add(url);
                    continue;
                }
                urls.add(url);
                if (kind == Kind.LICH || kind == Kind.MAPPINGS) {
                    lich.add(url);
                }
                if (kind == Kind.MAPPINGS) {
                    mappings.add(path);
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
        LICH,
        /** The game's LWJGL 2: replaced when the game runs on LWJGL 3. */
        LWJGL2,
        /** A jar of LWJGL 3, classes or natives. With {@link #LWJGL2_API}, it replaces LWJGL 2; without, it's left out. */
        LWJGL3,
        /** Lich's LWJGL 2 API on LWJGL 3 (compat-lwjgl2). */
        LWJGL2_API,
        /** MCP's {@code mcp-<mc>-srg.zip}: kept, but hidden like Lich's jars; the vanilla profile remaps with it. */
        MAPPINGS,
        /**
         * Lich's Mixin and MixinExtras: held back until a launch has Lich mods ({@link #activateDeferred}), so
         * that without them Forge mods get the Mixin they ship, as on Forge.
         */
        DEFERRED
    }

    static Kind classify(Path path) {
        String fileName = path.getFileName().toString();
        if (Files.isRegularFile(path) && fileName.startsWith("mcp-") && fileName.endsWith("-srg.zip")) {
            return Kind.MAPPINGS;
        }
        if (!Files.isRegularFile(path) || !fileName.endsWith(".jar")) {
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
            if (jar.getEntry("org/lwjgl/Sys.class") != null && jar.getEntry("org/lwjgl/system/Library.class") == null
                    && jar.getEntry("dev/lich/compat/lwjgl2/Lwjgl2Compat.class") == null) {
                return Kind.LWJGL2;
            }
            if (manifest == null) {
                return Kind.OTHER;
            }
            if (isLichMixin(jar, manifest)) {
                return Kind.DEFERRED;
            }
            String title = manifest.getMainAttributes().getValue("Implementation-Title");
            if ("lich-compat-lwjgl2".equals(title)) {
                return Kind.LWJGL2_API;
            }
            if (title != null && title.startsWith("lich-")) {
                return Kind.LICH;
            }
            String specification = manifest.getMainAttributes().getValue("Specification-Version");
            if ("lwjgl.org".equals(manifest.getMainAttributes().getValue("Specification-Vendor"))
                    && specification != null && specification.startsWith("3.")) {
                return Kind.LWJGL3;
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

    /** Fabric's Mixin fork (which Lich ships; mods shade SpongePowered's) or MixinExtras' own jar. */
    private static boolean isLichMixin(JarFile jar, Manifest manifest) {
        if (jar.getEntry("com/llamalad7/mixinextras/MixinExtrasBootstrap.class") != null
                && jar.getEntry("LICENSE_MixinExtras") != null) {
            return true;
        }
        return jar.getEntry("org/spongepowered/asm/launch/MixinTweaker.class") != null
                && "https://fabricmc.net".equals(manifest.getMainAttributes().getValue("Implementation-Vendor"));
    }

    private static int major(String version) {
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Integer.parseInt(version.substring(0, end));
    }
}

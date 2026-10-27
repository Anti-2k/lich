package net.minecraft.launchwrapper;

import dev.lich.launchwrapper.ImageIoProviders;
import dev.lich.launchwrapper.LichExclusions;
import dev.lich.launchwrapper.LichSources;
import dev.lich.launchwrapper.LichTransformers;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Files;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Lich's implementation of LaunchWrapper 1.12's class loader. It has the same public API and the same
 * private fields, by name and type, because Mixin, coremods and mods such as FoamFix read and replace
 * them reflectively. The lookup order (invalid → class loader exclusions → cache → transformer
 * exclusions → rename → define package → transform → define) is 1.12's.
 *
 * <p>What changed, all for Java 9+:
 * <ul>
 *   <li>The parent is the platform class loader. 1.12 passed {@code null}, the bootstrap loader, which
 *       on Java 9+ can't see platform modules (java.sql, jdk.naming.dns, …), and service lookups
 *       through this loader then miss providers such as JNDI's DNS context factory.</li>
 *   <li>Packages are found with {@code getDefinedPackage}, and a package another thread defined first
 *       is used instead of failing the class load.</li>
 *   <li>The shared collections are thread-safe, so registering a transformer or exclusion while
 *       another thread loads classes can't throw {@code ConcurrentModificationException}.</li>
 *   <li>Lich's own transformers ({@link LichTransformers}) run before and after the registered ones.
 *       They're kept out of {@link #getTransformers()}, so mods that reorder or remove transformers
 *       can't disturb them. Both also run on transformer-excluded classes.</li>
 *   <li>{@link #getSources()} leaves out the jars in {@link LichSources}, so FML's mod discovery
 *       doesn't scan Lich's own jars.</li>
 *   <li>Classes matching {@link LichExclusions} always come from the parent, even when a mod removes the
 *       same prefix from {@code classLoaderExceptions}.</li>
 *   <li>ImageIO's provider lists ({@code META-INF/services/javax.imageio.spi.*}) leave out classes that
 *       aren't there, which Java 8 skipped under FML's SecurityManager ({@link ImageIoProviders}).</li>
 * </ul>
 */
public class LaunchClassLoader extends URLClassLoader {
    public static final int BUFFER_SIZE = 1 << 12;
    private List<URL> sources;
    private ClassLoader parent = getClass().getClassLoader();

    private List<IClassTransformer> transformers = new CopyOnWriteArrayList<>();
    private Map<String, Class<?>> cachedClasses = new ConcurrentHashMap<>();
    private Set<String> invalidClasses = ConcurrentHashMap.newKeySet(1000);

    private Set<String> classLoaderExceptions = ConcurrentHashMap.newKeySet();
    private Set<String> transformerExceptions = ConcurrentHashMap.newKeySet();
    private Map<Package, Manifest> packageManifests = new ConcurrentHashMap<>();
    private Map<String, byte[]> resourceCache = new ConcurrentHashMap<>(1000);
    private Set<String> negativeResourceCache = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private IClassNameTransformer renameTransformer;

    private static final Manifest EMPTY = new Manifest();

    // Unused since reading uses InputStream.readAllBytes(); kept so the field set matches 1.12's.
    @SuppressWarnings("unused")
    private final ThreadLocal<byte[]> loadBuffer = new ThreadLocal<>();

    // Windows can't have files with these names, so obfuscators store such classes as "_<name>".
    private static final String[] RESERVED_NAMES = {"CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4",
            "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"};

    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("legacy.debugClassLoading", "false"));
    private static final boolean DEBUG_FINER = DEBUG && Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingFiner", "false"));
    private static final boolean DEBUG_SAVE = DEBUG && Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingSave", "false"));
    private static File tempFolder = null;

    // 1.12 calls these overridable methods from the constructor too; subclasses see the same calls.
    @SuppressWarnings("this-escape")
    public LaunchClassLoader(URL[] sources) {
        super(sources, ClassLoader.getPlatformClassLoader());
        this.sources = new CopyOnWriteArrayList<>(Arrays.asList(sources));

        // Class loader exclusions: loaded by the parent (application) class loader, never by this one.
        addClassLoaderExclusion("java.");
        addClassLoaderExclusion("sun.");
        addClassLoaderExclusion("org.lwjgl.");
        addClassLoaderExclusion("org.apache.logging.");
        addClassLoaderExclusion("net.minecraft.launchwrapper.");

        // Transformer exclusions: loaded by this class loader, but never transformed.
        addTransformerExclusion("javax.");
        addTransformerExclusion("argo.");
        addTransformerExclusion("org.objectweb.asm.");
        addTransformerExclusion("com.google.common.");
        addTransformerExclusion("org.bouncycastle.");
        addTransformerExclusion("net.minecraft.launchwrapper.injector.");

        if (DEBUG_SAVE) {
            int x = 1;
            tempFolder = new File(Launch.minecraftHome, "CLASSLOADER_TEMP");
            while (tempFolder.exists() && x <= 10) {
                tempFolder = new File(Launch.minecraftHome, "CLASSLOADER_TEMP" + x++);
            }
            if (tempFolder.exists()) {
                LogWrapper.info("DEBUG_SAVE enabled, but 10 temp directories already exist, clean them and try again.");
                tempFolder = null;
            } else {
                LogWrapper.info("DEBUG_SAVE Enabled, saving all classes to \"%s\"", tempFolder.getAbsolutePath().replace('\\', '/'));
                tempFolder.mkdirs();
            }
        }
    }

    public void registerTransformer(String transformerClassName) {
        try {
            IClassTransformer transformer = (IClassTransformer) loadClass(transformerClassName).getDeclaredConstructor().newInstance();
            transformers.add(transformer);
            if (transformer instanceof IClassNameTransformer nameTransformer && renameTransformer == null) {
                renameTransformer = nameTransformer;
            }
        } catch (Exception e) {
            LogWrapper.log(Level.ERROR, e, "A critical problem occurred registering the ASM transformer class %s", transformerClassName);
        }
    }

    @Override
    public Class<?> findClass(final String name) throws ClassNotFoundException {
        if (invalidClasses.contains(name)) {
            throw new ClassNotFoundException(name);
        }

        for (final String exception : classLoaderExceptions) {
            if (name.startsWith(exception)) {
                return parent.loadClass(name);
            }
        }
        if (LichExclusions.matches(name)) {
            return parent.loadClass(name);
        }

        final Class<?> cached = cachedClasses.get(name);
        if (cached != null) {
            return cached;
        }

        for (final String exception : transformerExceptions) {
            if (name.startsWith(exception)) {
                try {
                    final Class<?> clazz = findExcludedClass(name);
                    cachedClasses.put(name, clazz);
                    return clazz;
                } catch (ClassNotFoundException e) {
                    invalidClasses.add(name);
                    throw e;
                }
            }
        }

        try {
            final String transformedName = transformName(name);
            final Class<?> cachedTransformed = cachedClasses.get(transformedName);
            if (cachedTransformed != null) {
                return cachedTransformed;
            }

            final String untransformedName = untransformName(name);

            final int lastDot = untransformedName.lastIndexOf('.');
            final String packageName = lastDot == -1 ? "" : untransformedName.substring(0, lastDot);
            final String fileName = untransformedName.replace('.', '/').concat(".class");
            final URLConnection urlConnection = findCodeSourceConnectionFor(fileName);

            CodeSigner[] signers = null;

            if (lastDot > -1 && !untransformedName.startsWith("net.minecraft.")) {
                if (urlConnection instanceof JarURLConnection jarURLConnection) {
                    final JarFile jarFile = jarURLConnection.getJarFile();

                    if (jarFile != null && jarFile.getManifest() != null) {
                        final Manifest manifest = jarFile.getManifest();
                        final JarEntry entry = jarFile.getJarEntry(fileName);

                        Package pkg = getDefinedPackage(packageName);
                        // The entry has to be read fully before its code signers are known.
                        getClassBytes(untransformedName);
                        signers = entry.getCodeSigners();
                        if (pkg == null) {
                            pkg = definePackageOnce(packageName,
                                    () -> definePackage(packageName, manifest, jarURLConnection.getJarFileURL()));
                            packageManifests.put(pkg, manifest);
                        } else {
                            if (pkg.isSealed() && !pkg.isSealed(jarURLConnection.getJarFileURL())) {
                                LogWrapper.severe("The jar file %s is trying to seal already secured path %s", jarFile.getName(), packageName);
                            } else if (isSealed(packageName, manifest)) {
                                LogWrapper.severe("The jar file %s has a security seal for path %s, but that path is defined and not secure", jarFile.getName(), packageName);
                            }
                        }
                    }
                } else {
                    Package pkg = getDefinedPackage(packageName);
                    if (pkg == null) {
                        pkg = definePackageOnce(packageName,
                                () -> definePackage(packageName, null, null, null, null, null, null, null));
                        packageManifests.put(pkg, EMPTY);
                    } else if (pkg.isSealed()) {
                        LogWrapper.severe("The URL %s is defining elements for sealed path %s", urlConnection.getURL(), packageName);
                    }
                }
            }

            final byte[] transformedClass = runTransformers(untransformedName, transformedName, getClassBytes(untransformedName));
            if (DEBUG_SAVE) {
                saveTransformedClass(transformedClass, transformedName);
            }

            final CodeSource codeSource = urlConnection == null ? null : new CodeSource(urlConnection.getURL(), signers);
            final Class<?> clazz = defineClass(transformedName, transformedClass, 0, transformedClass.length, codeSource);
            cachedClasses.put(transformedName, clazz);
            return clazz;
        } catch (Throwable e) {
            invalidClasses.add(name);
            if (DEBUG) {
                LogWrapper.log(Level.TRACE, e, "Exception encountered attempting classloading of %s", name);
                LogManager.getLogger("LaunchWrapper").log(Level.ERROR, "Exception encountered attempting classloading of %s", e);
            }
            throw new ClassNotFoundException(name, e);
        }
    }

    /**
     * A class that's excluded from transformers. 1.12 defines it untouched with {@code URLClassLoader}'s
     * {@code findClass}, and so does Lich unless its own first or last transformers change it: FML keeps
     * its own patcher and deobfuscation classes out of transformers, and those call removed JDK APIs and
     * LWJGL 2 too. A changed class gets the code source {@code URLClassLoader} would have given it.
     */
    private Class<?> findExcludedClass(final String name) throws ClassNotFoundException {
        if (LichTransformers.first().isEmpty() && LichTransformers.last().isEmpty()) {
            return super.findClass(name);
        }
        final byte[] bytes;
        try {
            bytes = getClassBytes(name);
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
        final byte[] compat = bytes == null ? null
                : runAll(LichTransformers.last(), name, name, runAll(LichTransformers.first(), name, name, bytes));
        if (compat == bytes) {
            return super.findClass(name);
        }

        final String fileName = name.replace('.', '/').concat(".class");
        final URLConnection connection = findCodeSourceConnectionFor(fileName);
        URL location = connection == null ? null : connection.getURL();
        CodeSigner[] signers = null;
        Manifest manifest = null;
        if (connection instanceof JarURLConnection jarConnection) {
            try {
                location = jarConnection.getJarFileURL();
                manifest = jarConnection.getManifest();
                final JarEntry entry = jarConnection.getJarEntry();
                signers = entry == null ? null : entry.getCodeSigners();
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
        final int lastDot = name.lastIndexOf('.');
        if (lastDot > -1) {
            final String packageName = name.substring(0, lastDot);
            if (getDefinedPackage(packageName) == null) {
                final Manifest packageManifest = manifest;
                final URL packageLocation = location;
                definePackageOnce(packageName, () -> packageManifest != null
                        ? definePackage(packageName, packageManifest, packageLocation)
                        : definePackage(packageName, null, null, null, null, null, null, null));
            }
        }
        return defineClass(name, compat, 0, compat.length, new CodeSource(location, signers));
    }

    /** Defines a package, or returns the one another thread defined between our lookup and now. */
    private Package definePackageOnce(String packageName, Supplier<Package> define) {
        try {
            return define.get();
        } catch (IllegalArgumentException alreadyDefined) {
            Package existing = getDefinedPackage(packageName);
            if (existing == null) {
                throw alreadyDefined;
            }
            return existing;
        }
    }

    private void saveTransformedClass(final byte[] data, final String transformedName) {
        if (tempFolder == null) {
            return;
        }

        final File outFile = new File(tempFolder, transformedName.replace('.', File.separatorChar) + ".class");
        final File outDir = outFile.getParentFile();

        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        if (outFile.exists()) {
            outFile.delete();
        }

        try {
            LogWrapper.fine("Saving transformed class \"%s\" to \"%s\"", transformedName, outFile.getAbsolutePath().replace('\\', '/'));
            Files.write(outFile.toPath(), data);
        } catch (IOException ex) {
            LogWrapper.log(Level.WARN, ex, "Could not save transformed class \"%s\"", transformedName);
        }
    }

    private String untransformName(final String name) {
        if (renameTransformer != null) {
            return renameTransformer.unmapClassName(name);
        }
        return name;
    }

    private String transformName(final String name) {
        if (renameTransformer != null) {
            return renameTransformer.remapClassName(name);
        }
        return name;
    }

    private boolean isSealed(final String path, final Manifest manifest) {
        Attributes attributes = manifest.getAttributes(path);
        String sealed = null;
        if (attributes != null) {
            sealed = attributes.getValue(Attributes.Name.SEALED);
        }

        if (sealed == null) {
            attributes = manifest.getMainAttributes();
            if (attributes != null) {
                sealed = attributes.getValue(Attributes.Name.SEALED);
            }
        }
        return "true".equalsIgnoreCase(sealed);
    }

    private URLConnection findCodeSourceConnectionFor(final String name) {
        final URL resource = findResource(name);
        if (resource != null) {
            try {
                return resource.openConnection();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return null;
    }

    private byte[] runTransformers(final String name, final String transformedName, byte[] basicClass) {
        if (DEBUG_FINER) {
            LogWrapper.finest("Beginning transform of {%s (%s)} Start Length: %d", name, transformedName, basicClass == null ? 0 : basicClass.length);
        }
        basicClass = runAll(LichTransformers.first(), name, transformedName, basicClass);
        basicClass = runAll(transformers, name, transformedName, basicClass);
        basicClass = runAll(LichTransformers.last(), name, transformedName, basicClass);
        if (DEBUG_FINER) {
            LogWrapper.finest("Ending transform of {%s (%s)} Start Length: %d", name, transformedName, basicClass == null ? 0 : basicClass.length);
        }
        return basicClass;
    }

    private static byte[] runAll(List<IClassTransformer> chain, String name, String transformedName, byte[] basicClass) {
        for (final IClassTransformer transformer : chain) {
            if (DEBUG_FINER) {
                final String transName = transformer.getClass().getName();
                LogWrapper.finest("Before Transformer {%s (%s)} %s: %d", name, transformedName, transName, basicClass == null ? 0 : basicClass.length);
                basicClass = transformer.transform(name, transformedName, basicClass);
                LogWrapper.finest("After  Transformer {%s (%s)} %s: %d", name, transformedName, transName, basicClass == null ? 0 : basicClass.length);
            } else {
                basicClass = transformer.transform(name, transformedName, basicClass);
            }
        }
        return basicClass;
    }

    @Override
    public void addURL(final URL url) {
        super.addURL(url);
        sources.add(url);
    }

    /** ImageIO's provider lists without classes that aren't there, as Java 8 under FML treated them (ImageIoProviders). */
    @Override
    public Enumeration<URL> getResources(final String name) throws IOException {
        final Enumeration<URL> found = super.getResources(name);
        return ImageIoProviders.applies(name) ? ImageIoProviders.filter(found, this) : found;
    }

    public List<URL> getSources() {
        if (!LichSources.anyHidden()) {
            return sources;
        }
        // A copy: callers that add to the list expected to add to the real one, but none of the
        // mods that read it do (they call addURL).
        final List<URL> visible = new ArrayList<>(sources.size());
        for (final URL source : sources) {
            if (!LichSources.isHidden(source)) {
                visible.add(source);
            }
        }
        return visible;
    }

    public List<IClassTransformer> getTransformers() {
        return Collections.unmodifiableList(transformers);
    }

    public void addClassLoaderExclusion(String toExclude) {
        classLoaderExceptions.add(toExclude);
    }

    public void addTransformerExclusion(String toExclude) {
        transformerExceptions.add(toExclude);
    }

    public byte[] getClassBytes(String name) throws IOException {
        if (negativeResourceCache.contains(name)) {
            return null;
        }
        final byte[] cached = resourceCache.get(name);
        if (cached != null) {
            return cached;
        }
        if (name.indexOf('.') == -1) {
            for (final String reservedName : RESERVED_NAMES) {
                if (name.toUpperCase(Locale.ENGLISH).startsWith(reservedName)) {
                    final byte[] data = getClassBytes("_" + name);
                    if (data != null) {
                        resourceCache.put(name, data);
                        return data;
                    }
                }
            }
        }

        final String resourcePath = name.replace('.', '/').concat(".class");
        final URL classResource = findResource(resourcePath);

        if (classResource == null) {
            if (DEBUG) {
                LogWrapper.finest("Failed to find class resource %s", resourcePath);
            }
            negativeResourceCache.add(name);
            return null;
        }
        try (InputStream classStream = classResource.openStream()) {
            if (DEBUG) {
                LogWrapper.finest("Loading class %s from resource %s", name, classResource.toString());
            }
            final byte[] data = classStream.readAllBytes();
            resourceCache.put(name, data);
            return data;
        }
    }

    public void clearNegativeEntries(Set<String> entriesToClear) {
        negativeResourceCache.removeAll(entriesToClear);
    }
}

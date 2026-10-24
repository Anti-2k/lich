package dev.lich.launchwrapper;

import dev.lich.launchwrapper.testsupport.RecordingTransformer;
import net.minecraft.launchwrapper.IClassNameTransformer;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.junit.jupiter.api.Test;

import javax.naming.spi.InitialContextFactory;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchClassLoaderTest {
    private static final String SUPPORT = "dev.lich.launchwrapper.testsupport.";

    /** A loader over the fixture classes only, with the test's transformers loadable by name. */
    private static LaunchClassLoader newLoader() {
        URL[] urls = Arrays.stream(System.getProperty("lich.test.fixtures").split(File.pathSeparator))
                .map(LaunchClassLoaderTest::toUrl)
                .toArray(URL[]::new);
        LaunchClassLoader loader = new LaunchClassLoader(urls);
        loader.addClassLoaderExclusion(SUPPORT);
        return loader;
    }

    private static URL toUrl(String path) {
        try {
            return Path.of(path).toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Object callStatic(Class<?> type, String method) throws ReflectiveOperationException {
        return type.getMethod(method).invoke(null);
    }

    @Test
    void definesClassesFromItsSourcesItself() throws Exception {
        LaunchClassLoader loader = newLoader();
        Class<?> greeting = loader.loadClass("fixture.Greeting");
        assertSame(loader, greeting.getClassLoader());
        assertEquals("ORIGINAL", callStatic(greeting, "text"));
        assertSame(greeting, loader.loadClass("fixture.Greeting"), "second load comes from the cache");
    }

    @Test
    void classLoaderExclusionsComeFromTheApplicationLoader() throws Exception {
        assertSame(ITweaker.class, newLoader().loadClass("net.minecraft.launchwrapper.ITweaker"));
    }

    @Test
    void platformModulesAreVisible() throws Exception {
        LaunchClassLoader loader = newLoader();
        assertEquals(0L, callStatic(loader.loadClass("fixture.UsesSql"), "epoch"));
        loader.loadClass("com.sun.jndi.dns.DnsContextFactory");

        // Vanilla's SRV lookup asks JNDI for the DNS context factory with this loader as the context
        // class loader, and JNDI finds it through ServiceLoader.
        boolean found = ServiceLoader.load(InitialContextFactory.class, loader).stream()
                .anyMatch(provider -> provider.type().getName().equals("com.sun.jndi.dns.DnsContextFactory"));
        assertTrue(found, "JNDI's DNS context factory is visible through the loader");
    }

    @Test
    void aBootstrapOnlyParentCantSeePlatformModules() throws Exception {
        // LaunchWrapper 1.12's choice of parent, and the reason Lich's differs.
        try (URLClassLoader bootstrapOnly = new URLClassLoader(new URL[0], null)) {
            assertThrows(ClassNotFoundException.class, () -> bootstrapOnly.loadClass("java.sql.Timestamp"));
        }
    }

    @Test
    void registeredTransformersRewriteClassBytes() throws Exception {
        LaunchClassLoader loader = newLoader();
        loader.registerTransformer(SUPPORT + "ReplaceStringTransformer");
        assertEquals(1, loader.getTransformers().size());
        assertEquals("REPLACED", callStatic(loader.loadClass("fixture.Greeting"), "text"));
    }

    @Test
    void transformersCanCreateClassesFromNothing() throws Exception {
        LaunchClassLoader loader = newLoader();
        loader.registerTransformer(SUPPORT + "GeneratingTransformer");
        assertEquals("generated", callStatic(loader.loadClass("fixture.Generated"), "hello"));
    }

    @Test
    void lichTransformersRunAroundTheRegisteredOnes() throws Exception {
        LichTransformers.addFirst(new RecordingTransformer("first"));
        LichTransformers.addLast(new RecordingTransformer("last"));
        LaunchClassLoader loader = newLoader();
        loader.registerTransformer(SUPPORT + "RecordingTransformer");
        loader.loadClass("fixture.Ordered");
        assertEquals(List.of("first", "registered", "last"), RecordingTransformer.SEEN);
        assertEquals(1, loader.getTransformers().size(), "Lich's transformers are not in the registered list");
    }

    @Test
    void lichTransformersAlsoRunOnTransformerExcludedClasses() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        LichTransformers.addFirst((name, transformedName, bytes) -> record(seen, "first", name, bytes));
        LichTransformers.addLast((name, transformedName, bytes) -> record(seen, "last", name, bytes));
        LaunchClassLoader loader = newLoader();
        loader.addTransformerExclusion("fixture.UsesSql");
        loader.registerTransformer(SUPPORT + "ReplaceStringTransformer");
        loader.loadClass("fixture.UsesSql");
        assertEquals(List.of("first", "last"), seen);
    }

    @Test
    @SuppressWarnings("unchecked")
    void lichExclusionsSurviveModsEditingTheExclusionSet() throws Exception {
        LaunchClassLoader loader = newLoader();
        loader.addClassLoaderExclusion("fixture.");
        LichExclusions.add("fixture.Excluded");
        Field field = LaunchClassLoader.class.getDeclaredField("classLoaderExceptions");
        field.setAccessible(true);
        ((Set<String>) field.get(loader)).remove("fixture.");
        assertThrows(ClassNotFoundException.class, () -> loader.findClass("fixture.Excluded"),
                "left to the parent, which doesn't have the fixtures");
    }

    private static byte[] record(List<String> seen, String label, String name, byte[] bytes) {
        if (name.equals("fixture.UsesSql")) {
            seen.add(label);
        }
        return bytes;
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedLoadsAreRemembered() throws Exception {
        LaunchClassLoader loader = newLoader();
        assertThrows(ClassNotFoundException.class, () -> loader.loadClass("fixture.Missing"));
        Field invalid = LaunchClassLoader.class.getDeclaredField("invalidClasses");
        invalid.setAccessible(true);
        assertTrue(((Set<String>) invalid.get(loader)).contains("fixture.Missing"));
    }

    /** Mods and Mixin read these by name, so they must exist with exactly these types (from javap of 1.12). */
    @Test
    void fieldsMatchLaunchWrapper112() throws Exception {
        Map<String, Class<?>> fields = new LinkedHashMap<>();
        fields.put("BUFFER_SIZE", int.class);
        fields.put("sources", List.class);
        fields.put("parent", ClassLoader.class);
        fields.put("transformers", List.class);
        fields.put("cachedClasses", Map.class);
        fields.put("invalidClasses", Set.class);
        fields.put("classLoaderExceptions", Set.class);
        fields.put("transformerExceptions", Set.class);
        fields.put("packageManifests", Map.class);
        fields.put("resourceCache", Map.class);
        fields.put("negativeResourceCache", Set.class);
        fields.put("renameTransformer", IClassNameTransformer.class);
        fields.put("EMPTY", Manifest.class);
        fields.put("loadBuffer", ThreadLocal.class);
        fields.put("RESERVED_NAMES", String[].class);
        fields.put("DEBUG", boolean.class);
        fields.put("DEBUG_FINER", boolean.class);
        fields.put("DEBUG_SAVE", boolean.class);
        fields.put("tempFolder", File.class);
        for (Map.Entry<String, Class<?>> expected : fields.entrySet()) {
            Field field = LaunchClassLoader.class.getDeclaredField(expected.getKey());
            assertSame(expected.getValue(), field.getType(), expected.getKey());
        }
        assertTrue(Modifier.isPublic(LaunchClassLoader.class.getDeclaredField("BUFFER_SIZE").getModifiers()));
    }

    @Test
    void publicMethodsMatchLaunchWrapper112() throws Exception {
        Map<String, Class<?>[]> methods = new LinkedHashMap<>();
        methods.put("registerTransformer", new Class<?>[]{String.class});
        methods.put("findClass", new Class<?>[]{String.class});
        methods.put("addURL", new Class<?>[]{URL.class});
        methods.put("getSources", new Class<?>[0]);
        methods.put("getTransformers", new Class<?>[0]);
        methods.put("addClassLoaderExclusion", new Class<?>[]{String.class});
        methods.put("addTransformerExclusion", new Class<?>[]{String.class});
        methods.put("getClassBytes", new Class<?>[]{String.class});
        methods.put("clearNegativeEntries", new Class<?>[]{Set.class});
        for (Map.Entry<String, Class<?>[]> expected : methods.entrySet()) {
            Method method = LaunchClassLoader.class.getDeclaredMethod(expected.getKey(), expected.getValue());
            assertTrue(Modifier.isPublic(method.getModifiers()), expected.getKey());
        }
    }
}

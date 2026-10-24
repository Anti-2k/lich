package dev.lich.compat.jdk;

import dev.lich.transform.CompatTransformer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonsLangJavaVersionTest {
    @Test
    void modernVersionStrings() {
        assertTrue(JavaVersions.isModern("9"));
        assertTrue(JavaVersions.isModern("25"));
        assertTrue(JavaVersions.isModern("25.0.4"));
        assertFalse(JavaVersions.isModern("1.8"));
        assertFalse(JavaVersions.isModern("1.9"));
        assertFalse(JavaVersions.isModern("8"));
        assertFalse(JavaVersions.isModern("abc"));
        assertFalse(JavaVersions.isModern(""));
        assertFalse(JavaVersions.isModern(null));
    }

    /** The game's commons-lang3 3.3.2 (from the build), its JavaVersion patched by the compat pass. */
    @Test
    void patchedJavaVersionKnowsJava25() throws Exception {
        URL jar = Path.of(System.getProperty("lich.test.commonsLang3")).toUri().toURL();
        try (PatchingLoader loader = new PatchingLoader(jar, JdkCompat.register(CompatTransformer.builder()).build())) {
            Class<?> javaVersion = loader.loadClass(CommonsLangJavaVersion.CLASS);
            assertTrue(loader.patched);
            Method get = javaVersion.getDeclaredMethod("get", String.class);
            get.setAccessible(true);
            Object java8 = javaVersion.getField("JAVA_1_8").get(null);
            assertEquals(java8, get.invoke(null, "25"));
            assertEquals(java8, get.invoke(null, "1.8"));
            assertEquals(javaVersion.getField("JAVA_1_6").get(null), get.invoke(null, "1.6"));
            assertNull(get.invoke(null, "1.9"));
            assertNull(get.invoke(null, "bogus"));

            // What made FML 1.8.9's BlamingTransformer throw on every Java 8 class.
            Class<?> systemUtils = loader.loadClass("org.apache.commons.lang3.SystemUtils");
            var asEnum = systemUtils.getDeclaredField("JAVA_SPECIFICATION_VERSION_AS_ENUM");
            asEnum.setAccessible(true);
            assertEquals(java8, asEnum.get(null));
            assertTrue((Boolean) systemUtils.getMethod("isJavaVersionAtLeast", javaVersion).invoke(null, java8));
        }
    }

    /** Child-first loader for the jar that runs the compat pass on JavaVersion. */
    private static final class PatchingLoader extends URLClassLoader {
        private final CompatTransformer compat;
        boolean patched;

        PatchingLoader(URL jar, CompatTransformer compat) {
            super(new URL[]{jar}, CommonsLangJavaVersionTest.class.getClassLoader());
            this.compat = compat;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("org.apache.commons.lang3.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                // findResource, not getResource: the test class path has a newer commons-lang3.
                URL resource = findResource(name.replace('.', '/') + ".class");
                if (resource == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes;
                try (InputStream in = resource.openStream()) {
                    bytes = in.readAllBytes();
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
                byte[] transformed = compat.transform(name, bytes);
                if (name.equals(CommonsLangJavaVersion.CLASS)) {
                    assertNotSame(bytes, transformed);
                    patched = true;
                }
                return defineClass(name, transformed, 0, transformed.length);
            }
        }
    }
}

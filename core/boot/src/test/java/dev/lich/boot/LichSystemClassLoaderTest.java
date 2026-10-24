package dev.lich.boot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LichSystemClassLoaderTest {
    @AfterEach
    void clearTransformer() {
        LichSystemClassLoader.setTransformer(null);
    }

    @Test
    void definesClassPathClassesItself() throws Exception {
        try (LichSystemClassLoader loader = new LichSystemClassLoader(getClass().getClassLoader())) {
            Class<?> profile = loader.loadClass(Profile.class.getName());
            assertSame(loader, profile.getClassLoader());
            assertSame(String.class, loader.loadClass("java.lang.String"));
            assertSame(LichSystemClassLoader.class, loader.loadClass(LichSystemClassLoader.class.getName()),
                    "the loader class itself stays the application class loader's");
        }
    }

    @Test
    void runsTheCompatPassOnEveryClassButLichsOwnAndAsms() throws Exception {
        List<String> seen = new ArrayList<>();
        LichSystemClassLoader.setTransformer((name, bytes) -> {
            seen.add(name);
            return bytes;
        });
        try (LichSystemClassLoader loader = new LichSystemClassLoader(getClass().getClassLoader())) {
            loader.loadClass("org.junit.jupiter.api.Test");
            loader.loadClass(Profile.class.getName());
        }
        assertEquals(List.of("org.junit.jupiter.api.Test"), seen);
    }

    @Test
    void leavesOutTheJarsLichReplaces(@TempDir Path dir) throws IOException {
        Path launchWrapper = jar(dir.resolve("launchwrapper-1.12.jar"), null, "net/minecraft/launchwrapper/Launch.class");
        Path lichLaunchWrapper = jar(dir.resolve("lich-launchwrapper.jar"), null,
                "net/minecraft/launchwrapper/Launch.class", "dev/lich/launchwrapper/LaunchCore.class");
        Path asm5 = jar(dir.resolve("asm-all-5.0.3.jar"), asmManifest("org.objectweb.asm.all", "5.0.3"), "org/objectweb/asm/ClassReader.class");
        Path asm9 = jar(dir.resolve("asm-9.8.jar"), asmManifest("org.objectweb.asm", "9.8"), "org/objectweb/asm/ClassReader.class");
        Path shaded = jar(dir.resolve("some-mod-lib.jar"), null, "org/objectweb/asm/ClassReader.class");
        Path lichBoot = jar(dir.resolve("lich-boot.jar"), titled("lich-boot"), "dev/lich/boot/Main.class");
        Path forge = jar(dir.resolve("forge.jar"), titled("Forge"), "net/minecraftforge/Foo.class");
        Path classesDir = Files.createDirectories(dir.resolve("classes"));

        List<String> dropped = new ArrayList<>();
        List<URL> lich = new ArrayList<>();
        String classPath = String.join(File.pathSeparator, List.of(launchWrapper.toString(), lichLaunchWrapper.toString(),
                asm5.toString(), asm9.toString(), shaded.toString(), lichBoot.toString(), forge.toString(), classesDir.toString()));
        URL[] urls = LichSystemClassLoader.classPath(classPath, dropped, lich);

        assertEquals(List.of(launchWrapper.toString(), asm5.toString()), dropped);
        assertEquals(List.of(url(lichLaunchWrapper), url(asm9), url(shaded), url(lichBoot), url(forge), url(classesDir)), List.of(urls));
        assertEquals(List.of(url(asm9), url(lichBoot)), lich, "Lich's jars and ASM 9, which FML mustn't scan");
        assertEquals(LichSystemClassLoader.Kind.OTHER, LichSystemClassLoader.classify(classesDir));
    }

    private static URL url(Path path) throws IOException {
        return path.toUri().toURL();
    }

    private static Manifest titled(String title) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_TITLE, title);
        return manifest;
    }

    private static Manifest asmManifest(String name, String version) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Bundle-SymbolicName", name);
        manifest.getMainAttributes().putValue("Bundle-Version", version);
        return manifest;
    }

    private static Path jar(Path file, Manifest manifest, String... entries) throws IOException {
        try (JarOutputStream out = manifest == null
                ? new JarOutputStream(Files.newOutputStream(file))
                : new JarOutputStream(Files.newOutputStream(file), manifest)) {
            for (String entry : entries) {
                out.putNextEntry(new JarEntry(entry));
                out.closeEntry();
            }
        }
        return file;
    }
}

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
        URL[] urls = LichSystemClassLoader.classPath(classPath, null, dropped, lich, new ArrayList<>(), new ArrayList<>());

        assertEquals(List.of(launchWrapper.toString(), asm5.toString()), dropped);
        assertEquals(List.of(url(lichLaunchWrapper), url(asm9), url(shaded), url(lichBoot), url(forge), url(classesDir)), List.of(urls));
        assertEquals(List.of(url(asm9), url(lichBoot)), lich, "Lich's jars and ASM 9, which FML mustn't scan");
        assertEquals(LichSystemClassLoader.Kind.OTHER, LichSystemClassLoader.classify(classesDir));
    }

    @Test
    void choosesBetweenLwjgl2AndLwjgl3(@TempDir Path dir) throws IOException {
        Path lwjgl2 = jar(dir.resolve("lwjgl-2.9.4.jar"), null, "org/lwjgl/Sys.class", "org/lwjgl/opengl/Display.class");
        Path lwjglUtil = jar(dir.resolve("lwjgl_util-2.9.4.jar"), null, "org/lwjgl/util/glu/GLU.class");
        Path lwjgl3 = jar(dir.resolve("lwjgl-3.3.6.jar"), lwjgl3Manifest(), "org/lwjgl/system/Library.class");
        Path natives = jar(dir.resolve("lwjgl-3.3.6-natives-windows.jar"), lwjgl3Manifest(), "windows/x64/org/lwjgl/lwjgl.dll");
        Path api = jar(dir.resolve("lich-compat-lwjgl2.jar"), titled("lich-compat-lwjgl2"),
                "org/lwjgl/Sys.class", "dev/lich/compat/lwjgl2/Lwjgl2Compat.class");
        String all = String.join(File.pathSeparator, List.of(api.toString(), lwjgl3.toString(), natives.toString(),
                lwjgl2.toString(), lwjglUtil.toString()));

        List<String> dropped = new ArrayList<>();
        List<URL> lich = new ArrayList<>();
        URL[] urls = LichSystemClassLoader.classPath(all, null, dropped, lich, new ArrayList<>(), new ArrayList<>());
        assertEquals(List.of(lwjgl2.toString()), dropped, "LWJGL 3 replaces LWJGL 2; lwjgl_util stays");
        assertEquals(List.of(url(api), url(lwjgl3), url(natives), url(lwjglUtil)), List.of(urls));
        assertEquals(List.of(url(api), url(lwjgl3), url(natives)), lich, "hidden from FML like Lich's own jars");

        dropped.clear();
        lich.clear();
        urls = LichSystemClassLoader.classPath(all, "2", dropped, lich, new ArrayList<>(), new ArrayList<>());
        assertEquals(List.of(api.toString(), lwjgl3.toString(), natives.toString()), dropped, "-Dlich.lwjgl=2 keeps LWJGL 2");
        assertEquals(List.of(url(lwjgl2), url(lwjglUtil)), List.of(urls));

        dropped.clear();
        LichSystemClassLoader.classPath(String.join(File.pathSeparator, List.of(lwjgl3.toString(), lwjgl2.toString())),
                null, dropped, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        assertEquals(List.of(lwjgl3.toString()), dropped, "without Lich's LWJGL 2 API, LWJGL 3 can't replace LWJGL 2");
    }

    @Test
    void keepsMcpMappingsButHidesThem(@TempDir Path dir) throws IOException {
        Path srg = jar(dir.resolve("mcp-1.8.9-srg.zip"), null, "joined.srg");
        Path other = jar(dir.resolve("other.zip"), null, "joined.srg");
        List<URL> lich = new ArrayList<>();
        List<Path> mappings = new ArrayList<>();
        URL[] urls = LichSystemClassLoader.classPath(String.join(File.pathSeparator, List.of(srg.toString(), other.toString())),
                null, new ArrayList<>(), lich, mappings, new ArrayList<>());
        assertEquals(List.of(url(srg), url(other)), List.of(urls));
        assertEquals(List.of(url(srg)), lich, "hidden from FML's mod discovery");
        assertEquals(List.of(srg.toAbsolutePath()), mappings);
    }

    private static Manifest lwjgl3Manifest() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.SPECIFICATION_VENDOR, "lwjgl.org");
        manifest.getMainAttributes().put(Attributes.Name.SPECIFICATION_VERSION, "3.3.6");
        return manifest;
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

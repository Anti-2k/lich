package dev.lich.compat.jdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class Pack200Test {
    @Test
    void unpacksWhatPack200Packed(@TempDir Path dir) throws Exception {
        // Shaped like Forge's binpatches: a jar of non-class files under binpatch/<side>/.
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("binpatch/client/abc.binpatch", "patch one".getBytes(StandardCharsets.UTF_8));
        files.put("binpatch/client/net.minecraft.Foo.binpatch", new byte[]{0, 1, 2, 3, (byte) 0xff});
        files.put("binpatch/server/xyz.binpatch", new byte[0]);

        Path jar = dir.resolve("binpatches.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                out.putNextEntry(new JarEntry(file.getKey()));
                out.write(file.getValue());
                out.closeEntry();
            }
        }
        // Packed by the test's own commons-compress (on the test class path), unpacked by the shim,
        // which uses the copy nested in this module's resources.
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            org.apache.commons.compress.java.util.jar.Pack200.newPacker().pack(jarFile, packed);
        }

        // Read through a decompressing FilterInputStream, as FML reads through LzmaInputStream:
        // commons-compress on its own would unwrap it and read the compressed bytes.
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(deflated)) {
            out.write(packed.toByteArray());
        }
        ByteArrayOutputStream unpacked = new ByteArrayOutputStream();
        // Not closed before reading, like FML's ClassPatchManager does it.
        JarOutputStream out = new JarOutputStream(unpacked);
        Pack200.newUnpacker().unpack(new InflaterInputStream(new ByteArrayInputStream(deflated.toByteArray())), out);
        System.out.println("stdout still open after unpacking");
        assertFalse(System.out.checkError(), "commons-compress closes System.out unless given a log file");

        Map<String, byte[]> result = new LinkedHashMap<>();
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(unpacked.toByteArray()))) {
            for (JarEntry entry; (entry = in.getNextJarEntry()) != null; ) {
                result.put(entry.getName(), in.readAllBytes());
            }
        }
        assertEquals(files.keySet(), result.keySet());
        files.forEach((name, bytes) -> assertArrayEquals(bytes, result.get(name), name));
    }

    @Test
    void commonsCompressIsIsolatedFromTheClassPath() throws Exception {
        Class<?> nested = Class.forName("org.apache.commons.compress.java.util.jar.Pack200", false, IsolatedLibraries.pack200());
        assertNotSame(org.apache.commons.compress.java.util.jar.Pack200.class, nested);
        assertEquals(ClassLoader.getPlatformClassLoader(), nested.getClassLoader().getParent());
    }
}

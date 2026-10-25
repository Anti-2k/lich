package dev.lich.remap.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Remaps every class in a jar and copies everything else. Signatures are dropped (they can't match
 * remapped classes), and so are the per-entry digests in the manifest; its main attributes are kept.
 * Entries keep the timestamp of the input, so the same input always gives the same output bytes.
 */
public final class JarRemapper {
    private static final long FIXED_TIME = 315532800000L; // 1980-01-01, the earliest time a zip can hold

    private JarRemapper() {
    }

    /** {@code input} remapped with {@code remapper} into {@code output}, written through a temporary file. */
    public static void remap(Path input, Path output, MappingRemapper remapper) throws IOException {
        remap(input, output, remapper, entry -> true, (name, bytes) -> bytes);
    }

    /**
     * The same, keeping only the entries {@code keep} accepts (by their name in {@code input}), and passing
     * every other file through {@code resources} (name, bytes → bytes), for files that name classes or
     * members themselves, such as access wideners.
     */
    public static void remap(Path input, Path output, MappingRemapper remapper, Predicate<String> keep,
                             BiFunction<String, byte[], byte[]> resources) throws IOException {
        remap(input, output, remapper, keep, resources, UnaryOperator.identity());
    }

    /** The same, with {@code after} wrapped around the writer: it sees each class with its new names. */
    public static void remap(Path input, Path output, MappingRemapper remapper, Predicate<String> keep,
                             BiFunction<String, byte[], byte[]> resources, UnaryOperator<ClassVisitor> after) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        try (ZipFile zip = new ZipFile(input.toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(temporary))) {
            Map<String, byte[]> entries = new HashMap<>();
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (entry.isDirectory() || isSignature(entry.getName()) || !keep.test(entry.getName())) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                    entries.put(entry.getName(), mainAttributesOnly(bytes));
                } else if (entry.getName().endsWith(".class")) {
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(0);
                    reader.accept(remapper.classVisitor(after.apply(writer)), 0);
                    entries.put(remapper.map(reader.getClassName()) + ".class", writer.toByteArray());
                } else {
                    entries.put(entry.getName(), resources.apply(entry.getName(), bytes));
                }
            }
            write(out, entries);
        }
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Writes {@code entries} sorted by name, with the manifest first as {@code JarInputStream} expects. */
    static void write(JarOutputStream out, Map<String, byte[]> entries) throws IOException {
        byte[] manifest = entries.get("META-INF/MANIFEST.MF");
        if (manifest != null) {
            put(out, "META-INF/MANIFEST.MF", manifest);
        }
        for (String name : entries.keySet().stream().sorted().toList()) {
            if (!name.equals("META-INF/MANIFEST.MF")) {
                put(out, name, entries.get(name));
            }
        }
    }

    private static void put(JarOutputStream out, String name, byte[] bytes) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(FIXED_TIME);
        out.putNextEntry(entry);
        out.write(bytes);
        out.closeEntry();
    }

    private static boolean isSignature(String name) {
        if (!name.startsWith("META-INF/") || name.indexOf('/', "META-INF/".length()) >= 0) {
            return false;
        }
        return name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC");
    }

    private static byte[] mainAttributesOnly(byte[] manifestBytes) throws IOException {
        Manifest manifest = new Manifest(new ByteArrayInputStream(manifestBytes));
        Manifest stripped = new Manifest();
        stripped.getMainAttributes().putAll(manifest.getMainAttributes());
        if (stripped.getMainAttributes().getValue(Attributes.Name.MANIFEST_VERSION) == null) {
            stripped.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        stripped.write(out);
        return out.toByteArray();
    }
}

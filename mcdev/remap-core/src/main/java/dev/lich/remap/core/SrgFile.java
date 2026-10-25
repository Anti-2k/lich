package dev.lich.remap.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * MCP's SRG format: {@code CL: a net/minecraft/X}, {@code FD: a/b net/minecraft/X/field_1_a} and
 * {@code MD: a/b (I)La; net/minecraft/X/func_1_a (I)Lnet/minecraft/X;}. Package lines ({@code PK:}) are
 * skipped: every class is listed by its full name anyway.
 */
public final class SrgFile {
    private SrgFile() {
    }

    /** Reads the SRG file {@code entry} (MCP's {@code joined.srg}) from a zip such as {@code mcp-<mc>-srg.zip}. */
    public static Mappings readZip(Path zip, String entry) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            ZipEntry srg = file.getEntry(entry);
            if (srg == null) {
                throw new IOException(entry + " is missing from " + zip);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(srg), StandardCharsets.UTF_8))) {
                return read(reader);
            }
        }
    }

    public static Mappings read(BufferedReader reader) throws IOException {
        Mappings.Builder mappings = Mappings.builder();
        String line;
        int number = 0;
        while ((line = reader.readLine()) != null) {
            number++;
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("PK:")) {
                continue;
            }
            String[] parts = line.split(" ");
            try {
                switch (parts[0]) {
                    case "CL:" -> mappings.mapClass(parts[1], parts[2]);
                    case "FD:" -> {
                        int slash = parts[1].lastIndexOf('/');
                        mappings.mapField(parts[1].substring(0, slash), parts[1].substring(slash + 1), simpleName(parts[2]));
                    }
                    case "MD:" -> {
                        int slash = parts[1].lastIndexOf('/');
                        mappings.mapMethod(parts[1].substring(0, slash), parts[1].substring(slash + 1), parts[2], simpleName(parts[3]));
                    }
                    default -> throw new IOException("Unknown SRG line " + number + ": " + line);
                }
            } catch (IndexOutOfBoundsException e) {
                throw new IOException("Malformed SRG line " + number + ": " + line, e);
            }
        }
        return mappings.build();
    }

    private static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('/') + 1);
    }

    /** Writes {@code mappings} as SRG, sorted, so the same mappings always give the same file. */
    public static void write(Mappings mappings, Writer writer) throws IOException {
        for (String source : new TreeSet<>(mappings.classNames())) {
            writer.write("CL: " + source + " " + mappings.mapClass(source) + "\n");
        }
        for (Map.Entry<String, Map<String, String>> owned : new TreeMap<>(mappings.fields()).entrySet()) {
            String owner = owned.getKey();
            String targetOwner = target(mappings, owner);
            for (Map.Entry<String, String> field : new TreeMap<>(owned.getValue()).entrySet()) {
                writer.write("FD: " + owner + "/" + field.getKey() + " " + targetOwner + "/" + field.getValue() + "\n");
            }
        }
        for (Map.Entry<String, Map<String, String>> owned : new TreeMap<>(mappings.methods()).entrySet()) {
            String owner = owned.getKey();
            String targetOwner = target(mappings, owner);
            for (Map.Entry<String, String> method : new TreeMap<>(owned.getValue()).entrySet()) {
                String key = method.getKey();
                int paren = key.indexOf('(');
                String descriptor = key.substring(paren);
                writer.write("MD: " + owner + "/" + key.substring(0, paren) + " " + descriptor + " "
                        + targetOwner + "/" + method.getValue() + " " + mappings.mapDescriptor(descriptor) + "\n");
            }
        }
    }

    private static String target(Mappings mappings, String owner) {
        String mapped = mappings.mapClass(owner);
        return mapped == null ? owner : mapped;
    }
}

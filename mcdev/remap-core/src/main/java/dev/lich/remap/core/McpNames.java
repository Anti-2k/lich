package dev.lich.remap.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * MCP's human-readable names for SRG names ({@code field_71439_g} → {@code thePlayer}), from the
 * {@code fields.csv} and {@code methods.csv} of an {@code mcp_stable} zip (or Forge's copy of them). SRG member names are unique
 * across the game (overrides share one), so a plain name → name map is enough.
 */
public final class McpNames {
    private final Map<String, String> fields;
    private final Map<String, String> methods;

    private McpNames(Map<String, String> fields, Map<String, String> methods) {
        this.fields = fields;
        this.methods = methods;
    }

    /** Reads {@code fields.csv} and {@code methods.csv} from an {@code mcp_stable-<n>-<mc>.zip}. */
    public static McpNames readZip(Path zip) throws IOException {
        return readZip(zip, "");
    }

    /** Reads them from {@code directory} (empty, or ending in {@code /}) of a zip or jar, such as Forge's userdev {@code conf/}. */
    public static McpNames readZip(Path zip, String directory) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            return new McpNames(readCsv(file, directory + "fields.csv"), readCsv(file, directory + "methods.csv"));
        }
    }

    private static Map<String, String> readCsv(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new IOException(name + " is missing from " + zip.getName());
        }
        Map<String, String> names = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
            // searge,name,side,desc; only the first two columns matter, and they never contain commas.
            reader.readLine();
            for (String line; (line = reader.readLine()) != null; ) {
                String[] columns = line.split(",", 3);
                if (columns.length >= 2) {
                    names.put(columns[0], columns[1]);
                }
            }
        }
        return names;
    }

    /** The MCP name of an SRG field name, or the SRG name itself if MCP has none. */
    public String field(String srg) {
        return fields.getOrDefault(srg, srg);
    }

    /** The MCP name of an SRG method name, or the SRG name itself if MCP has none. */
    public String method(String srg) {
        return methods.getOrDefault(srg, srg);
    }

    /** obf → MCP: {@code obfToSrg} with MCP's names for its SRG member names (class names are the same). */
    public Mappings obfToMcp(Mappings obfToSrg) {
        return obfToSrg.renameTargets(this::field, this::method);
    }

    /**
     * MCP → SRG, for reobfuscating code compiled against MCP names. Class names are the same in both, so
     * classes map to themselves; members are keyed by their MCP name (and MCP descriptor, which is the
     * SRG one).
     */
    public Mappings mcpToSrg(Mappings obfToSrg) {
        Mappings.Builder mcpToSrg = Mappings.builder();
        for (String obf : obfToSrg.classNames()) {
            String srg = obfToSrg.mapClass(obf);
            mcpToSrg.mapClass(srg, srg);
        }
        obfToSrg.fields().forEach((owner, owned) -> {
            String srgOwner = classIn(obfToSrg, owner);
            owned.forEach((obf, srg) -> mcpToSrg.mapField(srgOwner, field(srg), srg));
        });
        obfToSrg.methods().forEach((owner, owned) -> {
            String srgOwner = classIn(obfToSrg, owner);
            owned.forEach((key, srg) -> {
                String descriptor = obfToSrg.mapDescriptor(key.substring(key.indexOf('(')));
                mcpToSrg.mapMethod(srgOwner, method(srg), descriptor, srg);
            });
        });
        return mcpToSrg.build();
    }

    private static String classIn(Mappings mappings, String owner) {
        String mapped = mappings.mapClass(owner);
        return mapped == null ? owner : mapped;
    }
}

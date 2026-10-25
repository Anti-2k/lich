package dev.lich.mcdev;

import dev.lich.remap.core.McpNames;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

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
 * MCP's {@code joined.exc}: the checked exceptions each method declares and a few access fixes, which the
 * obfuscator stripped from the game's class files. Without them a mod couldn't override, say,
 * {@code GuiScreen.keyTyped} with its {@code throws IOException}. Keyed by MCP names once read.
 */
final class McpExceptions {
    private record Fix(String[] exceptions, boolean makePublic) {
    }

    /** owner + "." + MCP name + descriptor → what to add. */
    private final Map<String, Fix> fixes;

    private McpExceptions(Map<String, Fix> fixes) {
        this.fixes = fixes;
    }

    /**
     * Reads {@code joined.exc} from an {@code mcp-<mc>-srg.zip}. Lines are
     * {@code owner.srgName(desc)=exceptions|parameters} or {@code owner.srgName(desc)-Access=PUBLIC}.
     */
    static McpExceptions read(Path srgZip, McpNames names) throws IOException {
        Map<String, Fix> fixes = new HashMap<>();
        try (ZipFile zip = new ZipFile(srgZip.toFile())) {
            ZipEntry entry = zip.getEntry("joined.exc");
            if (entry == null) {
                return new McpExceptions(fixes);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    int dot = line.indexOf('.');
                    int paren = line.indexOf('(');
                    if (line.startsWith("#") || dot < 0 || paren < dot) {
                        continue;
                    }
                    String owner = line.substring(0, dot);
                    String name = names.method(line.substring(dot + 1, paren));
                    boolean access = line.contains("-Access=PUBLIC");
                    int equals = line.indexOf('=');
                    int end = access ? line.indexOf("-Access") : equals;
                    String descriptor = line.substring(paren, end < 0 ? line.length() : end);
                    String[] exceptions = new String[0];
                    if (!access && equals >= 0) {
                        String thrown = line.substring(equals + 1, line.indexOf('|', equals) < 0 ? line.length() : line.indexOf('|', equals));
                        exceptions = thrown.isEmpty() ? exceptions : thrown.split(",");
                    }
                    String key = owner + "." + name + descriptor;
                    Fix existing = fixes.get(key);
                    fixes.put(key, new Fix(exceptions.length > 0 || existing == null ? exceptions : existing.exceptions(),
                            access || (existing != null && existing.makePublic())));
                }
            }
        }
        return new McpExceptions(fixes);
    }

    /** A visitor that adds the exceptions (and access fixes) to the methods it passes on, by their MCP names. */
    ClassVisitor visitor(ClassVisitor next) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            private String owner;

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                owner = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                Fix fix = fixes.get(owner + "." + name + descriptor);
                if (fix != null) {
                    if (fix.exceptions().length > 0 && (exceptions == null || exceptions.length == 0)) {
                        exceptions = fix.exceptions();
                    }
                    if (fix.makePublic()) {
                        access = (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
                    }
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        };
    }
}

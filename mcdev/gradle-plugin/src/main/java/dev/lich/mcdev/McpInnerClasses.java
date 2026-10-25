package dev.lich.mcdev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * MCP's {@code exceptor.json}: which classes are nested in which, as the {@code InnerClasses} and (for local and
 * anonymous classes) {@code EnclosingMethod} attributes the obfuscator stripped. 1.7.10's client has none left, so without them
 * javac sees {@code ResourcePackRepository$Entry} as a top-level class and {@code ResourcePackRepository.Entry}
 * doesn't compile. Keyed by class name, which SRG and MCP share.
 */
final class McpInnerClasses {
    private record Inner(String name, String outer, String innerName, int access) {
    }

    private final Map<String, List<Inner>> innerClasses;
    private final Map<String, String> enclosingClass;

    private McpInnerClasses(Map<String, List<Inner>> innerClasses, Map<String, String> enclosingClass) {
        this.innerClasses = innerClasses;
        this.enclosingClass = enclosingClass;
    }

    /** Reads {@code exceptor.json} from an {@code mcp-<mc>-srg.zip}; none there means nothing to add. */
    static McpInnerClasses read(Path srgZip) throws IOException {
        Map<String, List<Inner>> innerClasses = new HashMap<>();
        Map<String, String> enclosingClass = new HashMap<>();
        try (ZipFile zip = new ZipFile(srgZip.toFile())) {
            ZipEntry entry = zip.getEntry("exceptor.json");
            if (entry == null) {
                return new McpInnerClasses(innerClasses, enclosingClass);
            }
            try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                for (Map.Entry<String, JsonElement> cls : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                    JsonObject info = cls.getValue().getAsJsonObject();
                    if (info.has("enclosingMethod")) {
                        enclosingClass.put(cls.getKey(), info.getAsJsonObject("enclosingMethod").get("owner").getAsString());
                    }
                    if (info.has("innerClasses")) {
                        innerClasses.put(cls.getKey(), info.getAsJsonArray("innerClasses").asList().stream().map(element -> {
                            JsonObject inner = element.getAsJsonObject();
                            // MCP copied the class's own flags, ACC_SUPER included, which isn't an inner class flag.
                            int access = inner.has("access") ? Integer.parseInt(inner.get("access").getAsString(), 16) & ~Opcodes.ACC_SUPER : 0;
                            return new Inner(inner.get("inner_class").getAsString(), string(inner, "outer_class"),
                                    string(inner, "inner_name"), access);
                        }).toList());
                    }
                }
            }
        }
        return new McpInnerClasses(innerClasses, enclosingClass);
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : null;
    }

    /** A visitor that adds the attributes a class is missing, leaving those it still has. */
    ClassVisitor visitor(ClassVisitor next) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            private String name;
            private boolean hasOuterClass;
            private final Set<String> seen = new HashSet<>();

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                this.name = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public void visitOuterClass(String owner, String methodName, String methodDescriptor) {
                hasOuterClass = true;
                super.visitOuterClass(owner, methodName, methodDescriptor);
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                seen.add(name);
                super.visitInnerClass(name, outerName, innerName, access);
            }

            @Override
            public void visitEnd() {
                // Only local and anonymous classes may have one: javac takes a member class that has one for a
                // local class and won't let code name it. MCP lists an enclosing class for member classes too.
                String enclosing = enclosingClass.get(name);
                boolean member = innerClasses.getOrDefault(name, List.of()).stream()
                        .anyMatch(inner -> inner.name().equals(name) && inner.outer() != null);
                if (enclosing != null && !hasOuterClass && !member) {
                    super.visitOuterClass(enclosing, null, null);
                }
                for (Inner inner : innerClasses.getOrDefault(name, List.of())) {
                    if (seen.add(inner.name())) {
                        super.visitInnerClass(inner.name(), inner.outer(), inner.innerName(), inner.access());
                    }
                }
                super.visitEnd();
            }
        };
    }
}

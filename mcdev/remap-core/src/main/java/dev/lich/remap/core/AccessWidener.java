package dev.lich.remap.core;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A Lich mod's access widener: classes, fields and methods of the game made public, non-final or
 * extendable. The file format is Fabric's (so mod authors already know it):
 * <pre>
 * accessWidener v2 named
 * accessible class net/minecraft/client/renderer/GlStateManager$Color
 * accessible field net/minecraft/client/settings/KeyBinding keybindArray Ljava/util/List;
 * mutable field ...         (drops final)
 * extendable class|method   (non-final, at least protected)
 * </pre>
 * Mods write it with MCP names ({@code named}); the dev.lich.mod plugin rewrites it to SRG ({@code srg})
 * when it reobfuscates the jar, and Lich applies the SRG one to the game at runtime.
 */
public final class AccessWidener {
    public enum Access { ACCESSIBLE, MUTABLE, EXTENDABLE }

    /** One line: what it does, to which kind of thing, and the thing ({@code name}/{@code desc} null for classes). */
    public record Entry(Access access, String kind, String owner, String name, String descriptor) {
    }

    private final String namespace;
    private final List<Entry> entries;
    private final Map<String, List<Entry>> byClass = new HashMap<>();

    public AccessWidener(String namespace, List<Entry> entries) {
        this.namespace = namespace;
        this.entries = List.copyOf(entries);
        for (Entry entry : entries) {
            byClass.computeIfAbsent(entry.owner(), owner -> new ArrayList<>()).add(entry);
        }
    }

    public static AccessWidener parse(String text) throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader(text));
        String header = nextLine(reader);
        String[] head = header == null ? new String[0] : header.split("\\s+");
        if (head.length != 3 || !head[0].equals("accessWidener") || !(head[1].equals("v1") || head[1].equals("v2"))) {
            throw new IOException("Not an access widener (expected \"accessWidener v2 <namespace>\"): " + header);
        }
        List<Entry> entries = new ArrayList<>();
        for (String line; (line = nextLine(reader)) != null; ) {
            String[] parts = line.split("\\s+");
            Access access = switch (parts[0]) {
                case "accessible", "transitive-accessible" -> Access.ACCESSIBLE;
                case "mutable", "transitive-mutable" -> Access.MUTABLE;
                case "extendable", "transitive-extendable" -> Access.EXTENDABLE;
                default -> throw new IOException("Unknown access widener access: " + line);
            };
            String kind = parts.length > 1 ? parts[1] : "";
            switch (kind) {
                case "class" -> {
                    if (parts.length != 3) {
                        throw new IOException("Expected \"<access> class <name>\": " + line);
                    }
                    entries.add(new Entry(access, kind, parts[2], null, null));
                }
                case "field", "method" -> {
                    if (parts.length != 5) {
                        throw new IOException("Expected \"<access> " + kind + " <owner> <name> <descriptor>\": " + line);
                    }
                    entries.add(new Entry(access, kind, parts[2], parts[3], parts[4]));
                }
                default -> throw new IOException("Unknown access widener target: " + line);
            }
        }
        return new AccessWidener(head[2], entries);
    }

    /** The next line without comment, or {@code null} at the end; blank lines are skipped. */
    private static String nextLine(BufferedReader reader) throws IOException {
        for (String line; (line = reader.readLine()) != null; ) {
            int hash = line.indexOf('#');
            String content = (hash >= 0 ? line.substring(0, hash) : line).strip();
            if (!content.isEmpty()) {
                return content;
            }
        }
        return null;
    }

    public String namespace() {
        return namespace;
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Whether this widens anything in {@code className} (internal name). */
    public boolean targets(String className) {
        return byClass.containsKey(className);
    }

    /** The internal names of the classes this changes. */
    public Set<String> targetClasses() {
        return new HashSet<>(byClass.keySet());
    }

    /** This widener with names mapped by {@code remapper}, labelled {@code namespace}. */
    public AccessWidener remap(MappingRemapper remapper, String namespace) {
        List<Entry> remapped = new ArrayList<>();
        for (Entry entry : entries) {
            String owner = remapper.map(entry.owner());
            remapped.add(switch (entry.kind()) {
                case "class" -> new Entry(entry.access(), "class", owner, null, null);
                case "field" -> new Entry(entry.access(), "field", owner,
                        remapper.mapFieldName(entry.owner(), entry.name(), entry.descriptor()), remapper.mapDesc(entry.descriptor()));
                default -> new Entry(entry.access(), "method", owner,
                        remapper.mapMethodName(entry.owner(), entry.name(), entry.descriptor()), remapper.mapMethodDesc(entry.descriptor()));
            });
        }
        return new AccessWidener(namespace, remapped);
    }

    public String write() {
        StringBuilder out = new StringBuilder("accessWidener v2 ").append(namespace).append('\n');
        for (Entry entry : entries) {
            out.append(entry.access().name().toLowerCase(Locale.ROOT)).append(' ').append(entry.kind()).append(' ').append(entry.owner());
            if (entry.name() != null) {
                out.append(' ').append(entry.name()).append(' ').append(entry.descriptor());
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * A visitor that applies this widener to the class it visits. Making a private method accessible also
     * makes it final (it becomes virtual, so a subclass could otherwise override it by accident) and turns
     * the class's own {@code invokespecial} calls to it into {@code invokevirtual}, as Fabric does.
     */
    public ClassVisitor visitor(ClassVisitor next) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            private String className;
            private List<Entry> own = List.of();
            /** Widened methods: the class's invokespecial calls to them can only be calls to them as private methods. */
            private final Set<String> widenedMethods = new HashSet<>();

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                className = name;
                own = byClass.getOrDefault(name, List.of());
                for (Entry entry : own) {
                    if (entry.kind().equals("method") && entry.access() != Access.MUTABLE && !entry.name().equals("<init>")) {
                        widenedMethods.add(entry.name() + entry.descriptor());
                    }
                }
                super.visit(version, classAccess(name, access), name, signature, superName, interfaces);
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                super.visitInnerClass(name, outerName, innerName, byClass.containsKey(name) ? classAccess(name, access) : access);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                for (Entry entry : own) {
                    if (entry.kind().equals("field") && entry.name().equals(name) && entry.descriptor().equals(descriptor)) {
                        access = switch (entry.access()) {
                            case ACCESSIBLE -> makePublic(access);
                            case MUTABLE -> access & ~Opcodes.ACC_FINAL;
                            case EXTENDABLE -> access;
                        };
                    }
                }
                return super.visitField(access, name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                for (Entry entry : own) {
                    if (entry.kind().equals("method") && entry.name().equals(name) && entry.descriptor().equals(descriptor)) {
                        boolean wasPrivate = (access & Opcodes.ACC_PRIVATE) != 0;
                        boolean virtual = (access & Opcodes.ACC_STATIC) == 0 && !name.equals("<init>");
                        switch (entry.access()) {
                            case ACCESSIBLE -> {
                                access = makePublic(access);
                                if (wasPrivate && virtual) {
                                    access |= Opcodes.ACC_FINAL;
                                }
                            }
                            case EXTENDABLE -> {
                                if ((access & Opcodes.ACC_PUBLIC) == 0) {
                                    access = (access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PROTECTED;
                                }
                                access &= ~Opcodes.ACC_FINAL;
                            }
                            case MUTABLE -> {
                            }
                        }
                    }
                }
                MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (widenedMethods.isEmpty()) {
                    return visitor;
                }
                return new MethodVisitor(Opcodes.ASM9, visitor) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESPECIAL && owner.equals(className)
                                && widenedMethods.contains(methodName + methodDescriptor)) {
                            opcode = Opcodes.INVOKEVIRTUAL;
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                    }
                };
            }

            private int classAccess(String name, int access) {
                for (Entry entry : byClass.getOrDefault(name, List.of())) {
                    // A widened member is no use in a class nobody can see.
                    access = makePublic(access);
                    if (entry.kind().equals("class") && entry.access() == Access.EXTENDABLE) {
                        access &= ~Opcodes.ACC_FINAL;
                    }
                }
                return access;
            }
        };
    }

    private static int makePublic(int access) {
        return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
    }
}

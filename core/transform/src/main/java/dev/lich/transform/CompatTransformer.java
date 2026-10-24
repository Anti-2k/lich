package dev.lich.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lich's compat pass, run on every class Lich defines, before any LaunchWrapper transformer. It
 * <ul>
 *   <li>renames references to removed JDK types to Lich's look-alike shims
 *       ({@code java/util/jar/Pack200} → {@code dev/lich/compat/jdk/Pack200}),</li>
 *   <li>renames string constants that name removed classes for reflection
 *       ({@code "sun.reflect.FieldAccessor"}),</li>
 *   <li>redirects calls to JDK methods that now throw or behave differently to static replacements,</li>
 *   <li>and applies {@link ClassPatch}es to classes by name.</li>
 * </ul>
 * String renames and redirects can be limited to classes that also contain a marker string, so a
 * common call like {@code Class.getDeclaredField} is only redirected in classes that use the hack it
 * fixes.
 *
 * <p>Most classes need none of it, so each rule first checks the raw class bytes for the strings it
 * needs; only classes that some rule might apply to are parsed.
 */
public final class CompatTransformer {
    /** Told about every change, so boot can log which class used which removed API. */
    @FunctionalInterface
    public interface Listener {
        void changed(String className, String what);
    }

    private record Member(String owner, String name, String descriptor) {
    }

    /** A rule's byte check: all of {@code required}, and one of {@code anyOf} unless that's empty. */
    private record Needles(byte[][] required, byte[][] anyOf) {
        static Needles of(List<String> required, List<String> anyOf) {
            return new Needles(required.stream().map(CompatTransformer::utf8).toArray(byte[][]::new),
                    anyOf.stream().map(CompatTransformer::utf8).toArray(byte[][]::new));
        }

        boolean foundIn(byte[] bytes) {
            for (byte[] needle : required) {
                if (indexOf(bytes, needle) < 0) {
                    return false;
                }
            }
            if (anyOf.length == 0) {
                return true;
            }
            for (byte[] needle : anyOf) {
                if (indexOf(bytes, needle) >= 0) {
                    return true;
                }
            }
            return false;
        }
    }

    private record TypeRename(String from, String to, Needles needles) {
    }

    private record StringRename(String from, String to, Needles needles) {
    }

    private record Redirect(Member member, String target, Needles needles) {
    }

    private final List<TypeRename> typeRenames;
    private final List<StringRename> stringRenames;
    private final List<Redirect> redirects;
    private final Map<String, List<ClassPatch>> patches;
    private final Listener listener;

    private CompatTransformer(Builder builder) {
        this.typeRenames = List.copyOf(builder.typeRenames);
        this.stringRenames = List.copyOf(builder.stringRenames);
        this.redirects = List.copyOf(builder.redirects);
        Map<String, List<ClassPatch>> patches = new HashMap<>();
        builder.patches.forEach((name, list) -> patches.put(name, List.copyOf(list)));
        this.patches = Map.copyOf(patches);
        this.listener = builder.listener;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the transformed class, or {@code bytes} itself (the same array) when nothing applies.
     *
     * @param name the class's binary name ({@code a.b.C}), used to look up patches
     */
    public byte[] transform(String name, byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        Map<String, String> renames = new HashMap<>();
        for (TypeRename rename : typeRenames) {
            if (rename.needles().foundIn(bytes)) {
                renames.put(rename.from(), rename.to());
            }
        }
        Map<String, String> strings = new HashMap<>();
        for (StringRename rename : stringRenames) {
            if (rename.needles().foundIn(bytes)) {
                strings.put(rename.from(), rename.to());
            }
        }
        Map<Member, String> calls = new HashMap<>();
        for (Redirect redirect : redirects) {
            if (redirect.needles().foundIn(bytes)) {
                calls.put(redirect.member(), redirect.target());
            }
        }
        List<ClassPatch> classPatches = patches.getOrDefault(name, List.of());
        if (renames.isEmpty() && strings.isEmpty() && calls.isEmpty() && classPatches.isEmpty()) {
            return bytes;
        }

        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        boolean changed = false;
        if (!renames.isEmpty()) {
            // Counted as a change even if the name only matched inside a string: rewriting is harmless.
            reader.accept(new ClassRemapper(node, new SimpleRemapper(renames)), 0);
            changed = true;
            listener.changed(name, "renamed removed JDK types " + renames.keySet());
        } else {
            reader.accept(node, 0);
        }
        changed |= renameStrings(name, node, strings);
        changed |= redirect(name, node, calls);
        for (ClassPatch patch : classPatches) {
            if (patch.apply(node)) {
                changed = true;
                listener.changed(name, "patched by " + patch.getClass().getName());
            }
        }
        if (!changed) {
            return bytes;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private boolean renameStrings(String className, ClassNode node, Map<String, String> strings) {
        if (strings.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String value && strings.containsKey(value)) {
                    ldc.cst = strings.get(value);
                    listener.changed(className, "renamed string \"" + value + "\" to \"" + ldc.cst + "\"");
                    changed = true;
                }
            }
        }
        return changed;
    }

    private boolean redirect(String className, ClassNode node, Map<Member, String> calls) {
        if (calls.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESPECIAL) {
                    continue;
                }
                String target = calls.get(new Member(call.owner, call.name, call.desc));
                if (target == null) {
                    continue;
                }
                listener.changed(className, "redirected " + call.owner + "." + call.name + " to " + target);
                if (call.getOpcode() != Opcodes.INVOKESTATIC) {
                    // The receiver becomes the first argument; the stack stays exactly the same.
                    call.desc = "(L" + call.owner + ";" + call.desc.substring(1);
                }
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = target;
                call.itf = false;
                changed = true;
            }
        }
        return changed;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0, last = haystack.length - needle.length; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Collects the rules; type names are internal names ({@code a/b/C}), patch targets binary names. */
    public static final class Builder {
        private final List<TypeRename> typeRenames = new ArrayList<>();
        private final List<StringRename> stringRenames = new ArrayList<>();
        private final List<Redirect> redirects = new ArrayList<>();
        private final Map<String, List<ClassPatch>> patches = new HashMap<>();
        private Listener listener = (className, what) -> {
        };

        private Builder() {
        }

        /** Every reference to type {@code from} becomes {@code to}. Nested types need their own rename. */
        public Builder renameType(String from, String to) {
            typeRenames.add(new TypeRename(from, to, Needles.of(List.of(from), List.of())));
            return this;
        }

        /**
         * Every string constant equal to {@code from} becomes {@code to}, in classes that also contain
         * one of {@code markers} (any class, if there are none).
         */
        public Builder renameString(String from, String to, String... markers) {
            stringRenames.add(new StringRename(from, to, Needles.of(List.of(from), List.of(markers))));
            return this;
        }

        /**
         * Calls to {@code owner.name descriptor} become calls to the static method {@code target.name},
         * in classes that also contain one of {@code markers} (any class, if there are none). For instance
         * methods the target takes the receiver as its first parameter.
         */
        public Builder redirect(String owner, String name, String descriptor, String target, String... markers) {
            redirects.add(new Redirect(new Member(owner, name, descriptor), target,
                    Needles.of(List.of(owner, name), List.of(markers))));
            return this;
        }

        /** Applies {@code patch} to the class with binary name {@code className}. */
        public Builder patch(String className, ClassPatch patch) {
            patches.computeIfAbsent(className, k -> new ArrayList<>()).add(patch);
            return this;
        }

        public Builder listener(Listener listener) {
            this.listener = listener;
            return this;
        }

        public CompatTransformer build() {
            return new CompatTransformer(this);
        }
    }
}

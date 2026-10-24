package dev.lich.compat.lwjgl2.linkage;

import dev.lich.compat.lwjgl2.Lwjgl2Compat;
import dev.lich.transform.CompatTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lists the LWJGL references in a set of jars that wouldn't link under Lich: each class is run through
 * the LWJGL call table first, then every {@code org/lwjgl/} type, method and field it still names is looked
 * up in LWJGL 3, compat-lwjgl2 (this program's class path) and the checked jars themselves (lwjgl_util).
 * Members of a missing class are listed too, so the list says what a new compat class has to provide.
 * That's the to-do list for compat-lwjgl2, and the regression check after changing it.
 *
 * <p>Arguments: jars or directories (searched for jars, nested jars included). Prints one line per
 * missing member with the jars that use it.
 */
public final class LinkageCheck {
    private final Map<String, ClassNode> provided = new HashMap<>();
    private final Map<String, Set<String>> missing = new TreeMap<>();
    private final CompatTransformer table = Lwjgl2Compat.register(CompatTransformer.builder()).build();

    private LinkageCheck() {
    }

    public static void main(String[] args) throws IOException {
        LinkageCheck check = new LinkageCheck();
        Map<String, Map<String, byte[]>> jars = new TreeMap<>();
        for (String arg : args) {
            for (Path jar : jars(Path.of(arg))) {
                try (InputStream in = Files.newInputStream(jar)) {
                    check.read(jar.getFileName().toString(), in, jars);
                }
            }
        }
        jars.values().forEach(classes -> classes.forEach((name, bytes) -> check.provided.putIfAbsent(name, node(bytes))));
        jars.forEach((jar, classes) -> classes.forEach((name, bytes) -> check.check(jar, name, bytes)));
        check.missing.forEach((reference, users) -> System.out.println(reference + "  <- " + String.join(", ", users)));
        System.out.println(check.missing.size() + " missing LWJGL references in " + jars.size() + " jars");
    }

    private static List<Path> jars(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return List.of(path);
        }
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }
    }

    /** Collects the classes of one jar, and of the jars nested in it, leaving LWJGL 2 itself out. */
    private void read(String name, InputStream in, Map<String, Map<String, byte[]>> jars) throws IOException {
        Map<String, byte[]> classes = new HashMap<>();
        ZipInputStream zip = new ZipInputStream(in);
        for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
            if (entry.getName().endsWith(".jar")) {
                read(name + "!" + entry.getName(), new ByteArrayInputStream(zip.readAllBytes()), jars);
            } else if (entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/")) {
                classes.put(entry.getName().substring(0, entry.getName().length() - 6), zip.readAllBytes());
            }
        }
        if (classes.containsKey("org/lwjgl/Sys") || classes.containsKey("org/lwjgl/system/Library")) {
            return;
        }
        jars.put(name, classes);
    }

    private void check(String jar, String className, byte[] bytes) {
        ClassNode node;
        try {
            node = node(table.transform(className.replace('/', '.'), bytes));
        } catch (RuntimeException e) {
            System.err.println("Can't transform " + className + " in " + jar + ": " + e);
            return;
        }
        String user = jar.replaceAll(".*!", "");
        type(user, node.superName);
        if (node.interfaces != null) {
            node.interfaces.forEach(i -> type(user, i));
        }
        for (FieldNode field : node.fields) {
            descriptor(user, field.desc);
        }
        for (MethodNode method : node.methods) {
            descriptor(user, method.desc);
            for (AbstractInsnNode insn : method.instructions) {
                switch (insn) {
                    case MethodInsnNode call -> method(user, call.owner, call.name, call.desc);
                    case FieldInsnNode field -> field(user, field.owner, field.name, field.desc);
                    case TypeInsnNode type -> type(user, type.desc);
                    case MultiANewArrayInsnNode array -> descriptor(user, array.desc);
                    case LdcInsnNode ldc when ldc.cst instanceof Type type -> descriptor(user, type.getDescriptor());
                    case InvokeDynamicInsnNode indy -> {
                        descriptor(user, indy.desc);
                        for (Object arg : indy.bsmArgs) {
                            if (arg instanceof Handle handle) {
                                if (handle.getTag() <= Opcodes.H_PUTSTATIC) {
                                    field(user, handle.getOwner(), handle.getName(), handle.getDesc());
                                } else {
                                    method(user, handle.getOwner(), handle.getName(), handle.getDesc());
                                }
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
        }
    }

    private void descriptor(String user, String descriptor) {
        Type type = Type.getType(descriptor);
        if (type.getSort() == Type.METHOD) {
            descriptor(user, type.getReturnType().getDescriptor());
            for (Type argument : type.getArgumentTypes()) {
                descriptor(user, argument.getDescriptor());
            }
        } else if (type.getSort() == Type.ARRAY) {
            descriptor(user, type.getElementType().getDescriptor());
        } else if (type.getSort() == Type.OBJECT) {
            type(user, type.getInternalName());
        }
    }

    private void type(String user, String name) {
        if (name == null) {
            return;
        }
        if (name.startsWith("[")) {
            descriptor(user, name);
            return;
        }
        if (isLwjgl(name) && lookup(name) == null) {
            report("class " + name, user);
        }
    }

    private void method(String user, String owner, String name, String descriptor) {
        if (owner.startsWith("[")) {
            return;
        }
        descriptor(user, descriptor);
        if (!isLwjgl(owner)) {
            return;
        }
        if (lookup(owner) == null) {
            report("class " + owner, user);
        }
        if (!hasMethod(owner, name, descriptor)) {
            report("method " + owner + "." + name + descriptor, user);
        }
    }

    private void field(String user, String owner, String name, String descriptor) {
        descriptor(user, descriptor);
        if (!isLwjgl(owner)) {
            return;
        }
        if (lookup(owner) == null) {
            report("class " + owner, user);
        }
        if (!hasField(owner, name, descriptor)) {
            report("field " + owner + "." + name + ":" + descriptor, user);
        }
    }

    private static boolean isLwjgl(String name) {
        return name.startsWith("org/lwjgl/");
    }

    private void report(String reference, String user) {
        missing.computeIfAbsent(reference, k -> new TreeSet<>()).add(user);
    }

    private boolean hasMethod(String owner, String name, String descriptor) {
        List<String> queue = new ArrayList<>(List.of(owner));
        while (!queue.isEmpty()) {
            ClassNode type = lookup(queue.removeFirst());
            if (type == null) {
                continue;
            }
            for (MethodNode method : type.methods) {
                if (method.name.equals(name) && method.desc.equals(descriptor)) {
                    return true;
                }
            }
            if (type.superName != null) {
                queue.add(type.superName);
            }
            queue.addAll(type.interfaces);
        }
        return false;
    }

    private boolean hasField(String owner, String name, String descriptor) {
        List<String> queue = new ArrayList<>(List.of(owner));
        while (!queue.isEmpty()) {
            ClassNode type = lookup(queue.removeFirst());
            if (type == null) {
                continue;
            }
            for (FieldNode field : type.fields) {
                if (field.name.equals(name) && field.desc.equals(descriptor)) {
                    return true;
                }
            }
            if (type.superName != null) {
                queue.add(type.superName);
            }
            queue.addAll(type.interfaces);
        }
        return false;
    }

    /** A class from the checked jars, or from this program's class path (LWJGL 3, compat-lwjgl2, the JDK). */
    private ClassNode lookup(String name) {
        ClassNode type = provided.get(name);
        if (type != null || provided.containsKey(name)) {
            return type;
        }
        try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
            type = in == null ? null : node(in.readAllBytes());
        } catch (IOException e) {
            type = null;
        }
        provided.put(name, type);
        return type;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }
}

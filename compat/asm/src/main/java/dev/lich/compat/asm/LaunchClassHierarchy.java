package dev.lich.compat.asm;

import net.minecraft.launchwrapper.IClassNameTransformer;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code getCommonSuperClass} for a {@code ClassWriter} that looks classes up through LaunchClassLoader, answered
 * from the classes' bytes instead of by loading them. ASM's own version calls {@code Class.forName}, which defines
 * the class and runs every transformer on it. That is harmless while the game runs, but a coremod that computes
 * frames for a class Mixin is preparing (Mixin reads a mixin target through the transformer chain, holding its
 * re-entrance lock) makes Mixin's transformer run again inside itself: it throws, LaunchClassLoader remembers the
 * class as invalid, and the game's own load of it fails later. Bytes read here go through the rename transformer
 * only (FML's deobfuscation, so names match the ones the coremod sees) and nothing is defined. A class that is
 * already loaded is read from the {@code Class}; one LaunchClassLoader has no bytes for (the JDK's) is loaded as
 * before, which runs no transformers. DOCS.md §2.4.
 *
 * <p>Like ASM's version it ignores what transformers would change in a class's header: a coremod that changes a
 * superclass is rare, and Mixin can't.
 */
final class LaunchClassHierarchy {
    private static final String OBJECT = "java/lang/Object";
    private static final MethodHandle FIND_LOADED_CLASS = findLoadedClass();

    private final LaunchClassLoader loader;
    private final IClassNameTransformer names;
    private final IClassTransformer rename;
    private final Map<String, Node> nodes = new HashMap<>();

    private record Node(String superName, List<String> interfaces, boolean isInterface) {
    }

    LaunchClassHierarchy(LaunchClassLoader loader) {
        this.loader = loader;
        IClassNameTransformer found = null;
        for (IClassTransformer transformer : loader.getTransformers()) {
            if (transformer instanceof IClassNameTransformer nameTransformer) {
                found = nameTransformer;
                break;
            }
        }
        this.names = found;
        this.rename = found instanceof IClassTransformer transformer ? transformer : null;
    }

    /** What {@code ClassWriter.getCommonSuperClass} returns for these internal names, with the same rules. */
    String commonSuperClass(String type1, String type2) {
        if (isAssignable(type1, type2)) {
            return type1;
        }
        if (isAssignable(type2, type1)) {
            return type2;
        }
        if (node(type1).isInterface() || node(type2).isInterface()) {
            return OBJECT;
        }
        String common = type1;
        do {
            common = node(common).superName();
            if (common == null) {
                return OBJECT;
            }
        } while (!isAssignable(common, type2));
        return common;
    }

    /** Whether {@code target} is {@code from} or one of its superclasses or interfaces. */
    private boolean isAssignable(String target, String from) {
        if (target.equals(from) || target.equals(OBJECT)) {
            return true;
        }
        Deque<String> pending = new ArrayDeque<>(List.of(from));
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            String type = pending.pop();
            if (!seen.add(type)) {
                continue;
            }
            if (type.equals(target)) {
                return true;
            }
            Node node = node(type);
            if (node.superName() != null) {
                pending.push(node.superName());
            }
            node.interfaces().forEach(pending::push);
        }
        return false;
    }

    private Node node(String type) {
        Node node = nodes.get(type);
        if (node == null) {
            node = read(type);
            nodes.put(type, node);
        }
        return node;
    }

    private Node read(String type) {
        String name = type.replace('/', '.');
        Class<?> loaded = loaded(name);
        if (loaded != null) {
            return of(loaded);
        }
        String raw = names == null ? name : names.unmapClassName(name);
        byte[] bytes;
        try {
            bytes = loader.getClassBytes(raw);
        } catch (IOException e) {
            bytes = null;
        }
        if (bytes != null) {
            if (rename != null) {
                bytes = rename.transform(raw, name, bytes);
            }
            ClassReader reader = new ClassReader(bytes);
            return new Node(reader.getSuperName(), List.of(reader.getInterfaces()),
                    (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0);
        }
        try {
            return of(Class.forName(name, false, loader));
        } catch (ClassNotFoundException | LinkageError e) {
            throw new TypeNotPresentException(type, e);
        }
    }

    private Class<?> loaded(String name) {
        if (FIND_LOADED_CLASS == null) {
            return null;
        }
        try {
            return (Class<?>) FIND_LOADED_CLASS.invokeExact((ClassLoader) loader, name);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Node of(Class<?> type) {
        Class<?> superclass = type.getSuperclass();
        List<String> interfaces = Arrays.stream(type.getInterfaces())
                .map(LaunchClassHierarchy::internalName).toList();
        return new Node(superclass == null ? null : internalName(superclass), interfaces, type.isInterface());
    }

    private static String internalName(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    /** {@code ClassLoader.findLoadedClass}, which Lich's {@code --add-opens java.base/java.lang} makes reachable. */
    private static MethodHandle findLoadedClass() {
        try {
            return MethodHandles.privateLookupIn(ClassLoader.class, MethodHandles.lookup())
                    .findVirtual(ClassLoader.class, "findLoadedClass", MethodType.methodType(Class.class, String.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}

package dev.lich.remap.core;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * An ASM {@link Remapper} over {@link Mappings} that also maps members referenced through a subtype of
 * the class declaring them ({@code EntityPlayerSP.posX} is mapped on {@code Entity}), and overrides
 * declared in classes the mappings don't list (a mod's {@code GuiScreen} subclass), by searching the
 * owner's supertypes in {@link ClassHierarchy}. Lookups are cached; it's thread-safe.
 *
 * <p>Use {@link #classVisitor} rather than a plain {@code ClassRemapper}: it also maps the method a lambda
 * implements, which is named in an {@code invokedynamic} instruction rather than a member reference.
 */
public final class MappingRemapper extends Remapper {
    private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";

    private final Mappings mappings;
    private final ClassHierarchy hierarchy;
    private final Map<String, Optional<String>> fieldCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<String>> methodCache = new ConcurrentHashMap<>();

    public MappingRemapper(Mappings mappings, ClassHierarchy hierarchy) {
        super();
        this.mappings = mappings;
        this.hierarchy = hierarchy;
    }

    public Mappings mappings() {
        return mappings;
    }

    /** A class visitor that remaps everything it sees with this remapper before passing it to {@code next}. */
    public ClassVisitor classVisitor(ClassVisitor next) {
        return new ClassRemapper(Opcodes.ASM9, next, this) {
            @Override
            protected MethodVisitor createMethodRemapper(MethodVisitor methodVisitor) {
                return new LambdaRemapper(methodVisitor, MappingRemapper.this);
            }
        };
    }

    @Override
    public String map(String internalName) {
        String mapped = mappings.mapClass(internalName);
        return mapped != null ? mapped : internalName;
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        if (owner.startsWith("[")) {
            return name;
        }
        return fieldCache.computeIfAbsent(owner + '.' + name, key -> find(owner, type -> mappings.field(type, name)))
                .orElse(name);
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        if (owner.startsWith("[") || name.startsWith("<")) {
            return name;
        }
        return methodCache.computeIfAbsent(owner + '.' + name + descriptor,
                key -> find(owner, type -> mappings.method(type, name, descriptor))).orElse(name);
    }

    /**
     * Breadth-first through {@code owner} and its supertypes, the first mapping found. Fields and methods
     * are inherited from both super classes and interfaces, and a well-formed mapping never maps one
     * member two different ways along one hierarchy, so the search order rarely matters.
     */
    private Optional<String> find(String owner, Function<String, String> lookup) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            String type = queue.removeFirst();
            if (!seen.add(type)) {
                continue;
            }
            String mapped = lookup.apply(type);
            if (mapped != null) {
                return Optional.of(mapped);
            }
            ClassHierarchy.Supertypes supertypes = hierarchy.supertypes(type);
            if (supertypes != null) {
                if (supertypes.superName() != null) {
                    queue.add(supertypes.superName());
                }
                queue.addAll(supertypes.interfaces());
            }
        }
        return Optional.empty();
    }

    /**
     * Maps the interface method of a {@code LambdaMetafactory} call site, which is the call site's name:
     * {@code invokedynamic accept()Lnet/minecraft/X;} implements {@code X.accept}, with the erased
     * descriptor in the first bootstrap argument.
     */
    private static final class LambdaRemapper extends MethodRemapper {
        LambdaRemapper(MethodVisitor methodVisitor, Remapper remapper) {
            super(Opcodes.ASM9, methodVisitor, remapper);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
            if (bootstrap.getOwner().equals(LAMBDA_METAFACTORY) && arguments.length > 0 && arguments[0] instanceof Type sam) {
                String functionalInterface = Type.getReturnType(descriptor).getInternalName();
                name = remapper.mapMethodName(functionalInterface, name, sam.getDescriptor());
            }
            super.visitInvokeDynamicInsn(name, descriptor, bootstrap, arguments);
        }
    }
}

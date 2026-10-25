package dev.lich.remap.core;

import org.objectweb.asm.ClassReader;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Super class and interfaces of classes, by internal name, in the namespace being remapped from. Only
 * classes whose members can be mapped need to be known: the search for an inherited member stops at
 * classes this returns {@code null} for (JDK and library classes).
 */
@FunctionalInterface
public interface ClassHierarchy {
    /** The class's direct supertypes, or {@code null} if the class is unknown. */
    Supertypes supertypes(String internalName);

    /** {@code superName} is {@code null} only for {@code java/lang/Object}. */
    record Supertypes(String superName, List<String> interfaces) {
        public static Supertypes of(byte[] classFile) {
            ClassReader reader = new ClassReader(classFile);
            return new Supertypes(reader.getSuperName(), List.of(reader.getInterfaces()));
        }
    }

    /**
     * A hierarchy read lazily from class files: {@code bytes} returns a class's bytes by internal name, or
     * {@code null} for classes outside the code base. Results are cached, so {@code bytes} is asked at
     * most once per class. Thread-safe if {@code bytes} is.
     */
    static ClassHierarchy fromClassFiles(Function<String, byte[]> bytes) {
        Map<String, Optional<Supertypes>> cache = new ConcurrentHashMap<>();
        return name -> cache.computeIfAbsent(name, n -> {
            byte[] classFile = bytes.apply(n);
            return classFile == null ? Optional.empty() : Optional.of(Supertypes.of(classFile));
        }).orElse(null);
    }

    /** Asks this hierarchy first, then {@code other}. */
    default ClassHierarchy then(ClassHierarchy other) {
        return name -> {
            Supertypes found = supertypes(name);
            return found != null ? found : other.supertypes(name);
        };
    }
}

package dev.lich.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Facts about classes read from their class files, without loading them: a compat rule that runs while a
 * class is being defined can't load other classes.
 */
public final class ClassFiles {
    private static final Map<String, Kind> KINDS = new ConcurrentHashMap<>();

    private enum Kind { CLASS, INTERFACE, UNKNOWN }

    private ClassFiles() {
    }

    /**
     * Whether {@code internalName} is an interface. Looked up through the context class loader
     * (LaunchClassLoader once the launch has started), then the class path; the JDK's own classes come
     * from its modules either way. Unknown classes count as no interface.
     */
    public static boolean isInterface(String internalName) {
        return kind(internalName) == Kind.INTERFACE;
    }

    /** Whether {@code internalName} has a class file, found as {@link #isInterface} finds it, that isn't an interface. */
    public static boolean isKnownClass(String internalName) {
        return kind(internalName) == Kind.CLASS;
    }

    private static Kind kind(String internalName) {
        return KINDS.computeIfAbsent(internalName, ClassFiles::readKind);
    }

    private static Kind readKind(String internalName) {
        String resource = internalName + ".class";
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        URL url = context == null ? null : context.getResource(resource);
        if (url == null) {
            url = ClassLoader.getSystemResource(resource);
        }
        if (url == null) {
            return Kind.UNKNOWN;
        }
        try (InputStream in = url.openStream()) {
            return (new ClassReader(in.readAllBytes()).getAccess() & Opcodes.ACC_INTERFACE) != 0 ? Kind.INTERFACE : Kind.CLASS;
        } catch (IOException | IllegalArgumentException unreadable) {
            return Kind.UNKNOWN;
        }
    }
}

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
    private static final Map<String, Boolean> INTERFACES = new ConcurrentHashMap<>();

    private ClassFiles() {
    }

    /**
     * Whether {@code internalName} is an interface. Looked up through the context class loader
     * (LaunchClassLoader once the launch has started), then the class path; the JDK's own classes come
     * from its modules either way. Unknown classes count as no interface.
     */
    public static boolean isInterface(String internalName) {
        return INTERFACES.computeIfAbsent(internalName, ClassFiles::readIsInterface);
    }

    private static boolean readIsInterface(String internalName) {
        String resource = internalName + ".class";
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        URL url = context == null ? null : context.getResource(resource);
        if (url == null) {
            url = ClassLoader.getSystemResource(resource);
        }
        if (url == null) {
            return false;
        }
        try (InputStream in = url.openStream()) {
            return (new ClassReader(in.readAllBytes()).getAccess() & Opcodes.ACC_INTERFACE) != 0;
        } catch (IOException | IllegalArgumentException unreadable) {
            return false;
        }
    }
}

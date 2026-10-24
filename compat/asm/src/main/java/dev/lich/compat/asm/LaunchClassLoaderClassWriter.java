package dev.lich.compat.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * What {@code new ClassWriter(...)} in Forge 1.7.10 code creates under Lich (see {@link AsmCompat#registerFml1710}).
 * FML 1.7.10 let LaunchClassLoader define its own copy of ASM, so a plain {@code ClassWriter} computing
 * frames ({@code getCommonSuperClass}) looked classes up through LaunchClassLoader: game classes, mod jars
 * and libraries that mods add at runtime (FalsePatternLib's downloads for GTNHLib and Hodgepodge). Lich
 * serves one ASM from the system class loader, which sees none of those, so this subclass looks them up
 * through the context class loader, which is LaunchClassLoader while the game runs.
 */
public class LaunchClassLoaderClassWriter extends ClassWriter {
    public LaunchClassLoaderClassWriter(int flags) {
        super(flags);
    }

    public LaunchClassLoaderClassWriter(ClassReader classReader, int flags) {
        super(classReader, flags);
    }

    @Override
    protected ClassLoader getClassLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : super.getClassLoader();
    }
}

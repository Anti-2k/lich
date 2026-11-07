package dev.lich.compat.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * What {@code new ClassWriter(...)} in mod code creates under Lich. A writer made from a {@code ClassReader} copies
 * the class's constant pool, and some coremods then add a method <em>before</em> {@code reader.accept(writer)} gives
 * the writer the class header (EnderCore's elytra patch). ASM 5 only looked at the class version when it wrote the
 * class, so a stack map frame in that method was kept; ASM 9 needs the version when the frame arrives and throws
 * for a writer that has none yet, which {@link Asm5Frames} would take for a Java 5 class and drop the frame of,
 * and Java 8 classes then fail to verify. Here {@link Asm5Frames} can ask the writer to take the version from its
 * reader, which the later {@code accept} sets again to the same values.
 */
public class Asm5ClassWriter extends ClassWriter {
    // The weak reference matters: the writer's method list holds the key, so a strong value would keep both alive.
    private static final Map<MethodVisitor, WeakReference<Asm5ClassWriter>> OWNERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final ClassReader source;
    private boolean throughContext;

    public Asm5ClassWriter(int flags) {
        super(flags);
        this.source = null;
    }

    public Asm5ClassWriter(ClassReader classReader, int flags) {
        super(classReader, flags);
        this.source = classReader;
    }

    /**
     * A class ASM's own class loader can't see is looked up through the context class loader (LaunchClassLoader while
     * the game runs) before giving up. Essential's relaunch gives ASM a class loader of its own, where a coremod computing
     * frames (TenshiCore) doesn't find the game's classes.
     */
    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        try {
            return super.getCommonSuperClass(type1, type2);
        } catch (TypeNotPresentException notThere) {
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            if (context == null || context == getClassLoader()) {
                throw notThere;
            }
            throughContext = true;
            try {
                return super.getCommonSuperClass(type1, type2);
            } finally {
                throughContext = false;
            }
        }
    }

    @Override
    protected ClassLoader getClassLoader() {
        return throughContext ? Thread.currentThread().getContextClassLoader() : super.getClassLoader();
    }

    /**
     * {@code writer.visitMethod(...)} in mod code, redirected here ({@code visitMethod} is final in ASM 9): the
     * writer is remembered as the owner of the method, for {@link #learnVersion(MethodVisitor)}.
     */
    public static MethodVisitor visitMethod(ClassWriter writer, int access, String name, String descriptor,
                                            String signature, String[] exceptions) {
        MethodVisitor visitor = writer.visitMethod(access, name, descriptor, signature, exceptions);
        if (writer instanceof Asm5ClassWriter own && own.source != null && visitor != null) {
            OWNERS.put(visitor, new WeakReference<>(own));
        }
        return visitor;
    }

    /**
     * Gives the writer that made {@code method} the class version (and header) of its reader, if that is a Java 6+
     * class, whose methods need stack map frames. False when the writer isn't known or its class is older.
     */
    static boolean learnVersion(MethodVisitor method) {
        WeakReference<Asm5ClassWriter> owner = OWNERS.get(method);
        Asm5ClassWriter writer = owner == null ? null : owner.get();
        return writer != null && writer.learnVersion();
    }

    private boolean learnVersion() {
        if (source.readUnsignedShort(6) < Opcodes.V1_6) {
            return false;
        }
        visit(source.readInt(4), source.getAccess(), source.getClassName(), null, source.getSuperName(),
                source.getInterfaces());
        return true;
    }
}

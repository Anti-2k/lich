package dev.lich.remap;

import dev.lich.remap.core.ClassHierarchy;
import dev.lich.remap.core.MappingRemapper;
import dev.lich.remap.core.Mappings;
import net.minecraft.launchwrapper.IClassNameTransformer;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The vanilla profile's obf → SRG remapper, Lich's counterpart of FML's {@code DeobfuscationTransformer}:
 * the game's classes are defined under their SRG names ({@code ave} → {@code net.minecraft.client.Minecraft})
 * with SRG member names, so SRG-named Lich mods, Mixin refmaps and Lich's game fixes work the same with
 * and without Forge. As the first registered {@link IClassNameTransformer} it also renames the classes
 * {@code LaunchClassLoader} defines, and tells Mixin the obfuscated name of a class it wants to read.
 *
 * <p>Only the game's own classes are remapped (those the mappings list, under their obfuscated names):
 * mods and libraries never mention obfuscated names, so their bytes pass through untouched.
 * {@code LaunchClassLoader} creates it by name; {@link #use} must have been called first.
 */
public final class SrgRemapper implements IClassTransformer, IClassNameTransformer {
    private static volatile Mappings pending;

    private final Mappings obfToSrg;
    private final Mappings srgToObf;
    private final MappingRemapper remapper;

    /** The mappings the next instance uses; boot sets them before the vanilla tweaker registers this class. */
    public static void use(Mappings obfToSrg) {
        pending = obfToSrg;
    }

    public SrgRemapper() {
        Mappings mappings = pending;
        if (mappings == null) {
            throw new IllegalStateException("SrgRemapper.use(mappings) wasn't called");
        }
        this.obfToSrg = mappings;
        this.srgToObf = mappings.inverse();
        LaunchClassLoader classLoader = Launch.classLoader;
        this.remapper = new MappingRemapper(mappings, ClassHierarchy.fromClassFiles(name -> gameClassBytes(classLoader, name)));
    }

    /** The raw bytes of a game class by its obfuscated internal name; {@code null} for everything else. */
    private byte[] gameClassBytes(LaunchClassLoader classLoader, String internalName) {
        if (obfToSrg.mapClass(internalName) == null) {
            return null;
        }
        try {
            return classLoader.getClassBytes(internalName.replace('/', '.'));
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read game class " + internalName, e);
        }
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || obfToSrg.mapClass(name.replace('.', '/')) == null) {
            return basicClass;
        }
        ClassReader reader = new ClassReader(basicClass);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(remapper.classVisitor(new PublicAccess(writer)), 0);
        return writer.toByteArray();
    }

    /**
     * Makes the class and its non-private members public. The obfuscated game keeps nearly every class in
     * one package, so its package-private and protected members are visible to, and overridden by, every
     * game class; the obfuscator relied on that and dropped the accessors javac makes for inner classes
     * ({@code GuiConnecting$1} reads {@code GuiScreen.mc} directly). Spread over SRG's packages, those
     * accesses would throw {@code IllegalAccessError} and package-private overrides would stop overriding.
     * Public keeps both as the game ran them. Forge's profile gets the same from Forge's access transformer
     * and its patched classes.
     */
    private static final class PublicAccess extends ClassVisitor {
        PublicAccess(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        private static int makePublic(int access) {
            return (access & Opcodes.ACC_PRIVATE) != 0 ? access : (access & ~Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            super.visit(version, makePublic(access), name, signature, superName, interfaces);
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            super.visitInnerClass(name, outerName, innerName, name.startsWith("net/minecraft/") ? makePublic(access) : access);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            return super.visitField(makePublic(access), name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            return super.visitMethod(makePublic(access), name, descriptor, signature, exceptions);
        }
    }

    @Override
    public String remapClassName(String name) {
        String mapped = obfToSrg.mapClass(name.replace('.', '/'));
        return mapped == null ? name : mapped.replace('/', '.');
    }

    @Override
    public String unmapClassName(String name) {
        String obf = srgToObf.mapClass(name.replace('.', '/'));
        return obf == null ? name : obf.replace('/', '.');
    }
}

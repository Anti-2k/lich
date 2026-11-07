package dev.lich.compat.asm;

import dev.lich.transform.CompatTransformer;
import org.objectweb.asm.Opcodes;

/** The redirect table for ASM 5 behavior that ASM 9 changed, applied to FML and mod code. */
public final class AsmCompat {
    private static final String SHIMS = "dev/lich/compat/asm/";

    private AsmCompat() {
    }

    public static CompatTransformer.Builder register(CompatTransformer.Builder builder) {
        return builder
                // Mixin 0.7 shades ASM 5.2 as org.spongepowered.asm.lib, whose ClassReader rejects anything
                // newer than Java 8, and Mixin reads JDK classes for its class metadata. Mixin and every mod
                // that touches Mixin's ASM types (config plugins) use Lich's ASM 9 instead, unless the
                // relocated copy is an ASM 9 already (UniMixins), which must stay as it is; an older ASM 9
                // there only gets its class file version limit raised.
                .renamePackage("org/spongepowered/asm/lib/", "org/objectweb/asm/", MixinShadedAsm::isTooOld)
                .patch(ShadedAsmClassReader.CLASS, new ShadedAsmClassReader())
                .patch(ShadedAsmOpcodes.CLASS, new ShadedAsmOpcodes())
                .patch(ForgeEventWrappers.CLASS, new ForgeEventWrappers())
                .patch(ForgeEventWrappers.CLASS_1710, new ForgeEventWrappers())
                .redirect("org/objectweb/asm/Type", "getType", "(Ljava/lang/String;)Lorg/objectweb/asm/Type;",
                        SHIMS + "Asm5Types")
                .redirect("org/objectweb/asm/Type", "getReturnType", "(Ljava/lang/String;)Lorg/objectweb/asm/Type;",
                        SHIMS + "Asm5Types")
                .redirect("org/objectweb/asm/Type", "getReturnType", "()Lorg/objectweb/asm/Type;", SHIMS + "Asm5Types")
                .redirect("org/objectweb/asm/Type", "getSize", "()I", SHIMS + "Asm5Types")
                .redirect("org/objectweb/asm/Type", "getOpcode", "(I)I", SHIMS + "Asm5Types")
                // Compressed frames in a Java 5 class: ASM 5 wrote them (the JVM ignores them there), ASM 9 throws.
                .redirect("org/objectweb/asm/MethodVisitor", "visitFrame", "(II[Ljava/lang/Object;I[Ljava/lang/Object;)V",
                        SHIMS + "Asm5Frames")
                // ... unless the writer only lacks its class header yet (a coremod adding a method before accept).
                .replaceInstantiations("org/objectweb/asm/ClassWriter", SHIMS + "Asm5ClassWriter")
                .redirect("org/objectweb/asm/ClassWriter", "visitMethod",
                        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)Lorg/objectweb/asm/MethodVisitor;",
                        SHIMS + "Asm5ClassWriter")
                // Visitors written for ASM 5 (FML's mod discovery, its deobfuscation, mods' transformers, Mixin
                // 0.7) throw on Java 11+ class files, whose nest, record and permitted-subclass attributes need
                // a newer API level. For ASM 5-level code, a higher level only adds those callbacks, whose
                // defaults pass them on; output for Java 8 classes stays the same. ASM 4-level code is left
                // alone: ASM routes its method calls differently.
                .replaceIntConstant(Opcodes.ASM5, Opcodes.ASM9, "org/objectweb/asm/",
                        "raised the ASM API level from 5 to 9")
                // ASM 6+ throws for lambdas reaching an ASM 4-level visitor (see Asm5MethodVisitor).
                .rebase("org/objectweb/asm/MethodVisitor", SHIMS + "Asm5MethodVisitor")
                // ASM 6+ throws when a subclass calls one of these constructors without an API version.
                .rebase("org/objectweb/asm/tree/analysis/BasicInterpreter", SHIMS + "Asm5BasicInterpreter")
                .rebase("org/objectweb/asm/tree/analysis/BasicVerifier", SHIMS + "Asm5BasicVerifier")
                .rebase("org/objectweb/asm/tree/analysis/SimpleVerifier", SHIMS + "Asm5SimpleVerifier")
                .rebase("org/objectweb/asm/tree/analysis/SourceInterpreter", SHIMS + "Asm5SourceInterpreter")
                .rebase("org/objectweb/asm/util/CheckMethodAdapter", SHIMS + "Asm5CheckMethodAdapter")
                .rebase("org/objectweb/asm/commons/TryCatchBlockSorter", SHIMS + "Asm5TryCatchBlockSorter")
                // Mods hash Textifier's text to recognize code (Logistics Pipes); ASM 6+ prints some instructions
                // differently.
                .rebase("org/objectweb/asm/util/Textifier", SHIMS + "Asm5Textifier")
                .replaceInstantiations("org/objectweb/asm/util/Textifier", SHIMS + "Asm5Textifier");
    }

    /**
     * For Forge 1.7.10 only, whose FML had LaunchClassLoader define ASM: plain {@code ClassWriter}s look
     * classes up through LaunchClassLoader, as they did there. FML 1.8.9 already kept ASM in the system
     * class loader on Java 8, so its mods expect what they get.
     */
    public static CompatTransformer.Builder registerFml1710(CompatTransformer.Builder builder) {
        return builder.replaceInstantiations("org/objectweb/asm/ClassWriter", SHIMS + "LaunchClassLoaderClassWriter");
    }
}

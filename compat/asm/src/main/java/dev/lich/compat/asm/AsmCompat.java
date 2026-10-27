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
                // relocated copy is a current ASM already (UniMixins), which must stay as it is.
                .renamePackage("org/spongepowered/asm/lib/", "org/objectweb/asm/", MixinShadedAsm::isTooOld)
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
                // Visitors written for ASM 5 (FML's mod discovery, its deobfuscation, mods' transformers, Mixin
                // 0.7) throw on Java 11+ class files, whose nest, record and permitted-subclass attributes need
                // a newer API level. For ASM 5-level code, a higher level only adds those callbacks, whose
                // defaults pass them on; output for Java 8 classes stays the same. ASM 4-level code is left
                // alone: ASM routes its method calls differently.
                .replaceIntConstant(Opcodes.ASM5, Opcodes.ASM9, "org/objectweb/asm/",
                        "raised the ASM API level from 5 to 9")
                // ASM 6+ throws when a subclass calls one of these constructors without an API version.
                .rebase("org/objectweb/asm/tree/analysis/BasicInterpreter", SHIMS + "Asm5BasicInterpreter")
                .rebase("org/objectweb/asm/tree/analysis/BasicVerifier", SHIMS + "Asm5BasicVerifier")
                .rebase("org/objectweb/asm/tree/analysis/SimpleVerifier", SHIMS + "Asm5SimpleVerifier")
                .rebase("org/objectweb/asm/tree/analysis/SourceInterpreter", SHIMS + "Asm5SourceInterpreter")
                .rebase("org/objectweb/asm/util/CheckMethodAdapter", SHIMS + "Asm5CheckMethodAdapter")
                .rebase("org/objectweb/asm/commons/TryCatchBlockSorter", SHIMS + "Asm5TryCatchBlockSorter");
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

package dev.lich.compat.asm;

import dev.lich.transform.CompatTransformer;

/** The redirect table for ASM 5 behavior that ASM 9 changed, applied to FML and mod code. */
public final class AsmCompat {
    private AsmCompat() {
    }

    public static CompatTransformer.Builder register(CompatTransformer.Builder builder) {
        return builder.redirect("org/objectweb/asm/Type", "getType", "(Ljava/lang/String;)Lorg/objectweb/asm/Type;",
                "dev/lich/compat/asm/Asm5Types");
    }
}

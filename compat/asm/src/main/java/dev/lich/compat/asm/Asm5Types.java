package dev.lich.compat.asm;

import org.objectweb.asm.Type;

/**
 * ASM 5's lenient {@code Type.getType(String)} for code written against ASM 5. ASM 9 throws for a
 * string that isn't a type descriptor; ASM 5 returned a method-sort type over the whole string, whose
 * {@code getInternalName()} gave that string back. FML 1.8.9's {@code EventSubscriptionTransformer}
 * depends on that: it passes {@code ClassNode.superName}, an internal name, and reads the internal name
 * back.
 */
public final class Asm5Types {
    private Asm5Types() {
    }

    public static Type getType(String descriptor) {
        try {
            return Type.getType(descriptor);
        } catch (IllegalArgumentException | StringIndexOutOfBoundsException notADescriptor) {
            // What ASM 5's default branch built; getMethodType doesn't validate its argument.
            return Type.getMethodType(descriptor);
        }
    }
}

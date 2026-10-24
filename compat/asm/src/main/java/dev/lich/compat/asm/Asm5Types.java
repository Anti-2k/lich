package dev.lich.compat.asm;

import org.objectweb.asm.Type;

/**
 * ASM 5's lenient {@code Type} parsing for code written against ASM 5:
 * <ul>
 *   <li>{@code getType(String)}: ASM 9 throws for a string that isn't a type descriptor; ASM 5 returned a
 *       method-sort type over the whole string, whose {@code getInternalName()} gave that string back. FML
 *       1.8.9's {@code EventSubscriptionTransformer} depends on that: it passes {@code ClassNode.superName},
 *       an internal name, and reads the internal name back.</li>
 *   <li>{@code getReturnType}: for a descriptor without {@code ')'}, ASM 5 parsed from
 *       {@code indexOf(')') + 1}, the start, and so returned the descriptor's own type; ASM 9 runs off the
 *       end of the string. ForgeMultipart's trait compiler passes field descriptors.</li>
 *   <li>{@code getSize} and {@code getOpcode} on a method-sort type, such as {@code getType} gives for an
 *       internal name: ASM 5 treated it as a reference (size 1, the {@code A} opcodes); ASM 9 throws
 *       {@code AssertionError}. ForgeMultipart's stack analyser does that for every object on the stack.</li>
 * </ul>
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

    public static Type getReturnType(String methodDescriptor) {
        return methodDescriptor.indexOf(')') < 0 ? getType(methodDescriptor) : Type.getReturnType(methodDescriptor);
    }

    /** The instance method, redirected with its receiver as the argument. */
    public static Type getReturnType(Type methodType) {
        return getReturnType(methodType.getDescriptor());
    }

    public static int getSize(Type type) {
        return type.getSort() == Type.METHOD ? 1 : type.getSize();
    }

    public static int getOpcode(Type type, int opcode) {
        return type.getSort() == Type.METHOD ? Type.getObjectType("java/lang/Object").getOpcode(opcode) : type.getOpcode(opcode);
    }
}

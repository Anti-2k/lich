package dev.lich.compat.jdk;

import dev.lich.transform.ClassPatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.UUID;

/**
 * A string constant passed straight to {@code UUID.fromString} that Java 9+ rejects, or reads as another value than
 * Java 8 did ({@link Uuids}), is replaced by the well-formed string of Java 8's value. The call stays the JDK's, and a
 * class without such a constant isn't touched: patchers such as XU2-Patcher checksum mods' classes after the compat
 * pass and refuse changed ones (The 1.12.2 Pack), which redirecting every call would trip over.
 */
public final class UuidConstants implements ClassPatch {
    @Override
    public boolean apply(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("java/util/UUID") && call.name.equals("fromString")
                        && call.desc.equals("(Ljava/lang/String;)Ljava/util/UUID;")
                        && previous(call) instanceof LdcInsnNode ldc && ldc.cst instanceof String text) {
                    String fixed = asJava8(text);
                    if (fixed != null) {
                        ldc.cst = fixed;
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    /** The well-formed string of Java 8's value, if it differs from what this JDK makes of {@code text}; else null. */
    static String asJava8(String text) {
        UUID java8;
        try {
            java8 = Uuids.fromString(text);
        } catch (IllegalArgumentException invalid) {
            return null; // Invalid on Java 8 too: the call throws as it did.
        }
        try {
            if (UUID.fromString(text).equals(java8)) {
                return null;
            }
        } catch (IllegalArgumentException rejected) {
            // Java 9+ refuses it.
        }
        return java8.toString();
    }

    private static AbstractInsnNode previous(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        while (previous != null && previous.getOpcode() < 0) { // Labels, line numbers, frames.
            previous = previous.getPrevious();
        }
        return previous;
    }
}

package dev.lich.compat.asm;

import dev.lich.transform.ClassPatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Lets the ASM 9 that UniMixins relocates to {@code org.spongepowered.asm.lib} read Java 25's class files. Older
 * UniMixins (0.1.15, in Blightfall) ship an ASM 9 whose {@code ClassReader} stops at Java 21, and Mixin reads JDK
 * classes for its class metadata. That copy has to stay (its classes bridge 0.7-era plugins, which renaming it to
 * Lich's ASM would cut: CoreTweaks' plugin fails), so only the reader's version check moves up to Java 25. Nothing in
 * Java 22-25 class files needs more than ASM 9 reads already. Mixin 0.7's ASM 5.2 is renamed to Lich's instead
 * ({@link MixinShadedAsm}), so a limit below Java 17's is left alone.
 */
final class ShadedAsmClassReader implements ClassPatch {
    static final String CLASS = "org.spongepowered.asm.lib.ClassReader";

    @Override
    public boolean apply(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>") || !method.desc.equals("([BIZ)V")) {
                continue;
            }
            // readShort(offset + 6) > V<n>: the class file's major version against the newest this ASM knows.
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && call.name.equals("readShort")
                        && call.getNext() instanceof IntInsnNode limit && limit.getOpcode() == Opcodes.BIPUSH
                        && limit.operand >= Opcodes.V17 && limit.operand < Opcodes.V25
                        && limit.getNext() != null && limit.getNext().getOpcode() == Opcodes.IF_ICMPLE) {
                    limit.operand = Opcodes.V25;
                    changed = true;
                }
            }
        }
        return changed;
    }
}

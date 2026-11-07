package dev.lich.compat.jdk;

import dev.lich.transform.ClassPatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * ZenUtils takes any Java newer than 8 to mean Cleanroom ({@code Reference.IS_CLEANROOM = !IS_JAVA_1_8}), and then
 * looks for Cleanroom's class loader, which isn't there: Lich runs real Forge. The flag is set to what's true here.
 */
public final class ZenUtilsPlatform implements ClassPatch {
    public static final String CLASS = "youyihj.zenutils.Reference";

    @Override
    public boolean apply(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<clinit>")) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
                        && field.name.equals("IS_CLEANROOM")) {
                    // Whatever was computed is dropped; no branch added, so the frames stay valid.
                    method.instructions.insertBefore(insn, new InsnNode(Opcodes.POP));
                    method.instructions.insertBefore(insn, new InsnNode(Opcodes.ICONST_0));
                    changed = true;
                }
            }
        }
        return changed;
    }
}

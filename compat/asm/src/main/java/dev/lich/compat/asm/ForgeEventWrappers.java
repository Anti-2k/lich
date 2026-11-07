package dev.lich.compat.asm;

import dev.lich.transform.ClassFiles;
import dev.lich.transform.ClassPatch;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Method;

/**
 * Lets Forge's event bus call a {@code @SubscribeEvent} static method declared on an interface (Electroblob's Wizardry).
 * {@code ASMEventHandler.createWrapper} generates a Java 6 class that calls it with a plain method reference. Java 8
 * let that through; Java 9+ throws {@code IncompatibleClassChangeError} (it must be an interface method reference),
 * and a class older than Java 8 can't hold one. For these handlers only, the wrapper becomes a Java 8 class with the
 * right reference. The wrappers have no branches, so they need no stack map frames at the new version.
 */
public final class ForgeEventWrappers implements ClassPatch {
    public static final String CLASS = "net.minecraftforge.fml.common.eventhandler.ASMEventHandler";
    public static final String CLASS_1710 = "cpw.mods.fml.common.eventhandler.ASMEventHandler";
    private static final String SELF = "dev/lich/compat/asm/ForgeEventWrappers";
    private static final String METHOD_VISITOR = "org/objectweb/asm/MethodVisitor";

    @Override
    public boolean apply(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("createWrapper") || !method.desc.equals("(Ljava/lang/reflect/Method;)Ljava/lang/Class;")) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof IntInsnNode constant && constant.getOpcode() == Opcodes.BIPUSH
                        && constant.operand == Opcodes.V1_6) {
                    method.instructions.insertBefore(insn, new VarInsnNode(Opcodes.ALOAD, 1)); // the handler method
                    AbstractInsnNode version = new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "classVersion",
                            "(Ljava/lang/reflect/Method;)I", false);
                    method.instructions.set(insn, version);
                    insn = version;
                    changed = true;
                } else if (insn instanceof MethodInsnNode call && call.owner.equals(METHOD_VISITOR)
                        && call.name.equals("visitMethodInsn")) {
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.owner = SELF;
                    call.desc = "(L" + METHOD_VISITOR + ";" + call.desc.substring(1);
                    call.itf = false;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** The wrapper class's version for this handler. */
    public static int classVersion(Method handler) {
        return handler.getDeclaringClass().isInterface() ? Opcodes.V1_8 : Opcodes.V1_6;
    }

    /** The call {@code createWrapper} makes (the same on 1.7.10, 1.8.9 and 1.12.2), with its receiver as an argument. */
    public static void visitMethodInsn(MethodVisitor visitor, int opcode, String owner, String name, String descriptor,
                                       boolean isInterface) {
        visitor.visitMethodInsn(opcode, owner, name, descriptor,
                isInterface || opcode == Opcodes.INVOKESTATIC && ClassFiles.isInterface(owner));
    }
}

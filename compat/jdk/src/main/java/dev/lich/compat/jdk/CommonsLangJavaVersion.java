package dev.lich.compat.jdk;

import dev.lich.transform.ClassPatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Makes commons-lang3's {@code JavaVersion.get} (3.1 and 3.3.2, the copies 1.7.10 and 1.8.9 ship) return
 * its newest constant, {@code JAVA_1_8}, for Java 9+ version strings instead of {@code null}, as
 * commons-lang3 3.5+ does with {@code JAVA_RECENT}. With {@code null}, {@code SystemUtils}'s
 * {@code JAVA_SPECIFICATION_VERSION_AS_ENUM} is null on Java 25 and every
 * {@code SystemUtils.isJavaVersionAtLeast} call throws. FML 1.8.9's {@code BlamingTransformer} makes that
 * call for every Java 8 class file, so without the patch no mod compiled for Java 8 loads.
 */
public final class CommonsLangJavaVersion implements ClassPatch {
    public static final String CLASS = "org.apache.commons.lang3.JavaVersion";

    @Override
    public boolean apply(ClassNode node) {
        FieldNode newest = null;
        for (FieldNode field : node.fields) {
            if ((field.access & Opcodes.ACC_ENUM) != 0) {
                newest = field;
            }
        }
        MethodNode get = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("get") && method.desc.equals("(Ljava/lang/String;)L" + node.name + ";")) {
                get = method;
            }
        }
        if (newest == null || get == null) {
            return false;
        }
        boolean changed = false;
        for (AbstractInsnNode insn = get.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            AbstractInsnNode next = insn.getNext();
            if (insn.getOpcode() != Opcodes.ACONST_NULL || next == null || next.getOpcode() != Opcodes.ARETURN) {
                continue;
            }
            // return null → return (JavaVersion) JavaVersions.newestIfModern(name, <newest>). No branch, so
            // the method's stack map frames stay valid (the compat pass doesn't recompute them).
            InsnList modern = new InsnList();
            modern.add(new VarInsnNode(Opcodes.ALOAD, 0));
            modern.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, newest.name, newest.desc));
            modern.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/lich/compat/jdk/JavaVersions", "newestIfModern",
                    "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false));
            modern.add(new TypeInsnNode(Opcodes.CHECKCAST, node.name));
            get.instructions.insertBefore(insn, modern);
            get.instructions.remove(insn);
            insn = next;
            changed = true;
        }
        return changed;
    }
}

package dev.lich.compat.asm;

import dev.lich.transform.ClassPatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * The other half of {@link ShadedAsmClassReader}: Mixin works out the newest class file version its ASM reads from
 * the {@code V<n>} constants of {@code Opcodes} (by reflection, up to the first other constant), and refuses to read
 * JDK classes past it ("Class file major version 69 is not supported by active ASM"). A relocated ASM 9 that stops
 * short of Java 25 gets the missing constants, with ASM's own values, after its newest one.
 */
final class ShadedAsmOpcodes implements ClassPatch {
    static final String CLASS = "org.spongepowered.asm.lib.Opcodes";

    @Override
    public boolean apply(ClassNode node) {
        if (field(node, "V17") == null) {
            // Not an ASM 9: Mixin 0.7's ASM 5.2, which is renamed to Lich's ASM instead.
            return false;
        }
        boolean changed = false;
        for (int version = 18; version <= 25; version++) {
            if (field(node, "V" + version) == null) {
                // Right after the previous version: Mixin reads the fields in declaration order and stops at the
                // first that isn't a version (ACC_PUBLIC), so ones added at the end would never be seen.
                FieldNode previous = field(node, "V" + (version - 1));
                // Java n's class file major version is 44 + n.
                node.fields.add(node.fields.indexOf(previous) + 1, new FieldNode(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "V" + version, "I", null, 44 + version));
                changed = true;
            }
        }
        return changed;
    }

    private static FieldNode field(ClassNode node, String name) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name)) {
                return field;
            }
        }
        return null;
    }
}

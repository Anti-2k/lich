package dev.lich.compat.v1_12_2.forge;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Forge1122Test {
    private static final String LIST = "(Ljava/io/File;)Ljava/util/List;";

    private static ClassNode classWith(String name, String descriptor, int returns) {
        ClassNode node = new ClassNode();
        node.name = "net/minecraftforge/fml/relauncher/libraries/LibraryManager";
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(returns));
        node.methods.add(method);
        return node;
    }

    @Test
    void passesWhatTheListingMethodReturnsThroughLichSources() {
        ClassNode node = classWith("gatherLegacyCanidates", LIST, Opcodes.ARETURN);
        assertTrue(new Forge1122.FilterResult("gatherLegacyCanidates", LIST, "(Ljava/util/List;)Ljava/util/List;").apply(node));
        MethodNode method = node.methods.getFirst();
        assertEquals(3, method.instructions.size());
        MethodInsnNode call = (MethodInsnNode) method.instructions.get(1);
        assertEquals("dev/lich/launchwrapper/LichSources", call.owner);
        assertEquals("withoutLichMods", call.name);
        assertEquals("(Ljava/util/List;)Ljava/util/List;", call.desc);
        assertSame(method.instructions.getLast(), call.getNext(), "the filter runs right before the return");
    }

    @Test
    void leavesOtherMethodsAndOtherSignaturesAlone() {
        ClassNode other = classWith("somethingElse", LIST, Opcodes.ARETURN);
        assertFalse(new Forge1122.FilterResult("gatherLegacyCanidates", LIST, "(Ljava/util/List;)Ljava/util/List;").apply(other));
        ClassNode sameNameOtherSignature = classWith("gatherLegacyCanidates", "()Ljava/util/List;", Opcodes.ARETURN);
        assertFalse(new Forge1122.FilterResult("gatherLegacyCanidates", LIST, "(Ljava/util/List;)Ljava/util/List;")
                .apply(sameNameOtherSignature));
    }
}

package dev.lich.compat.asm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Asm5TypesTest {
    @Test
    void descriptorsBehaveAsInAsm9() {
        assertEquals(Type.getType("Ljava/lang/String;"), Asm5Types.getType("Ljava/lang/String;"));
        assertEquals(Type.INT_TYPE, Asm5Types.getType("I"));
        assertEquals(Type.getMethodType("()V"), Asm5Types.getType("()V"));
    }

    @Test
    void internalNamesComeBackAsAsm5DidThem() {
        String superName = "net/minecraftforge/fml/common/eventhandler/Event";
        assertThrows(IllegalArgumentException.class, () -> Type.getType(superName));
        Type type = Asm5Types.getType(superName);
        assertEquals(Type.METHOD, type.getSort());
        assertEquals(superName, type.getInternalName());
    }
}

package dev.lich.compat.asm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
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

    @Test
    void returnTypesOfMethodDescriptorsAreAsInAsm9() {
        assertEquals(Type.INT_TYPE, Asm5Types.getReturnType("(Ljava/lang/String;)I"));
        assertEquals(Type.VOID_TYPE, Asm5Types.getReturnType(Type.getMethodType("()V")));
    }

    @Test
    void theReturnTypeOfAFieldDescriptorIsItsOwnTypeAsInAsm5() {
        String field = "Lcodechicken/multipart/TMultiPart;";
        assertThrows(StringIndexOutOfBoundsException.class, () -> Type.getReturnType(field));
        assertEquals(Type.getType(field), Asm5Types.getReturnType(field));
        assertEquals(Type.INT_TYPE, Asm5Types.getReturnType("I"));
    }

    @Test
    void methodSortTypesFromInternalNamesActAsReferences() {
        Type part = Asm5Types.getType("codechicken/multipart/TMultiPart");
        assertThrows(AssertionError.class, part::getSize);
        assertEquals(1, Asm5Types.getSize(part));
        assertEquals(Opcodes.ALOAD, Asm5Types.getOpcode(part, Opcodes.ILOAD));
        assertEquals(Opcodes.ARETURN, Asm5Types.getOpcode(part, Opcodes.IRETURN));
        assertEquals(2, Asm5Types.getSize(Type.LONG_TYPE));
        assertEquals(Opcodes.LLOAD, Asm5Types.getOpcode(Type.LONG_TYPE, Opcodes.ILOAD));
    }
}

package dev.lich.compat.jdk;

import dev.lich.transform.CompatTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Mods' UUID constants give what Java 8 gave; the expected values are Java 8's own (JDK 8u482). */
class UuidsTest {
    private final CompatTransformer compat = JdkCompat.register(CompatTransformer.builder()).build();

    @Test
    void anOverlongStringParsesAsOnJava8() {
        // Solar Flux Reborn's efficiency upgrade, which Java 25 rejects ("UUID string too large").
        assertThrows(IllegalArgumentException.class, () -> UUID.fromString("28575920-a562b-e364d-388af-837a6b8f5a8c"));
        assertEquals(new UUID(0x2857592a562f364dL, 0x88af837a6b8f5a8cL),
                Uuids.fromString("28575920-a562b-e364d-388af-837a6b8f5a8c"));
        // Short enough for Java 25, which masks the overlong group instead of letting it spill over.
        assertEquals(new UUID(0x123450000L, 0), Uuids.fromString("0-12345-0-0-0"));
    }

    @Test
    void wellFormedStringsParseAsTheJdkParsesThem() {
        assertEquals(UUID.fromString("662A6B8D-DA3E-4C1C-8813-96EA6097278D"), Uuids.fromString("662A6B8D-DA3E-4C1C-8813-96EA6097278D"));
        for (int i = 0; i < 100; i++) {
            UUID random = UUID.randomUUID();
            assertEquals(random, Uuids.fromString(random.toString()));
        }
        assertThrows(IllegalArgumentException.class, () -> Uuids.fromString("not-a-uuid"));
    }

    /** {@code static UUID id()} returning {@code UUID.fromString(constant)}, or of its argument if {@code constant} is null. */
    private static byte[] parsing(String internalName, String constant) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        String descriptor = constant == null ? "(Ljava/lang/String;)Ljava/util/UUID;" : "()Ljava/util/UUID;";
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "id", descriptor, null, null);
        mv.visitCode();
        if (constant == null) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
        } else {
            mv.visitLdcInsn(constant);
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/UUID", "fromString", "(Ljava/lang/String;)Ljava/util/UUID;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private Object id(String binaryName, byte[] bytes) throws Exception {
        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(binaryName, bytes, 0, bytes.length);
            }
        }.define();
        return type.getMethod("id").invoke(null);
    }

    @Test
    void aBadConstantBecomesJava8sValueAndNothingElseIsTouched() throws Exception {
        byte[] solarFlux = compat.transform("tk.zeitheron.solarflux.items.Upgrade",
                parsing("tk/zeitheron/solarflux/items/Upgrade", "28575920-a562b-e364d-388af-837a6b8f5a8c"));
        assertEquals(new UUID(0x2857592a562f364dL, 0x88af837a6b8f5a8cL), id("tk.zeitheron.solarflux.items.Upgrade", solarFlux));
        ClassNode node = new ClassNode();
        new ClassReader(solarFlux).accept(node, 0);
        assertEquals("java/util/UUID", ((MethodInsnNode) node.methods.get(0).instructions.get(1)).owner, "the JDK's call stays");

        // Classes that XU2-Patcher-style patchers checksum after this pass must keep their bytes.
        byte[] wellFormed = parsing("com/rwtema/extrautils2/items/ItemLawSword", "662A6B8D-DA3E-4C1C-8813-96EA6097278D");
        assertSame(wellFormed, compat.transform("com.rwtema.extrautils2.items.ItemLawSword", wellFormed));
        byte[] computed = parsing("test/Profiles", null);
        assertSame(computed, compat.transform("test.Profiles", computed));
        byte[] invalidOnJava8Too = parsing("test/Broken", "not-a-uuid");
        assertSame(invalidOnJava8Too, compat.transform("test.Broken", invalidOnJava8Too));
    }
}

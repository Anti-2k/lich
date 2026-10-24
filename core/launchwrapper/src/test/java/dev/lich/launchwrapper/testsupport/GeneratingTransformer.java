package dev.lich.launchwrapper.testsupport;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Creates fixture.Generated, which has no class file, with {@code static String hello()} returning "generated". */
public class GeneratingTransformer implements IClassTransformer {
    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!"fixture.Generated".equals(name) || basicClass != null) {
            return basicClass;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/Generated", null, "java/lang/Object", null);
        MethodVisitor hello = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "hello", "()Ljava/lang/String;", null, null);
        hello.visitCode();
        hello.visitLdcInsn("generated");
        hello.visitInsn(Opcodes.ARETURN);
        hello.visitMaxs(0, 0);
        hello.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}

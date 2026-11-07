package dev.lich.compat.asm;

import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * What a mod's {@code MethodVisitor} extends under Lich. ASM 5 passed lambdas and method handles through a visitor of
 * any API level; ASM 9 throws for an ASM 4-level one, so a coremod that says {@code ASM4} (AdvSolarPatch) couldn't read
 * a class with a lambda in it any more. Those two calls forward here whatever the level, as they did; a visitor that
 * overrides them, or one at a newer level, sees no difference.
 */
public abstract class Asm5MethodVisitor extends MethodVisitor {
    protected Asm5MethodVisitor(int api) {
        super(api);
    }

    protected Asm5MethodVisitor(int api, MethodVisitor methodVisitor) {
        super(api, methodVisitor);
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                                       Object... bootstrapMethodArguments) {
        if (mv != null) {
            mv.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
        }
    }

    @Override
    public void visitLdcInsn(Object value) {
        boolean newerThanAsm4 = value instanceof Handle || value instanceof Type type && type.getSort() == Type.METHOD;
        if (!newerThanAsm4) {
            super.visitLdcInsn(value);
        } else if (mv != null) {
            mv.visitLdcInsn(value);
        }
    }
}

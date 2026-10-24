package dev.lich.compat.asm;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckMethodAdapter;

import java.util.HashMap;
import java.util.Map;

/** {@link CheckMethodAdapter} with ASM 5's constructors; see {@link Asm5BasicInterpreter}. */
public class Asm5CheckMethodAdapter extends CheckMethodAdapter {
    public Asm5CheckMethodAdapter(MethodVisitor methodVisitor) {
        this(methodVisitor, new HashMap<>());
    }

    public Asm5CheckMethodAdapter(MethodVisitor methodVisitor, Map<Label, Integer> labelInsnIndices) {
        super(Opcodes.ASM9, methodVisitor, labelInsnIndices);
    }

    protected Asm5CheckMethodAdapter(int api, MethodVisitor methodVisitor, Map<Label, Integer> labelInsnIndices) {
        super(api, methodVisitor, labelInsnIndices);
    }

    public Asm5CheckMethodAdapter(int access, String name, String descriptor, MethodVisitor methodVisitor,
                                  Map<Label, Integer> labelInsnIndices) {
        super(Opcodes.ASM9, access, name, descriptor, methodVisitor, labelInsnIndices);
    }

    protected Asm5CheckMethodAdapter(int api, int access, String name, String descriptor, MethodVisitor methodVisitor,
                                     Map<Label, Integer> labelInsnIndices) {
        super(api, access, name, descriptor, methodVisitor, labelInsnIndices);
    }
}
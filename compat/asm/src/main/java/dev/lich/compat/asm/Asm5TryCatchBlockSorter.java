package dev.lich.compat.asm;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.TryCatchBlockSorter;

/** {@link TryCatchBlockSorter} with ASM 5's constructors; see {@link Asm5BasicInterpreter}. */
public class Asm5TryCatchBlockSorter extends TryCatchBlockSorter {
    public Asm5TryCatchBlockSorter(MethodVisitor methodVisitor, int access, String name, String descriptor,
                                   String signature, String[] exceptions) {
        super(Opcodes.ASM9, methodVisitor, access, name, descriptor, signature, exceptions);
    }

    protected Asm5TryCatchBlockSorter(int api, MethodVisitor methodVisitor, int access, String name, String descriptor,
                                      String signature, String[] exceptions) {
        super(api, methodVisitor, access, name, descriptor, signature, exceptions);
    }
}
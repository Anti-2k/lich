package dev.lich.compat.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

import java.util.List;

/**
 * {@link SimpleVerifier} with ASM 5's constructors; see {@link Asm5BasicInterpreter}. Mixin 0.7's
 * {@code MixinVerifier} is one such subclass.
 */
public class Asm5SimpleVerifier extends SimpleVerifier {
    public Asm5SimpleVerifier() {
        this(null, null, false);
    }

    public Asm5SimpleVerifier(Type currentClass, Type currentSuperClass, boolean isInterface) {
        this(currentClass, currentSuperClass, null, isInterface);
    }

    public Asm5SimpleVerifier(Type currentClass, Type currentSuperClass, List<Type> currentClassInterfaces,
                              boolean isInterface) {
        super(Opcodes.ASM9, currentClass, currentSuperClass, currentClassInterfaces, isInterface);
    }

    protected Asm5SimpleVerifier(int api, Type currentClass, Type currentSuperClass, List<Type> currentClassInterfaces,
                                 boolean isInterface) {
        super(api, currentClass, currentSuperClass, currentClassInterfaces, isInterface);
    }
}
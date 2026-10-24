package dev.lich.transform;

import org.objectweb.asm.tree.ClassNode;

/**
 * A change to one named class, made on its tree before any other transformer sees it. Patches must
 * leave the stack map frames valid: the compat pass recomputes max stack/locals, never frames, because
 * computing frames loads classes and these patches run while classes are being loaded.
 */
@FunctionalInterface
public interface ClassPatch {
    /** Changes {@code node} and returns whether anything changed. */
    boolean apply(ClassNode node);
}

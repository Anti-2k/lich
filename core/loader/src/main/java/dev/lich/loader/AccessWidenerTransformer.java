package dev.lich.loader;

import dev.lich.remap.core.AccessWidener;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * Applies the Lich mods' access wideners to the game's classes, by SRG name, as one of Lich's last
 * transformers (after FML's or Lich's deobfuscation, so the names match in both profiles).
 */
final class AccessWidenerTransformer implements IClassTransformer {
    private final AccessWidener widener;

    AccessWidenerTransformer(AccessWidener widener) {
        this.widener = widener;
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !widener.targets(transformedName.replace('.', '/'))) {
            return basicClass;
        }
        ClassReader reader = new ClassReader(basicClass);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(widener.visitor(writer), 0);
        return writer.toByteArray();
    }
}

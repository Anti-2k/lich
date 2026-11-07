package dev.lich.compat.v1_12_2.forge;

import dev.lich.transform.ClassPatch;
import dev.lich.transform.CompatTransformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Lich's rules for FML 1.12.2's own classes. */
public final class Forge1122 {
    private static final String LICH_SOURCES = "dev/lich/launchwrapper/LichSources";
    private static final String LIBRARY_MANAGER = "net.minecraftforge.fml.relauncher.libraries.LibraryManager";
    private static final String CORE_MOD_MANAGER = "net.minecraftforge.fml.relauncher.CoreModManager";

    private Forge1122() {
    }

    /**
     * Keeps Lich mods out of FML's coremod and mod discovery. FML 1.12.2 no longer sorts its listings of the mods
     * folder through {@code FileListHelper} as 1.8.9 did: mod discovery asks
     * {@code LibraryManager.gatherLegacyCanidates} (a {@code List<File>} of {@code mods/} and {@code mods/1.12.2/}),
     * coremod discovery lists the same folders through the private {@code CoreModManager.listFiles}. Both results go
     * through {@code LichSources.withoutLichMods}. Only launches with Lich mods need it.
     */
    public static CompatTransformer.Builder hideLichMods(CompatTransformer.Builder builder) {
        return builder
                .patch(LIBRARY_MANAGER, new FilterResult("gatherLegacyCanidates", "(Ljava/io/File;)Ljava/util/List;",
                        "(Ljava/util/List;)Ljava/util/List;"))
                .patch(CORE_MOD_MANAGER, new FilterResult("listFiles", "(Ljava/io/FileFilter;[Ljava/io/File;)[Ljava/io/File;",
                        "([Ljava/io/File;)[Ljava/io/File;"));
    }

    /**
     * Passes what a method returns through {@code LichSources.withoutLichMods}, which takes and returns that type.
     * Adds no branches, so the frames stay valid.
     */
    static final class FilterResult implements ClassPatch {
        private final String method;
        private final String descriptor;
        private final String filterDescriptor;

        FilterResult(String method, String descriptor, String filterDescriptor) {
            this.method = method;
            this.descriptor = descriptor;
            this.filterDescriptor = filterDescriptor;
        }

        @Override
        public boolean apply(ClassNode node) {
            boolean changed = false;
            for (MethodNode candidate : node.methods) {
                if (!candidate.name.equals(method) || !candidate.desc.equals(descriptor)) {
                    continue;
                }
                for (AbstractInsnNode insn : candidate.instructions.toArray()) {
                    if (insn.getOpcode() == Opcodes.ARETURN) {
                        candidate.instructions.insertBefore(insn,
                                new MethodInsnNode(Opcodes.INVOKESTATIC, LICH_SOURCES, "withoutLichMods", filterDescriptor, false));
                        changed = true;
                    }
                }
            }
            return changed;
        }
    }
}

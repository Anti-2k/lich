package dev.lich.compat.shared;

import dev.lich.transform.ClassPatch;
import dev.lich.transform.CompatTransformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Puts {@link Branding}'s line where the game says what it runs on. Under Forge: FML's branding list, which the
 * main menu draws bottom left ("Minecraft 1.8.9", "MCP 9.19", "Powered by Forge 11.15.1.2318", Lich's line, the mod
 * count), 1.7.10 shows on its F3 screen, and crash reports include. Lich's line goes in through the list FML asks its
 * client handler for ({@code getAdditionalBrandingInformation}), the slot FML keeps for this. In the vanilla
 * profile, the main menu's "Minecraft 1.8.9" line gets it appended. Both patches add one static call, no branches.
 */
public final class BrandingPatches {
    private static final String BRANDING = "dev/lich/compat/shared/Branding";
    static final String MAIN_MENU = "net.minecraft.client.gui.GuiMainMenu";

    private BrandingPatches() {
    }

    /**
     * Registers the patch for this launch: FML's {@code FMLCommonHandler} (its package differs between 1.7.10
     * and 1.8.9) when Forge runs, the main menu otherwise. Forge's main menu draws FML's list instead of the
     * vanilla line, so only one of them ever shows.
     */
    public static CompatTransformer.Builder register(CompatTransformer.Builder builder, String minecraft, boolean forge) {
        if (!forge) {
            return builder.patch(MAIN_MENU, new MainMenu("Minecraft " + minecraft));
        }
        String handler = "1.7.10".equals(minecraft) ? "cpw.mods.fml.common.FMLCommonHandler"
                : "net.minecraftforge.fml.common.FMLCommonHandler";
        return builder.patch(handler, new FmlBranding());
    }

    /** {@code builder.addAll(sidedDelegate.getAdditionalBrandingInformation())} → {@code ...addAll(Branding.withLich(...))}. */
    static final class FmlBranding implements ClassPatch {
        @Override
        public boolean apply(ClassNode node) {
            for (MethodNode method : node.methods) {
                if (!method.name.equals("computeBranding")) {
                    continue;
                }
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof MethodInsnNode call && call.name.equals("getAdditionalBrandingInformation")
                            && call.desc.equals("()Ljava/util/List;")) {
                        method.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC, BRANDING, "withLich",
                                "(Ljava/util/List;)Ljava/util/List;", false));
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** The main menu's {@code "Minecraft 1.8.9"} constant → {@code Branding.withLich("Minecraft 1.8.9")}. */
    static final class MainMenu implements ClassPatch {
        private final String versionLine;

        MainMenu(String versionLine) {
            this.versionLine = versionLine;
        }

        @Override
        public boolean apply(ClassNode node) {
            boolean changed = false;
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof LdcInsnNode ldc && versionLine.equals(ldc.cst)) {
                        method.instructions.insert(ldc, new MethodInsnNode(Opcodes.INVOKESTATIC, BRANDING, "withLich",
                                "(Ljava/lang/String;)Ljava/lang/String;", false));
                        changed = true;
                    }
                }
            }
            return changed;
        }
    }
}

package dev.lich.compat.shared;

import dev.lich.transform.ClassPatch;
import dev.lich.transform.CompatTransformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Map;

/**
 * Puts {@link Branding}'s lines where the game says what it runs on. Under Forge: FML's branding list, which the
 * main menu draws bottom left, top to bottom ("Minecraft 1.8.9", "MCP 9.19", "Powered by Forge 11.15.1.2318",
 * Lich's line, Lich's mod count, FML's mod count), 1.7.10 shows on its F3 screen, and crash reports include. Lich's
 * line goes in through the list FML asks its client handler for ({@code getAdditionalBrandingInformation}), the slot
 * FML keeps for this, with Lich's mod count after it. The menu draws the list reversed, so FML's count is the lowest
 * line. In the vanilla profile, the main menu moves its "Minecraft 1.8.9" up two lines and draws Lich's line and mod
 * count under it, with the draw call the game uses for that one.
 */
public final class BrandingPatches {
    private static final String BRANDING = "dev/lich/compat/shared/Branding";
    private static final String FORGE_VERSION = "net/minecraftforge/common/ForgeVersion";
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

    /**
     * In {@code computeBranding}: {@code builder.addAll(sidedDelegate.getAdditionalBrandingInformation())} →
     * {@code ...addAll(Branding.withLich(..., ForgeVersion.getVersion()))}. FML adds its own mod count after that.
     * The finished list goes through {@code Branding.withoutPoweredBy}, which drops "Powered by Forge ...".
     */
    static final class FmlBranding implements ClassPatch {
        @Override
        public boolean apply(ClassNode node) {
            for (MethodNode method : node.methods) {
                if (!method.name.equals("computeBranding")) {
                    continue;
                }
                MethodInsnNode additional = null;
                MethodInsnNode build = null;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode call)) {
                        continue;
                    }
                    if (call.name.equals("getAdditionalBrandingInformation") && call.desc.equals("()Ljava/util/List;")) {
                        additional = call;
                    } else if (call.name.equals("build") && call.owner.endsWith("ImmutableList$Builder")) {
                        build = call;
                    }
                }
                if (additional == null || build == null) {
                    return false;
                }
                InsnList lich = new InsnList();
                lich.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FORGE_VERSION, "getVersion", "()Ljava/lang/String;", false));
                lich.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRANDING, "withLich",
                        "(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;", false));
                method.instructions.insert(additional, lich);
                // Lich's line already names the Forge version, so FML's "Powered by Forge" line goes.
                method.instructions.insert(build, new MethodInsnNode(Opcodes.INVOKESTATIC, BRANDING, "withoutPoweredBy",
                        "(Ljava/util/List;)Ljava/util/List;", false));
                return true;
            }
            return false;
        }
    }

    /**
     * The main menu's version line: {@code drawString(fontRenderer, s, 2, height - 10, -1)}, with {@code s} set from
     * the {@code "Minecraft 1.8.9"} constant. That call moves up two lines (to {@code height - 30}), and two copies of
     * it follow, at {@code height - 20} and {@code height - 10}, drawing {@code Branding.menuLine(1)} and
     * {@code (2)}. The copies are cloned from the original, so they use whatever names the game's classes have at
     * that point.
     */
    static final class MainMenu implements ClassPatch {
        private static final String DRAW_STRING = "(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;III)V";
        private static final int ARGUMENTS = 10;
        private final String versionLine;

        MainMenu(String versionLine) {
            this.versionLine = versionLine;
        }

        @Override
        public boolean apply(ClassNode node) {
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof LdcInsnNode ldc && versionLine.equals(ldc.cst)
                            && ldc.getNext() instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE
                            && addLines(method, store.var)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Finds the draw call that takes local {@code var} and adds the copies after it. */
        private static boolean addLines(MethodNode method, int var) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode call) || !call.desc.equals(DRAW_STRING)) {
                    continue;
                }
                AbstractInsnNode[] args = new AbstractInsnNode[ARGUMENTS];
                AbstractInsnNode previous = call;
                for (int i = ARGUMENTS - 1; i >= 0; i--) {
                    previous = previous.getPrevious();
                    if (previous == null) {
                        return false;
                    }
                    args[i] = previous;
                }
                // aload this, aload this, getfield fontRenderer, aload s, iconst_2, aload this, getfield height,
                // bipush 10, isub, iconst_m1
                boolean shape = args[3] instanceof VarInsnNode s && s.getOpcode() == Opcodes.ALOAD && s.var == var
                        && args[7] instanceof IntInsnNode ten && ten.operand == 10;
                if (!shape) {
                    continue;
                }
                ((IntInsnNode) args[7]).operand = 30;
                InsnList lines = new InsnList();
                for (int line = 1; line <= 2; line++) {
                    for (int i = 0; i < ARGUMENTS; i++) {
                        if (i == 3) {
                            lines.add(new IntInsnNode(Opcodes.BIPUSH, line));
                            lines.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRANDING, "menuLine",
                                    "(I)Ljava/lang/String;", false));
                            continue;
                        }
                        AbstractInsnNode copy = args[i].clone(Map.of());
                        if (i == 7) {
                            ((IntInsnNode) copy).operand = 30 - 10 * line;
                        }
                        lines.add(copy);
                    }
                    lines.add(call.clone(Map.of()));
                }
                method.instructions.insert(call, lines);
                return true;
            }
            return false;
        }
    }
}

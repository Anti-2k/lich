package net.minecraftforge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

/** Compile-time stub of Forge 1.12.2's HUD, for Lich's Forge-only HUD hook: only what the hook names. */
public class GuiIngameForge extends GuiIngame {
    public static int left_height = 39;
    public static int right_height = 39;

    private ScaledResolution res;
    private RenderGameOverlayEvent eventParent;

    public GuiIngameForge(Minecraft mc) {
        super(mc);
    }

    protected void renderTitle(int width, int height, float partialTicks) {
    }

    protected void renderRecordOverlay(int width, int height, float partialTicks) {
    }

    protected void renderChat(int width, int height) {
    }

    private boolean pre(RenderGameOverlayEvent.ElementType type) {
        return false;
    }

    private void post(RenderGameOverlayEvent.ElementType type) {
    }
}

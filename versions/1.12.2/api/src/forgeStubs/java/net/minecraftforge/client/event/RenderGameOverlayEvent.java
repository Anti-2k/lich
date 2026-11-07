package net.minecraftforge.client.event;

import net.minecraft.client.gui.ScaledResolution;

/** Compile-time stub of Forge 1.12.2's HUD event: the getters and element types Lich's HUD hook reads. */
public class RenderGameOverlayEvent {
    public float getPartialTicks() {
        return 0;
    }

    public ScaledResolution getResolution() {
        return null;
    }

    public ElementType getType() {
        return null;
    }

    public enum ElementType {
        ALL, HELMET, PORTAL, CROSSHAIRS, BOSSHEALTH, BOSSINFO, ARMOR, HEALTH, FOOD, AIR, HOTBAR, EXPERIENCE, TEXT,
        HEALTHMOUNT, JUMPBAR, CHAT, PLAYER_LIST, DEBUG, POTION_ICONS, SUBTITLES, FPS_GRAPH, VIGNETTE
    }
}

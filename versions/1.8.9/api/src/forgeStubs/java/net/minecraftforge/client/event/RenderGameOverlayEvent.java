package net.minecraftforge.client.event;

import net.minecraft.client.gui.ScaledResolution;

/** Compile-time stub of Forge 1.8.9's HUD event: the fields and element types Lich's HUD hook reads. */
public class RenderGameOverlayEvent {
    public final float partialTicks;
    public final ScaledResolution resolution;
    public final ElementType type;

    public RenderGameOverlayEvent(float partialTicks, ScaledResolution resolution) {
        this.partialTicks = partialTicks;
        this.resolution = resolution;
        this.type = null;
    }

    public enum ElementType {
        ALL, HELMET, PORTAL, CROSSHAIRS, BOSSHEALTH, ARMOR, HEALTH, FOOD, AIR, HOTBAR, EXPERIENCE, TEXT, HEALTHMOUNT,
        JUMPBAR, CHAT, PLAYER_LIST, DEBUG
    }
}

package dev.lich.api.impl.v1_12_2;

import dev.lich.api.client.HudElement;
import dev.lich.api.client.HudEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.MathHelper;

/**
 * What the HUD hook's Mixins share: the frame's partial ticks and resolution, firing the events, and
 * where vanilla draws its bars. Client thread only.
 */
public final class HudHooks {
    private static float partialTicks;
    private static ScaledResolution resolution;

    private HudHooks() {
    }

    /** A new HUD frame (vanilla's renderGameOverlay; Forge passes its own values with each event). */
    public static void begin(float framePartialTicks, ScaledResolution frameResolution) {
        partialTicks = framePartialTicks;
        resolution = frameResolution;
    }

    /** Fires {@link HudEvents#PRE}; returns whether a listener cancelled the element. */
    public static boolean pre(HudElement element, int top) {
        return HudEvents.PRE.hasListeners() && HudEvents.PRE.fire(new HudEvents.Render(element, partialTicks, resolution, top)).isCanceled();
    }

    public static void post(HudElement element, int top) {
        if (HudEvents.POST.hasListeners()) {
            HudEvents.POST.fire(new HudEvents.Render(element, partialTicks, resolution, top));
        }
    }

    /** Fires with Forge's values (its HUD keeps its own partial ticks and resolution). */
    public static boolean pre(HudElement element, float framePartialTicks, ScaledResolution frameResolution, int top) {
        begin(framePartialTicks, frameResolution);
        return pre(element, top);
    }

    private static final java.util.Map<String, HudElement> FORGE_ELEMENTS = new java.util.HashMap<>();

    static {
        for (HudElement element : HudElement.values()) {
            FORGE_ELEMENTS.put(element.name(), element);
        }
        // Forge's text overlay has no Lich element (Lich has its F3 text event), and the title and action bar are not
        // Forge elements. Forge 1.12's new elements (potion icons, subtitles, FPS graph, vignette, boss info) have none.
        FORGE_ELEMENTS.remove("TITLE");
        FORGE_ELEMENTS.remove("ACTION_BAR");
    }

    /** Lich's element for the Forge element type called {@code forgeName}, or null if Lich has none. */
    public static HudElement forgeElement(String forgeName) {
        return FORGE_ELEMENTS.get(forgeName);
    }

    /**
     * The row vanilla draws a bar in: health, food and mount health on the bottom row, armour and air above
     * the health rows (absorption adds rows, which squeeze together past two).
     */
    public static int vanillaTop(HudElement element, ScaledResolution resolution) {
        int bottom = resolution.getScaledHeight() - 39;
        if (element != HudElement.ARMOR && element != HudElement.AIR) {
            return bottom;
        }
        if (!(Minecraft.getMinecraft().getRenderViewEntity() instanceof EntityPlayer player)) {
            return bottom - 10;
        }
        float maxHealth = (float) player.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).getAttributeValue();
        int rows = MathHelper.ceil((maxHealth + player.getAbsorptionAmount()) / 2.0F / 10.0F);
        int rowHeight = Math.max(10 - (rows - 2), 3);
        return bottom - (rows - 1) * rowHeight - 10;
    }
}

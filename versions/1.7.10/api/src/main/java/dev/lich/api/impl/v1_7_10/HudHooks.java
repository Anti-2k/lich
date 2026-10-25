package dev.lich.api.impl.v1_7_10;

import dev.lich.api.client.HudElement;
import dev.lich.api.client.HudEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

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

    public static ScaledResolution resolution() {
        return resolution;
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

    /**
     * The row vanilla draws a bar in: health, food and mount health on the bottom row, armour and air above
     * the health rows (absorption adds rows, which squeeze together past two).
     */
    public static int vanillaTop(HudElement element, int height) {
        int bottom = height - 39;
        if (element != HudElement.ARMOR && element != HudElement.AIR) {
            return bottom;
        }
        if (!(Minecraft.getMinecraft().renderViewEntity instanceof EntityPlayer player)) {
            return bottom - 10;
        }
        float maxHealth = (float) player.getEntityAttribute(SharedMonsterAttributes.maxHealth).getAttributeValue();
        int rows = MathHelper.ceiling_float_int((maxHealth + player.getAbsorptionAmount()) / 2.0F / 10.0F);
        int rowHeight = Math.max(10 - (rows - 2), 3);
        return bottom - (rows - 1) * rowHeight - 10;
    }
}

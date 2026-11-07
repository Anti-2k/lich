package dev.lich.api.impl.v1_12_2.mixin;

import net.minecraft.client.gui.GuiIngame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The HUD's title and action bar timers, private in vanilla, for the Forge HUD hook ({@code render.hud}). */
@Mixin(GuiIngame.class)
public interface GuiIngameAccessor {
    @Accessor("titlesTimer")
    int lich$titlesTimer();

    @Accessor("overlayMessageTime")
    int lich$overlayMessageTime();
}

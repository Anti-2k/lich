package dev.lich.api.impl.v1_7_10.mixin;

import net.minecraft.client.gui.GuiIngame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The HUD's record overlay timer, private in vanilla, for the Forge HUD hook ({@code render.hud}). */
@Mixin(GuiIngame.class)
public interface GuiIngameAccessor {
    @Accessor("recordPlayingUpFor")
    int lich$recordPlayingUpFor();
}

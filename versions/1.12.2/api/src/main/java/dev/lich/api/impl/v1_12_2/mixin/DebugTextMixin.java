package dev.lich.api.impl.v1_12_2.mixin;

import dev.lich.api.client.HudEvents;
import net.minecraft.client.gui.GuiOverlayDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Hook {@code render.hud}, the F3 text. Both columns come from {@code GuiOverlayDebug}: the left from
 * {@code call()}, the right from {@code getDebugInfoRight()}, asked for in that order every frame by
 * vanilla and by Forge's HUD alike. The event needs both at once, so when the left one is made this asks
 * for the right one too and hands that list out for the request that follows.
 */
@Mixin(GuiOverlayDebug.class)
public abstract class DebugTextMixin {
    @Unique
    private List<String> lich$right;

    @Shadow
    protected abstract <T extends Comparable<T>> List<String> getDebugInfoRight();

    @Inject(method = "call", at = @At("RETURN"))
    private void lich$debugText(CallbackInfoReturnable<List<String>> cir) {
        if (!HudEvents.DEBUG_TEXT.hasListeners()) {
            return;
        }
        lich$right = null;
        List<String> right = getDebugInfoRight();
        HudEvents.DEBUG_TEXT.fire(new HudEvents.DebugText(cir.getReturnValue(), right));
        lich$right = right;
    }

    @Inject(method = "getDebugInfoRight", at = @At("HEAD"), cancellable = true)
    private void lich$preparedRight(CallbackInfoReturnable<List<String>> cir) {
        if (lich$right != null) {
            cir.setReturnValue(lich$right);
            lich$right = null;
        }
    }
}

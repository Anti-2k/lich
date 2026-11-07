package dev.lich.api.impl.v1_12_2.mixin;

import dev.lich.api.client.WorldRenderEvents;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hook {@code render.world}: right before the hand is drawn, after Forge's own "render last" with Forge. */
@Mixin(EntityRenderer.class)
public abstract class WorldLastMixin {
    @Inject(method = "renderWorldPass", at = @At(value = "INVOKE_STRING", args = "ldc=hand",
            target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V"))
    private void lich$last(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        if (WorldRenderEvents.LAST.hasListeners()) {
            WorldRenderEvents.LAST.fire(new WorldRenderEvents.Last(partialTicks));
        }
    }
}

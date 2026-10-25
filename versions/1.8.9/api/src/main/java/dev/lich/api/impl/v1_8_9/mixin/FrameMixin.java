package dev.lich.api.impl.v1_8_9.mixin;

import dev.lich.api.client.FrameEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Timer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hook {@code render.frame}: around the frame's drawing in {@code runGameLoop}, where Forge fires its render ticks. */
@Mixin(Minecraft.class)
public abstract class FrameMixin {
    @Shadow
    private Timer timer;

    @Inject(method = "runGameLoop", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/EntityRenderer;updateCameraAndRender(FJ)V"))
    private void lich$frameStart(CallbackInfo ci) {
        if (FrameEvents.START.hasListeners()) {
            FrameEvents.START.fire(new FrameEvents.Frame(timer.renderPartialTicks));
        }
    }

    @Inject(method = "runGameLoop", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/EntityRenderer;updateCameraAndRender(FJ)V"))
    private void lich$frameEnd(CallbackInfo ci) {
        if (FrameEvents.END.hasListeners()) {
            FrameEvents.END.fire(new FrameEvents.Frame(timer.renderPartialTicks));
        }
    }
}

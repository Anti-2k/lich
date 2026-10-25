package dev.lich.api.impl.v1_7_10.mixin;

import dev.lich.api.client.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hook {@code client.tick}: around {@code Minecraft.runTick}, where Forge fires its client tick events too. */
@Mixin(Minecraft.class)
public abstract class ClientTickMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void lich$tickStart(CallbackInfo ci) {
        if (ClientTickEvents.START.hasListeners()) {
            ClientTickEvents.START.fire(ClientTickEvents.Tick.INSTANCE);
        }
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void lich$tickEnd(CallbackInfo ci) {
        if (ClientTickEvents.END.hasListeners()) {
            ClientTickEvents.END.fire(ClientTickEvents.Tick.INSTANCE);
        }
    }
}

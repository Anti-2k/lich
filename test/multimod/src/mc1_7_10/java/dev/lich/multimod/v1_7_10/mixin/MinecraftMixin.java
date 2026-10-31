package dev.lich.multimod.v1_7_10.mixin;

import dev.lich.multimod.MultiMod;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft 1.7.10's Mixin, with that version's refmap: logs once the game starts. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "startGame", at = @At("RETURN"))
    private void lichmulti$started(CallbackInfo ci) {
        MultiMod.LOG.info("Lich multimod: 1.7.10 Mixin applied");
    }
}

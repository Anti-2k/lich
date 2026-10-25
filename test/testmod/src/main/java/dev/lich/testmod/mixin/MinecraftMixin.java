package dev.lich.testmod.mixin;

import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The mod's own Mixin, with a refmap and a shadowed field: logs once the game starts. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow
    public int displayWidth;

    @Inject(method = "startGame", at = @At("RETURN"))
    private void lichtest$started(CallbackInfo ci) {
        LogManager.getLogger("Lich testmod").info("Lich testmod: mod Mixin applied (display width {})", displayWidth);
    }
}

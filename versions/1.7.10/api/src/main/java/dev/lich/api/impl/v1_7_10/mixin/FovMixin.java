package dev.lich.api.impl.v1_7_10.mixin;

import dev.lich.api.client.FovEvents;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hook {@code render.fov}: the value {@code getFOVMultiplier} returns (with Forge, after Forge's FOV event). */
@Mixin(EntityPlayerSP.class)
public abstract class FovMixin {
    @Inject(method = "getFOVMultiplier", at = @At("RETURN"), cancellable = true)
    private void lich$fov(CallbackInfoReturnable<Float> cir) {
        if (FovEvents.UPDATE.hasListeners()) {
            cir.setReturnValue(FovEvents.UPDATE.fire(new FovEvents.Update((EntityPlayer) (Object) this, cir.getReturnValueF())).newFov());
        }
    }
}

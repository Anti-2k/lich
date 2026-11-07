package dev.lich.api.impl.v1_12_2.mixin;

import dev.lich.api.client.EntityRenderEvents;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hook {@code render.entity}: around {@code RenderLivingBase.doRender} and after {@code renderName}, as Forge's events. */
@Mixin(RenderLivingBase.class)
public abstract class EntityRenderMixin {
    @Inject(method = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void lich$pre(EntityLivingBase entity, double x, double y, double z, float yaw, float partialTicks, CallbackInfo ci) {
        if (EntityRenderEvents.PRE.hasListeners()
                && EntityRenderEvents.PRE.fire(new EntityRenderEvents.Living(entity, lich$self(), x, y, z)).isCanceled()) {
            ci.cancel();
        }
    }

    @Inject(method = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V", at = @At("RETURN"))
    private void lich$post(EntityLivingBase entity, double x, double y, double z, float yaw, float partialTicks, CallbackInfo ci) {
        if (EntityRenderEvents.POST.hasListeners()) {
            EntityRenderEvents.POST.fire(new EntityRenderEvents.Living(entity, lich$self(), x, y, z));
        }
    }

    @Inject(method = "renderName(Lnet/minecraft/entity/EntityLivingBase;DDD)V", at = @At("RETURN"))
    private void lich$nametagPost(EntityLivingBase entity, double x, double y, double z, CallbackInfo ci) {
        if (EntityRenderEvents.NAMETAG_POST.hasListeners()) {
            EntityRenderEvents.NAMETAG_POST.fire(new EntityRenderEvents.Living(entity, lich$self(), x, y, z));
        }
    }

    private RenderLivingBase<?> lich$self() {
        return (RenderLivingBase<?>) (Object) this;
    }
}

package dev.lich.api.impl.v1_8_9.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.lich.api.client.FogEvents;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hook {@code render.fog}. The density goes through every {@code setFogDensity} call of {@code setupFog}
 * (Forge's density hook calls it too); the distance fog event fires where both vanilla and Forge finish
 * setting up fog, when the branch for the edge of the render distance ran; the colour where the frame's
 * clear colour is set. OptiFine replaces the class; these points are all still in its version.
 */
@Mixin(EntityRenderer.class)
public abstract class FogMixin {
    @Shadow
    private Minecraft mc;
    @Shadow
    private float farPlaneDistance;
    @Shadow
    private boolean cloudFog;
    @Shadow
    private float fogColorRed;
    @Shadow
    private float fogColorGreen;
    @Shadow
    private float fogColorBlue;

    @ModifyArg(method = "setupFog", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;setFogDensity(F)V"))
    private float lich$density(float density, @Local(argsOnly = true) float partialTicks) {
        if (!FogEvents.DENSITY.hasListeners()) {
            return density;
        }
        Entity entity = mc.getRenderViewEntity();
        Block block = ActiveRenderInfo.getBlockAtEntityViewpoint(mc.theWorld, entity, partialTicks);
        return FogEvents.DENSITY.fire(new FogEvents.Density(entity, block, partialTicks, density)).density();
    }

    @Inject(method = "setupFog", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;enableColorMaterial()V"))
    private void lich$distance(int startCoords, float partialTicks, CallbackInfo ci) {
        if (!FogEvents.DISTANCE.hasListeners()) {
            return;
        }
        Entity entity = mc.getRenderViewEntity();
        Block block = ActiveRenderInfo.getBlockAtEntityViewpoint(mc.theWorld, entity, partialTicks);
        // The branches before the distance fog's, in vanilla's order (OptiFine and Forge keep them).
        boolean blind = entity instanceof EntityLivingBase living && living.isPotionActive(Potion.blindness);
        Material material = block.getMaterial();
        if (!blind && !cloudFog && material != Material.water && material != Material.lava) {
            FogEvents.DISTANCE.fire(new FogEvents.Distance(entity, block, partialTicks, startCoords, farPlaneDistance));
        }
    }

    /** The call that sets the frame's clear colour from the fog colour: vanilla's and Forge's, or OptiFine's own. */
    @WrapOperation(method = "updateFogColor", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;clearColor(FFFF)V"),
            @At(value = "INVOKE", target = "Lnet/optifine/shaders/Shaders;setClearColor(FFFF)V", remap = false)})
    private void lich$color(float red, float green, float blue, float alpha, Operation<Void> clear, @Local(argsOnly = true) float partialTicks) {
        if (FogEvents.COLOR.hasListeners()) {
            Entity entity = mc.getRenderViewEntity();
            Block block = ActiveRenderInfo.getBlockAtEntityViewpoint(mc.theWorld, entity, partialTicks);
            FogEvents.Color color = FogEvents.COLOR.fire(new FogEvents.Color(entity, block, partialTicks, red, green, blue));
            red = fogColorRed = color.red();
            green = fogColorGreen = color.green();
            blue = fogColorBlue = color.blue();
        }
        clear.call(red, green, blue, alpha);
    }
}

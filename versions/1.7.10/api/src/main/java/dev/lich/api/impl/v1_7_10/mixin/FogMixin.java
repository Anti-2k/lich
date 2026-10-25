package dev.lich.api.impl.v1_7_10.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.lich.api.client.FogEvents;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hook {@code render.fog}. 1.7.10 sets fog with plain GL calls: the density goes through every
 * {@code glFogf(GL_FOG_DENSITY, …)} of {@code setupFog} (Forge's density hook makes one too); the distance
 * fog event fires at the {@code glColorMaterial} that ends the method, when the branch for the edge of the
 * render distance ran; the colour where the frame's clear colour is set. OptiFine replaces the class; these
 * points are all still in its version.
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

    @WrapOperation(method = "setupFog", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glFogf(IF)V", remap = false))
    private void lich$density(int name, float value, Operation<Void> fog, @Local(argsOnly = true) float partialTicks) {
        if (name == GL11.GL_FOG_DENSITY && FogEvents.DENSITY.hasListeners()) {
            EntityLivingBase entity = mc.renderViewEntity;
            Block block = ActiveRenderInfo.getBlockAtEntityViewpoint(mc.theWorld, entity, partialTicks);
            value = FogEvents.DENSITY.fire(new FogEvents.Density(entity, block, partialTicks, value)).density();
        }
        fog.call(name, value);
    }

    @Inject(method = "setupFog", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColorMaterial(II)V", remap = false))
    private void lich$distance(int startCoords, float partialTicks, CallbackInfo ci) {
        if (!FogEvents.DISTANCE.hasListeners()) {
            return;
        }
        EntityLivingBase entity = mc.renderViewEntity;
        Block block = ActiveRenderInfo.getBlockAtEntityViewpoint(mc.theWorld, entity, partialTicks);
        // The branches before the distance fog's, in vanilla's order (OptiFine and Forge keep them).
        Material material = block.getMaterial();
        if (!entity.isPotionActive(Potion.blindness) && !cloudFog && material != Material.water && material != Material.lava) {
            FogEvents.DISTANCE.fire(new FogEvents.Distance(entity, block, partialTicks, startCoords, farPlaneDistance));
        }
    }

    /** The call that sets the frame's clear colour from the fog colour: vanilla's and Forge's, or OptiFine's two. */
    @WrapOperation(method = "updateFogColor", at = {
            @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glClearColor(FFFF)V", remap = false),
            @At(value = "INVOKE", target = "Lshadersmod/client/Shaders;setClearColor(FFFF)V", remap = false)})
    private void lich$color(float red, float green, float blue, float alpha, Operation<Void> clear, @Local(argsOnly = true) float partialTicks) {
        if (FogEvents.COLOR.hasListeners()) {
            EntityLivingBase entity = mc.renderViewEntity;
            Block block = ActiveRenderInfo.getBlockAtEntityViewpoint(mc.theWorld, entity, partialTicks);
            FogEvents.Color color = FogEvents.COLOR.fire(new FogEvents.Color(entity, block, partialTicks, red, green, blue));
            red = fogColorRed = color.red();
            green = fogColorGreen = color.green();
            blue = fogColorBlue = color.blue();
        }
        clear.call(red, green, blue, alpha);
    }
}

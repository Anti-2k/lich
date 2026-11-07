package dev.lich.api.impl.v1_12_2.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.lich.api.client.WorldRenderEvents;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.RayTraceResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Hook {@code render.blockOutline}: the outline call; with Forge, after its own highlight event. */
@Mixin(EntityRenderer.class)
public abstract class BlockOutlineMixin {
    @WrapWithCondition(method = "renderWorldPass", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderGlobal;drawSelectionBox(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/util/math/RayTraceResult;IF)V"))
    private boolean lich$outline(RenderGlobal renderGlobal, EntityPlayer player, RayTraceResult target, int execute, float partialTicks) {
        return !WorldRenderEvents.BLOCK_OUTLINE.hasListeners()
                || !WorldRenderEvents.BLOCK_OUTLINE.fire(new WorldRenderEvents.BlockOutline(player, target, partialTicks)).isCanceled();
    }
}

package dev.lich.api.impl.v1_7_10.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.lich.api.client.WorldRenderEvents;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Hook {@code render.blockOutline}: both outline calls (in and out of water); with Forge, after its own highlight event. */
@Mixin(EntityRenderer.class)
public abstract class BlockOutlineMixin {
    @WrapWithCondition(method = "renderWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderGlobal;drawSelectionBox(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/util/MovingObjectPosition;IF)V"))
    private boolean lich$outline(RenderGlobal renderGlobal, EntityPlayer player, MovingObjectPosition target, int execute, float partialTicks) {
        return !WorldRenderEvents.BLOCK_OUTLINE.hasListeners()
                || !WorldRenderEvents.BLOCK_OUTLINE.fire(new WorldRenderEvents.BlockOutline(player, target, partialTicks)).isCanceled();
    }
}

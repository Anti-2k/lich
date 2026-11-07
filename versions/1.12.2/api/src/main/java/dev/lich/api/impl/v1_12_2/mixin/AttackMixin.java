package dev.lich.api.impl.v1_12_2.mixin;

import dev.lich.api.client.PlayerEvents;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hook {@code player.attack}, observe only (DOCS.md §6): after the attack packet is queued, before the client
 * applies the attack itself, where Forge fires its attack event. Nothing about the attack can change here.
 */
@Mixin(PlayerControllerMP.class)
public abstract class AttackMixin {
    @Inject(method = "attackEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayer;attackTargetEntityWithCurrentItem(Lnet/minecraft/entity/Entity;)V"))
    private void lich$attack(EntityPlayer player, Entity target, CallbackInfo ci) {
        if (PlayerEvents.ATTACK.hasListeners()) {
            PlayerEvents.ATTACK.fire(new PlayerEvents.Attack(player, target));
        }
    }
}

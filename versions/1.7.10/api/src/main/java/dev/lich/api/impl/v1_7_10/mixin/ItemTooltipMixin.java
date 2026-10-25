package dev.lich.api.impl.v1_7_10.mixin;

import dev.lich.api.client.ItemEvents;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Hook {@code item.tooltip}: the list {@code ItemStack.getTooltip} returns (with Forge, after Forge's tooltip event). */
@Mixin(ItemStack.class)
public abstract class ItemTooltipMixin {
    @Inject(method = "getTooltip", at = @At("RETURN"))
    private void lich$tooltip(EntityPlayer player, boolean advanced, CallbackInfoReturnable<List<String>> cir) {
        if (ItemEvents.TOOLTIP.hasListeners()) {
            ItemEvents.TOOLTIP.fire(new ItemEvents.Tooltip((ItemStack) (Object) this, player, cir.getReturnValue(), advanced));
        }
    }
}

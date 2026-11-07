package dev.lich.api.impl.v1_12_2.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lich.api.client.TooltipEvents;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.client.config.GuiUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Hook {@code render.tooltip} with Forge, which draws every tooltip in {@code GuiUtils.drawHoveringText} instead
 * (the version without an item stack calls this one).
 */
@Mixin(value = GuiUtils.class, remap = false)
public abstract class TooltipForgeMixin {
    @Inject(method = "drawHoveringText(Lnet/minecraft/item/ItemStack;Ljava/util/List;IIIIILnet/minecraft/client/gui/FontRenderer;)V",
            at = @At("HEAD"))
    private static void lich$tooltip(ItemStack stack, List<String> lines, int x, int y, int screenWidth, int screenHeight,
                                     int maxTextWidth, FontRenderer font, CallbackInfo ci) {
        if (!lines.isEmpty() && TooltipEvents.PRE.hasListeners()) {
            TooltipEvents.PRE.fire(new TooltipEvents.Pre(lines, x, y));
        }
    }

    @WrapOperation(method = "drawHoveringText(Lnet/minecraft/item/ItemStack;Ljava/util/List;IIIIILnet/minecraft/client/gui/FontRenderer;)V",
            at = @At(value = "INVOKE", remap = true,
                    target = "Lnet/minecraft/client/gui/FontRenderer;drawStringWithShadow(Ljava/lang/String;FFI)I"))
    private static int lich$line(FontRenderer font, String text, float x, float y, int color, Operation<Integer> draw) {
        if (TooltipEvents.LINE.hasListeners() && TooltipEvents.LINE.fire(new TooltipEvents.Line(text, x, y)).isCanceled()) {
            return 0;
        }
        return draw.call(font, text, x, y, color);
    }
}

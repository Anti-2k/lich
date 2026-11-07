package dev.lich.api.impl.v1_12_2.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lich.api.client.TooltipEvents;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Hook {@code render.tooltip} without Forge: vanilla draws every tooltip in {@code GuiScreen.drawHoveringText}. */
@Mixin(GuiScreen.class)
public abstract class TooltipVanillaMixin {
    @Inject(method = "drawHoveringText(Ljava/util/List;II)V", at = @At("HEAD"))
    private void lich$tooltip(List<String> lines, int x, int y, CallbackInfo ci) {
        if (!lines.isEmpty() && TooltipEvents.PRE.hasListeners()) {
            TooltipEvents.PRE.fire(new TooltipEvents.Pre(lines, x, y));
        }
    }

    @WrapOperation(method = "drawHoveringText(Ljava/util/List;II)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/FontRenderer;drawStringWithShadow(Ljava/lang/String;FFI)I"))
    private int lich$line(FontRenderer font, String text, float x, float y, int color, Operation<Integer> draw) {
        if (TooltipEvents.LINE.hasListeners() && TooltipEvents.LINE.fire(new TooltipEvents.Line(text, x, y)).isCanceled()) {
            return 0;
        }
        return draw.call(font, text, x, y, color);
    }
}

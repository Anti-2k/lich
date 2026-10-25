package dev.lich.api.impl.v1_8_9.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lich.api.client.ScreenEvents;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Hook {@code screen}. Each injection is on a call vanilla and Forge both make, so it works the same with
 * Forge, whose own screen events fire around the same calls.
 */
@Mixin(GuiScreen.class)
public abstract class ScreenMixin {
    @Shadow
    protected List<GuiButton> buttonList;

    @Inject(method = "setWorldAndResolution", at = @At("RETURN"))
    private void lich$initPost(CallbackInfo ci) {
        if (ScreenEvents.INIT_POST.hasListeners()) {
            ScreenEvents.INIT_POST.fire(new ScreenEvents.Init((GuiScreen) (Object) this, buttonList));
        }
    }

    @WrapWithCondition(method = "mouseClicked", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiScreen;actionPerformed(Lnet/minecraft/client/gui/GuiButton;)V"))
    private boolean lich$actionPre(GuiScreen screen, GuiButton button) {
        return !ScreenEvents.ACTION_PRE.hasListeners()
                || !ScreenEvents.ACTION_PRE.fire(new ScreenEvents.Action(screen, button)).isCanceled();
    }

    @WrapOperation(method = "handleInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;handleMouseInput()V"))
    private void lich$mouseInput(GuiScreen screen, Operation<Void> handle) {
        if (ScreenEvents.MOUSE_INPUT_PRE.hasListeners()
                && ScreenEvents.MOUSE_INPUT_PRE.fire(new ScreenEvents.Input(screen)).isCanceled()) {
            return;
        }
        handle.call(screen);
        if (ScreenEvents.MOUSE_INPUT_POST.hasListeners()) {
            ScreenEvents.MOUSE_INPUT_POST.fire(new ScreenEvents.Input(screen));
        }
    }

    @WrapOperation(method = "handleInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;handleKeyboardInput()V"))
    private void lich$keyboardInput(GuiScreen screen, Operation<Void> handle) {
        if (ScreenEvents.KEYBOARD_INPUT_PRE.hasListeners()
                && ScreenEvents.KEYBOARD_INPUT_PRE.fire(new ScreenEvents.Input(screen)).isCanceled()) {
            return;
        }
        handle.call(screen);
        if (ScreenEvents.KEYBOARD_INPUT_POST.hasListeners()) {
            ScreenEvents.KEYBOARD_INPUT_POST.fire(new ScreenEvents.Input(screen));
        }
    }
}

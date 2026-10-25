package dev.lich.api.impl.v1_7_10.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lich.api.client.InputEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hook {@code input.mouse}: the in-game mouse loop of {@code runTick} ({@code while (Mouse.next())}) only
 * sees the events no listener cancelled. Wrapping the call keeps the loop's own code (and Forge's, which
 * fires its mouse event inside it) untouched.
 */
@Mixin(Minecraft.class)
public abstract class MouseInputMixin {
    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;next()Z", remap = false))
    private boolean lich$nextMouseEvent(Operation<Boolean> next) {
        while (next.call()) {
            if (!InputEvents.MOUSE.hasListeners()) {
                return true;
            }
            InputEvents.Mouse event = new InputEvents.Mouse(Mouse.getEventButton(), Mouse.getEventButtonState(),
                    Mouse.getEventDWheel(), Mouse.getEventDX(), Mouse.getEventDY());
            if (!InputEvents.MOUSE.fire(event).isCanceled()) {
                return true;
            }
        }
        return false;
    }
}

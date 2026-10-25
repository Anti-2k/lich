package dev.lich.api.impl.v1_8_9.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lich.api.client.ChatEvents;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hook {@code chat.receive}: the two calls that show a received message, to the chat or above the hotbar.
 * Forge's chat event changes the message before these calls, so Lich's listeners get what Forge mods left.
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class ChatMixin {
    @WrapOperation(method = "handleChat", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiNewChat;printChatMessage(Lnet/minecraft/util/IChatComponent;)V"))
    private void lich$chat(GuiNewChat chat, IChatComponent message, Operation<Void> show) {
        IChatComponent shown = lich$receive(false, message);
        if (shown != null) {
            show.call(chat, shown);
        }
    }

    @WrapOperation(method = "handleChat", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiIngame;setRecordPlaying(Lnet/minecraft/util/IChatComponent;Z)V"))
    private void lich$actionBar(GuiIngame hud, IChatComponent message, boolean isPlaying, Operation<Void> show) {
        IChatComponent shown = lich$receive(true, message);
        if (shown != null) {
            show.call(hud, shown, isPlaying);
        }
    }

    /** The message to show instead, or {@code null} to show none. */
    private static IChatComponent lich$receive(boolean actionBar, IChatComponent message) {
        if (!ChatEvents.RECEIVE.hasListeners()) {
            return message;
        }
        ChatEvents.Receive event = ChatEvents.RECEIVE.fire(new ChatEvents.Receive(actionBar, message));
        return event.isCanceled() ? null : event.message();
    }
}

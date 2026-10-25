package dev.lich.api.impl.v1_7_10.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lich.api.client.ChatEvents;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hook {@code chat.receive}: the call that shows a received message (1.7.10 has no action bar messages).
 * Forge's chat event changes the message before this call, so Lich's listeners get what Forge mods left.
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class ChatMixin {
    @WrapOperation(method = "handleChat", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiNewChat;printChatMessage(Lnet/minecraft/util/IChatComponent;)V"))
    private void lich$chat(GuiNewChat chat, IChatComponent message, Operation<Void> show) {
        if (!ChatEvents.RECEIVE.hasListeners()) {
            show.call(chat, message);
            return;
        }
        ChatEvents.Receive event = ChatEvents.RECEIVE.fire(new ChatEvents.Receive(false, message));
        if (!event.isCanceled() && event.message() != null) {
            show.call(chat, event.message());
        }
    }
}

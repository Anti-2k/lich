package dev.lich.api.impl.v1_12_2.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lich.api.client.ChatEvents;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.ITextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hook {@code chat.receive}: the one call that shows a received message, to the chat, the system messages or above
 * the hotbar ({@code ChatType.GAME_INFO}). Forge's chat event changes the message before this call, so Lich's
 * listeners get what Forge mods left.
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class ChatMixin {
    @WrapOperation(method = "handleChat", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiIngame;addChatMessage(Lnet/minecraft/util/text/ChatType;Lnet/minecraft/util/text/ITextComponent;)V"))
    private void lich$chat(GuiIngame hud, ChatType type, ITextComponent message, Operation<Void> show) {
        if (ChatEvents.RECEIVE.hasListeners()) {
            ChatEvents.Receive event = ChatEvents.RECEIVE.fire(new ChatEvents.Receive(type == ChatType.GAME_INFO, message));
            if (event.isCanceled()) {
                return;
            }
            message = event.message();
        }
        show.call(hud, type, message);
    }
}

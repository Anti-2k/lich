package dev.lich.multimod.v1_12_2;

import dev.lich.api.client.ChatEvents;
import dev.lich.api.client.ClientTickEvents;
import dev.lich.api.client.EntityRenderEvents;
import dev.lich.api.client.FogEvents;
import dev.lich.api.client.FovEvents;
import dev.lich.api.client.FrameEvents;
import dev.lich.api.client.HudEvents;
import dev.lich.api.client.InputEvents;
import dev.lich.api.client.ItemEvents;
import dev.lich.api.client.PlayerEvents;
import dev.lich.api.client.ScreenEvents;
import dev.lich.api.client.SoundEvents;
import dev.lich.api.client.TooltipEvents;
import dev.lich.api.client.WorldRenderEvents;
import dev.lich.api.entrypoint.ClientEntrypoint;
import dev.lich.api.event.Event;
import dev.lich.multimod.MultiMod;
import net.minecraft.client.Minecraft;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecraft 1.12.2's own entrypoint, from the jar's {@code versions} part. It reads a field this version's access
 * widener opened, and listens to every event of every hook (the jar lists them all for this version), logging the first
 * firing of each: a launch log shows which of the 1.12.2 hooks apply and fire, with and without Forge.
 */
public final class Client implements ClientEntrypoint {
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private static <E> void listen(Event<E> event, String name) {
        event.register(fired -> {
            if (SEEN.add(name)) {
                MultiMod.LOG.info("Lich multimod: 1.12.2 hook fired: {}", name);
            }
        });
    }

    @Override
    public void onClientReady() {
        MultiMod.LOG.info("Lich multimod: 1.12.2 entrypoint ran (widened debugFPS: {})", Minecraft.debugFPS >= 0);
        listen(ClientTickEvents.START, "ClientTickEvents.START");
        listen(ClientTickEvents.END, "ClientTickEvents.END");
        listen(FrameEvents.START, "FrameEvents.START");
        listen(FrameEvents.END, "FrameEvents.END");
        listen(ChatEvents.RECEIVE, "ChatEvents.RECEIVE");
        listen(FogEvents.DENSITY, "FogEvents.DENSITY");
        listen(FogEvents.DISTANCE, "FogEvents.DISTANCE");
        listen(FogEvents.COLOR, "FogEvents.COLOR");
        listen(FovEvents.UPDATE, "FovEvents.UPDATE");
        listen(HudEvents.PRE, "HudEvents.PRE");
        listen(HudEvents.POST, "HudEvents.POST");
        listen(HudEvents.DEBUG_TEXT, "HudEvents.DEBUG_TEXT");
        listen(InputEvents.MOUSE, "InputEvents.MOUSE");
        listen(ItemEvents.TOOLTIP, "ItemEvents.TOOLTIP");
        listen(PlayerEvents.ATTACK, "PlayerEvents.ATTACK");
        listen(ScreenEvents.INIT_POST, "ScreenEvents.INIT_POST");
        listen(ScreenEvents.ACTION_PRE, "ScreenEvents.ACTION_PRE");
        listen(ScreenEvents.MOUSE_INPUT_PRE, "ScreenEvents.MOUSE_INPUT_PRE");
        listen(ScreenEvents.MOUSE_INPUT_POST, "ScreenEvents.MOUSE_INPUT_POST");
        listen(ScreenEvents.KEYBOARD_INPUT_PRE, "ScreenEvents.KEYBOARD_INPUT_PRE");
        listen(ScreenEvents.KEYBOARD_INPUT_POST, "ScreenEvents.KEYBOARD_INPUT_POST");
        listen(SoundEvents.PLAY, "SoundEvents.PLAY");
        listen(TooltipEvents.PRE, "TooltipEvents.PRE");
        listen(TooltipEvents.LINE, "TooltipEvents.LINE");
        listen(WorldRenderEvents.LAST, "WorldRenderEvents.LAST");
        listen(WorldRenderEvents.BLOCK_OUTLINE, "WorldRenderEvents.BLOCK_OUTLINE");
        listen(EntityRenderEvents.PRE, "EntityRenderEvents.PRE");
        listen(EntityRenderEvents.POST, "EntityRenderEvents.POST");
        listen(EntityRenderEvents.NAMETAG_POST, "EntityRenderEvents.NAMETAG_POST");
    }
}
